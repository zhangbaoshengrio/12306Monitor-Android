package com.wizpizz.ticket12306.auto

import com.wizpizz.ticket12306.model.Station
import kotlinx.coroutines.delay

/** uiautomator 导出的一个界面节点。 */
data class UiNode(
    val text: String, val desc: String, val cls: String,
    val x1: Int, val y1: Int, val x2: Int, val y2: Int
) {
    /** 有的控件文字放在 text，有的放在 content-desc（比如结果页的车次行是 text），统一取一个。 */
    val label get() = text.ifBlank { desc }
    val cx get() = (x1 + x2) / 2
    val cy get() = (y1 + y2) / 2
}

object UiDump {
    private val nodeRe = Regex("<node [^>]*>")
    private val boundsRe = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")

    private fun unescape(s: String) =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun attr(node: String, key: String) =
        Regex(""" $key="([^"]*)"""").find(node)?.groupValues?.get(1)?.let(::unescape).orEmpty()

    fun parse(xml: String): List<UiNode> = nodeRe.findAll(xml).mapNotNull { m ->
        val n = m.value
        val b = boundsRe.find(attr(n, "bounds"))?.groupValues?.drop(1)?.map { it.toInt() } ?: return@mapNotNull null
        if (b[2] <= b[0] || b[3] <= b[1]) return@mapNotNull null
        UiNode(attr(n, "text"), attr(n, "content-desc"), attr(n, "class").substringAfterLast('.'), b[0], b[1], b[2], b[3])
    }.toList()
}

/**
 * 用 root 读屏、按文字点击，在 12306 App 里把一张票一路点到「确认订单」页。
 * 到这一页就停：不选乘车人、不点提交订单、不碰登录和支付，由用户自己接着操作。
 *
 * @param shell 以 root 执行一条命令并返回输出
 */
class Fill12306(
    private val shell: (String) -> String,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val onStatus: (String) -> Unit = {}
) {
    class Failure(message: String) : Exception(message)

    private fun foreground(): String =
        Regex("""topResumedActivity=\S* \S+ (\S+)/""")
            .find(shell("dumpsys activity activities | grep topResumedActivity"))?.groupValues?.get(1).orEmpty()

    /** uiautomator 有时会一直等界面「空闲」（首页的轮播广告）而卡住，所以限时、失败重试。 */
    private fun dump(): List<UiNode> {
        repeat(3) {
            val xml = shell(
                "rm -f $DUMP_FILE; timeout 10 uiautomator dump $DUMP_FILE >/dev/null 2>&1; cat $DUMP_FILE 2>/dev/null"
            )
            val nodes = UiDump.parse(xml)
            if (nodes.isNotEmpty()) return nodes
        }
        throw Failure("读不到 12306 的界面（uiautomator 没有响应）")
    }

    private fun guard() {
        if (foreground() != APP) throw Failure("12306 不在前台，已停止（不会乱点别的 App）")
    }

    private fun tap(n: UiNode) {
        guard()
        shell("input tap ${n.cx} ${n.cy}")
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 9000, cond: (List<UiNode>) -> Boolean): List<UiNode> {
        val start = System.currentTimeMillis()
        while (true) {
            // 页面正在加载时 uiautomator 可能读不出来，这不算失败，接着等
            val nodes = try { dump() } catch (e: Failure) { emptyList() }
            if (nodes.isNotEmpty() && cond(nodes)) return nodes
            if (System.currentTimeMillis() - start >= timeoutMs) {
                if (nodes.isEmpty()) throw Failure("读不到 12306 的界面（uiautomator 没有响应）")
                val seen = nodes.filter { it.text.isNotBlank() || it.desc.isNotBlank() }
                    .joinToString(" | ") { "${it.cls}:${it.label.take(14)}" }
                throw Failure("等不到「$what」。当时屏幕：${seen.take(500)}")
            }
            pause(500)
        }
    }

    /** 12306 偶尔会显示「未查询到符合条件的车次，点击重试」（网络或限流），点一下重试，最多 3 次。 */
    private suspend fun waitForResults(what: String, timeoutMs: Long, cond: (List<UiNode>) -> Boolean): List<UiNode> {
        repeat(3) { attempt ->
            try {
                return waitFor(what, timeoutMs, cond)
            } catch (e: Failure) {
                val retry = try { dump() } catch (e2: Failure) { emptyList() }.firstOrNull { it.label == "点击重试" }
                if (retry == null || attempt == 2) throw e
                onStatus("12306 没加载出来，重试…")
                tap(retry)
            }
        }
        throw Failure("等不到「$what」")
    }

    private fun isHome(nodes: List<UiNode>) = nodes.any { it.desc.startsWith("出发站:") } && nodes.any { it.text == "查询车票" }

    /** 回到 12306 首页（点界面上自带的返回按钮，不发系统返回键）。 */
    private suspend fun toHome(): List<UiNode> {
        var waited = 0L
        while (foreground() != APP) {
            if (waited >= 8000) throw Failure("12306 没有打开")
            pause(500); waited += 500
        }
        repeat(6) {
            val nodes = dump()
            if (isHome(nodes)) return nodes
            val back = nodes.firstOrNull { it.desc == "Back" || it.desc == "返回" }
                ?: throw Failure("找不到 12306 的首页，请先在 12306 里回到「火车票」首页")
            tap(back)
            pause(1200)
        }
        throw Failure("回不到 12306 首页")
    }

    /** 把出发/到达站设成 station；返回设置完成后的首页节点（省得再读一遍屏幕）。 */
    private suspend fun setStation(departure: Boolean, station: Station, home: List<UiNode>): List<UiNode> {
        val prefix = if (departure) "出发站:" else "到达站:"
        val field = home.first { it.desc.startsWith(prefix) }
        if (field.desc.removePrefix(prefix).trim() == station.name) return home

        tap(field)
        val picker = waitFor("车站选择页") { list -> list.any { it.text == "车站选择" } }
        // 常用/热门里的按钮文字就是站名；搜索结果里是「火车站 杨桥站」
        fun matches(n: UiNode) = n.cls == "Button" && (n.text == station.name ||
            (n.text.startsWith("火车站") && n.text.removePrefix("火车站").trim().removeSuffix("站") == station.name))
        fun button(list: List<UiNode>, minY: Int) = list.firstOrNull { matches(it) && it.y1 > minY }
        var target = button(picker, 800)
        if (target == null) {
            // 不在常用/热门里：在搜索框里输入拼音，再点结果
            val edit = picker.firstOrNull { it.cls == "EditText" } ?: throw Failure("车站选择页里找不到搜索框")
            tap(edit)
            pause(500)
            shell("input text ${station.pinyin.ifEmpty { station.initials }}")
            pause(1500)
            target = button(dump(), 463) ?: throw Failure("车站选择页里找不到「${station.name}」")
        }
        tap(target)
        return waitFor("回到 12306 首页") { isHome(it) }
    }

    /** 车次在 12306 界面里的写法：「G 1 5 4 5次列车」。 */
    private fun spaced(trainNo: String) = trainNo.toList().joinToString(" ") + "次列车"

    /** 界面树里也有屏幕下方还没滚出来的行，所以一屏一屏往下（或往上）滚着找，滚不动了就是没有。 */
    private suspend fun scan(down: Boolean, find: (List<UiNode>) -> UiNode?): UiNode? {
        var previous = emptySet<String>()
        var screen = dump()
        repeat(20) {
            find(screen)?.let { return it }
            val seen = screen.map { it.label }.toSet()
            if (seen == previous) return null
            previous = seen
            guard()
            shell(if (down) "input swipe 640 2200 640 1200 400" else "input swipe 640 1200 640 2200 400")
            pause(900)
            screen = dump()
        }
        return null
    }

    /** 结果页选日期：日期条里有就直接点，没有（日期条只有 7 天）就进「日历」点。 */
    private suspend fun pickDate(results: List<UiNode>, monthDay: String) {
        fun inBar(list: List<UiNode>) =
            list.firstOrNull { it.cls == "RadioButton" && it.text.startsWith("$monthDay ") && it.x2 - it.x1 > 50 }
        val day = inBar(results)
        if (day != null) {
            tap(day)
        } else {
            tap(results.first { it.text == "日历" })
            val calendar = waitFor("日历") { list -> list.any { it.text == "选择日期" } }
            val cell = calendar.firstOrNull { it.text.startsWith("$monthDay,") && it.y2 < 2700 }
                ?: throw Failure("12306 的日历里没有 $monthDay")
            if ("暂未开售" in cell.text) throw Failure("$monthDay 的票 12306 还没开售")
            tap(cell)
            waitFor("回到查询结果") { inBar(it) != null }
        }
        pause(2500)
    }

    /** 等「确认订单」页；等不到多半是 12306 要先登录，或者弹了别的提示，交给用户自己处理。 */
    private suspend fun waitForOrderPage() {
        try {
            waitFor("确认订单", 12000) { list -> list.any { it.text == "确认订单" } }
        } catch (e: Failure) {
            throw Failure("没有进入「确认订单」页（可能要先登录 12306，或 12306 弹了提示），请自己接着操作")
        }
    }

    /** 直达：在车次列表里找到这趟车，点开，再点这个席别的「预订」。 */
    private suspend fun bookDirect(trainNo: String, seat: String): String {
        fun isRow(n: UiNode) = n.cls == "Button" && "次列车" in n.label && "出发," in n.label
        waitForResults("车次列表", 12000) { list -> list.any(::isRow) }
        val name = spaced(trainNo)
        // 行的文字形如「G 1 5 4 5次列车11点42分从武汉出发,15点38分到达…」，有的前面还带「复兴号智能动车组」；顶上吸顶的筛选栏会盖住 y<720 的部分
        fun trainRow(list: List<UiNode>) = list.firstOrNull { isRow(it) && name in it.label && it.y1 > 720 && it.y2 < 2450 }
        var row = scan(true, ::trainRow) ?: scan(false, ::trainRow)
            ?: throw Failure("12306 的结果里没找到 $trainNo，可能这天没有这趟车")
        if (row.cy > 1700) {
            // 席别是在这一行下面展开的，先把这一行挪到屏幕上半部
            guard()
            shell("input swipe 640 1800 640 1100 400")
            pause(900)
            row = trainRow(dump()) ?: throw Failure("滚动后找不到 $trainNo 了，请自己点开它")
        }
        tap(row)
        val panel = waitFor("席别和预订按钮") { list -> list.any { it.cls == "Button" && (it.text == "预订" || it.text == "候补") } }
        val top = (trainRow(panel) ?: row).y2
        val bottom = panel.filter { isRow(it) && it.y1 > top }.minOfOrNull { it.y1 } ?: Int.MAX_VALUE
        val short = seatName(seat)
        // 展开后每个席别一行：左边席别名，中间价格和余票，右边「预订」或「候补」
        val button = panel.firstOrNull { b ->
            b.cls == "Button" && (b.text == "预订" || b.text == "候补") && b.y1 > top && b.y2 < bottom &&
                panel.any { it.cls == "TextView" && it.x1 < 300 && kotlin.math.abs(it.cy - b.cy) < 70 && (it.text == short || it.text == seat) }
        } ?: throw Failure("12306 里 $trainNo 没有「$short」这个席别，请自己点预订")
        val soldOut = button.text != "预订" ||
            panel.any { it.cls == "TextView" && kotlin.math.abs(it.cy - button.cy) < 70 && (it.text == "无" || it.text == "售罄") }
        if (soldOut) throw Failure("12306 显示 $trainNo 的$short 已经没票了，请自己换个席别")
        if (button.y2 > 2500) throw Failure("「$short」的预订按钮在屏幕外，请自己点")
        tap(button)
        waitForOrderPage()
        return "已到「确认订单」页（$trainNo $short），请自己选乘车人、提交订单"
    }

    /** 换座：在「中转」里找这趟车在 via 站「车内换座」的方案，点开，两张票在同一个订单里。 */
    private suspend fun bookSwap(trainNo: String, via: String): String {
        tap(dump().firstOrNull { it.text == "中转" && it.cls == "View" } ?: throw Failure("12306 的结果页里找不到「中转」"))
        try {
            waitForResults("中转方案", 15000) { list -> list.any { "经停详情" in it.label && "全程" in it.label } }
        } catch (e: Failure) {
            throw Failure("12306 的「中转」里没有列出方案，两张票请分开买")
        }
        // 方案的文字形如「11:42 武汉 14:53到达 全程3小时56分 14:58出发 永州 G 1 5 4 5次列车经停详情 车内换座 G 1 5 4 5次列车经停详情 15:38 全州南」
        val name = spaced(trainNo)
        fun plan(list: List<UiNode>) = list.firstOrNull {
            "${name}经停详情 车内换座 ${name}经停详情" in it.label && "出发 $via " in it.label && it.cy in 720..2480
        }
        val row = scan(true, ::plan)
            ?: throw Failure("12306 的「中转」里没有 $trainNo 在${via}车内换座的方案，两张票请分开买")
        tap(row)
        val next = waitFor("换座提示") { list -> list.any { it.text == "同意" || it.text == "确认订单" } }
        // 12306 会先弹「本方案为车内换座，需要在某站更换座位」，只有是这句话才点同意
        next.firstOrNull { it.text == "同意" }?.let { agree ->
            if (next.none { "车内换座" in it.text }) throw Failure("12306 弹了一个提示，请自己看一下再继续")
            tap(agree)
        }
        waitForOrderPage()
        return "已到「确认订单」页（$trainNo，在${via}换座，两张票一起），请自己核对席别、选乘车人、提交订单"
    }

    /**
     * 填好出发站、到达站，查询，选日期，找到这张票，一直走到「确认订单」页就停。
     * @param monthDay 形如「11月14日」
     * @param seat     席别，如「二等座」；换座的一组票用 12306 默认选中的席别
     * @param via      换座站；不为空时走「中转」里的车内换座方案，from/to 是整段行程的起点和终点
     */
    suspend fun run(from: Station, to: Station, monthDay: String, trainNo: String, seat: String, via: String = ""): String {
        onStatus("正在打开 12306…")
        var home = toHome()
        home = setStation(true, from, home)
        home = setStation(false, to, home)

        onStatus("查询 ${from.name}→${to.name}…")
        tap(home.first { it.text == "查询车票" })
        val results = waitForResults("查询结果", 12000) { list ->
            list.any { it.cls == "RadioButton" && "月" in it.text } && list.any { it.text == "日历" }
        }
        pickDate(results, monthDay)
        onStatus("正在找 $trainNo…")
        return if (via.isEmpty()) bookDirect(trainNo, seat) else bookSwap(trainNo, via)
    }

    companion object {
        const val APP = "com.MobileTicket"

        /** 席别在 12306 界面里的叫法：二等座→二等，硬座、软座、无座不变。 */
        fun seatName(label: String) = if (label in setOf("无座", "硬座", "软座")) label else label.removeSuffix("座")
        private const val DUMP_FILE = "/data/local/tmp/t12306.xml"
    }
}

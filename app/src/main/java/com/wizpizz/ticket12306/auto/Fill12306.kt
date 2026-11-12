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
 * 用 root 读屏、按文字点击，把 12306 App 首页的出发站、到达站填好。
 * 只填到这里：不点查询、不点预订、不碰登录和支付，由用户自己接着操作。
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
            val nodes = dump()
            if (cond(nodes)) return nodes
            if (System.currentTimeMillis() - start >= timeoutMs) {
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
                val retry = dump().firstOrNull { it.label == "点击重试" }
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

    /**
     * 只把出发站、到达站填好，停在 12306 首页，「查询车票」由用户自己点。
     * @param monthDay 形如「11月14日」，只用来提醒：首页的日期不是它时，请用户自己改
     * @param trainNo  车次，只用在提示里
     */
    suspend fun run(from: Station, to: Station, monthDay: String, trainNo: String): String {
        onStatus("正在打开 12306…")
        var home = toHome()
        home = setStation(true, from, home)
        home = setStation(false, to, home)

        val dateOk = home.any { it.desc.startsWith("出发日期") && monthDay in it.desc }
        return "已填好 ${from.name}→${to.name}（$trainNo），" +
            if (dateOk) "点「查询车票」即可" else "请把日期改成 $monthDay，再点「查询车票」"
    }

    companion object {
        const val APP = "com.MobileTicket"
        private const val DUMP_FILE = "/data/local/tmp/t12306.xml"
    }
}

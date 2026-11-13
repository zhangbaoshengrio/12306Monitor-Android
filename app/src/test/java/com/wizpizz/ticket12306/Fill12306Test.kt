package com.wizpizz.ticket12306

import com.wizpizz.ticket12306.auto.Fill12306
import com.wizpizz.ticket12306.model.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 用一部假的手机（几张照着真机 2026-11-13 的界面抄下来的页面 + 点哪里跳哪页）跑「去12306」。 */
class Fill12306Test {
    private class N(val text: String, val cls: String, val x1: Int, val y1: Int, val x2: Int, val y2: Int, val desc: String = "")

    private val bar13 = listOf(
        N("11月13日 周五", "RadioButton", 45, 316, 192, 463), N("11月19日 周四", "RadioButton", 1106, 316, 1253, 463)
    )
    private val bar26 = listOf(
        N("11月21日 周六", "RadioButton", 0, 316, 7, 463), N("11月26日 周四", "RadioButton", 745, 316, 892, 463)
    )
    private val tabs = listOf(
        N("日历", "TextView", 1148, 400, 1225, 442), N("直达", "View", 0, 498, 640, 655), N("中转", "View", 637, 498, 1272, 655)
    )
    private val trains = listOf(
        N("G 1 4 2 9次列车7点6分从武汉出发11点10分到达全州南,历时4小时4分,", "Button", 126, 925, 329, 1054),
        N("G 1 5 4 5次列车11点42分从武汉出发,15点38分到达全州南,历时3小时56分,,静音车厢，", "Button", 77, 1250, 343, 1394)
    )
    private val next = N("G 1 4 2 9次列车7点6分从武汉出发,11点10分到达全州南,历时4小时4分,,静音车厢，", "Button", 77, 2248, 343, 2391)
    private fun seatRow(name: String, status: String, button: String, y: Int) = listOf(
        N(name, "TextView", 84, y, 182, y + 60), N(status, "TextView", 770, y, 868, y + 60), N(button, "Button", 1025, y - 20, 1190, y + 85)
    )
    private val order = listOf(
        N("确认订单", "TextView", 496, 141, 776, 309), N("选择乘车人", "Button", 24, 1303, 1249, 1464),
        N("提交订单", "Button", 77, 1695, 1200, 1852)
    )

    private val screens = mapOf(
        "home" to listOf(
            N("", "TextView", 116, 748, 278, 913, "出发站: 武汉"), N("", "TextView", 900, 748, 1100, 913, "到达站: 全州南"),
            N("查询车票", "Button", 60, 1200, 1200, 1340)
        ),
        "results13" to bar13 + tabs + trains,
        "calendar" to listOf(
            N("选择日期", "TextView", 496, 141, 776, 309), N("11月2日,周1,农历九月廿四", "Button", 182, 600, 367, 800),
            N("11月26日,周4,农历十月十八", "ToggleButton", 728, 1292, 910, 1485),
            N("11月28日,周6,农历十月二十暂未开售,可设置开售提醒", "Button", 1092, 1292, 1272, 1516)
        ),
        "results26" to bar26 + tabs + trains,
        "panel" to bar26 + tabs + trains + seatRow("二等", "有票", "预订", 1492) + seatRow("一等", "售罄", "候补", 1670) +
            seatRow("无座", "无", "预订", 2027) + next,
        "plans" to bar26 + tabs + listOf(
            N("13:01 武汉 15:45到达 全程3小时39分 16:05出发 永州 G 3 0 9次列车经停详情 同站换乘20分 G 1 5 0 5次列车经停详情 16:40 全州南", "View", 70, 680, 1197, 914),
            N("11:42 武汉 14:53到达 全程3小时56分 14:58出发 永州 G 1 5 4 5次列车经停详情 车内换座 G 1 5 4 5次列车经停详情 15:38 全州南", "View", 70, 1863, 1197, 2097)
        ),
        "notice" to listOf(
            N("本方案为车内换座，需要在永州 站更换座位。", "TextView", 161, 1236, 1113, 1467),
            N("取消", "Button", 203, 1464, 619, 1604), N("同意", "Button", 665, 1464, 1071, 1604)
        ),
        "order" to order
    )

    private val jumps = mapOf(
        ("home" to "查询车票") to "results13", ("results13" to "日历") to "calendar",
        ("calendar" to "11月26日,周4,农历十月十八") to "results26",
        ("results26" to trains[1].text) to "panel", ("panel" to "预订@1492") to "order",
        ("results26" to "中转") to "plans", ("plans" to screens.getValue("plans").last().text) to "notice",
        ("notice" to "同意") to "order"
    )

    private inner class Phone {
        var screen = "home"
        var front = "com.MobileTicket"
        val taps = ArrayList<String>()

        fun shell(cmd: String): String {
            if ("topResumedActivity" in cmd) return "topResumedActivity=ActivityRecord{1 u0 $front/.Main t1}"
            if ("uiautomator dump" in cmd) return screens.getValue(screen).joinToString("\n") {
                """<node text="${it.text}" class="android.widget.${it.cls}" content-desc="${it.desc}" bounds="[${it.x1},${it.y1}][${it.x2},${it.y2}]" />"""
            }
            val tap = Regex("""input tap (\d+) (\d+)""").find(cmd) ?: return ""
            val (x, y) = tap.destructured.let { (a, b) -> a.toInt() to b.toInt() }
            val hit = screens.getValue(screen).filter { x in it.x1..it.x2 && y in it.y1..it.y2 }
                .minByOrNull { (it.x2 - it.x1) * (it.y2 - it.y1) } ?: return ""
            val label = hit.text.ifBlank { hit.desc }
            taps += label
            screen = jumps[screen to label] ?: jumps[screen to "$label@${hit.y1 + 20}"] ?: screen
            return ""
        }
    }

    private val wuhan = Station("武汉", "WHN", "wuhan", "wh")
    private val quanzhou = Station("全州南", "QNZ", "quanzhounan", "qzn")

    private fun go(phone: Phone, seat: String = "二等座", via: String = "", day: String = "11月26日") = runBlocking {
        Fill12306(phone::shell, pause = {}).run(wuhan, quanzhou, day, "G1545", seat, via)
    }

    @Test
    fun singleTicketGoesToTheConfirmPageAndStops() {
        val phone = Phone()
        val msg = go(phone)
        assertEquals("order", phone.screen)
        assertEquals(listOf("查询车票", "日历", "11月26日,周4,农历十月十八", trains[1].text, "预订"), phone.taps)
        assertTrue("确认订单" in msg)
    }

    @Test
    fun swapPairOpensTheSameTrainPlanAndStops() {
        val phone = Phone()
        go(phone, via = "永州")
        assertEquals("order", phone.screen)
        assertEquals("同意", phone.taps.last())
        assertFalse(phone.taps.any { it == "提交订单" || it == "选择乘车人" || it == "预订" })
    }

    @Test
    fun swapPlanForAnotherStationIsNotOpened() {
        val phone = Phone()
        try { go(phone, via = "衡阳东"); fail() } catch (e: Fill12306.Failure) { assertTrue("分开买" in e.message!!) }
        assertEquals("plans", phone.screen)
    }

    @Test
    fun soldOutSeatIsNotBooked() {
        for (seat in listOf("一等座", "无座", "商务座")) {
            val phone = Phone()
            try { go(phone, seat = seat); fail() } catch (e: Fill12306.Failure) { }
            assertEquals("panel", phone.screen)
            assertFalse("预订" in phone.taps || "候补" in phone.taps)
        }
    }

    @Test
    fun dateNotOnSaleYetStops() {
        val phone = Phone()
        try { go(phone, day = "11月28日"); fail() } catch (e: Fill12306.Failure) { assertTrue("没开售" in e.message!!) }
        assertEquals("calendar", phone.screen)
    }

    @Test
    fun stopsWhenAnotherAppComesToTheFront() {
        val phone = Phone().apply { screen = "results26"; front = "com.tencent.mm" }
        try { go(phone); fail() } catch (e: Fill12306.Failure) { }
        assertTrue(phone.taps.isEmpty())
    }
}

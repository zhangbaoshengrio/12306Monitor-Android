package com.wizpizz.ticket12306

import com.wizpizz.ticket12306.api.TicketApi
import com.wizpizz.ticket12306.api.TicketFinder
import com.wizpizz.ticket12306.model.QueryRequest
import com.wizpizz.ticket12306.model.RideKind
import com.wizpizz.ticket12306.model.StationData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * 连真实的 12306 跑一遍完整查询。默认不跑（要联网，而且会发十几到几十个请求）。
 * 手动跑：LIVE_12306=1 ./gradlew testDebugUnitTest --tests '*LiveTicketTest*' -i
 * 可选：LIVE_FROM=合肥南 LIVE_TO=宜昌东 LIVE_DATE=2026-11-13
 */
class LiveTicketTest {

    @Test
    fun realQueryEndToEnd() = runBlocking {
        assumeTrue("设置 LIVE_12306=1 才会连网运行", System.getenv("LIVE_12306") == "1")
        StationData.load(File("src/main/assets/station_name.js").readText())

        val from = StationData.findByName(System.getenv("LIVE_FROM") ?: "合肥南")!!
        val to = StationData.findByName(System.getenv("LIVE_TO") ?: "宜昌东")!!
        val date = System.getenv("LIVE_DATE") ?: LocalDate.now().plusDays(1).toString()

        val logs = mutableListOf<String>()
        val finder = TicketFinder(TicketApi())
        val result = finder.find(QueryRequest(date, from, to)) {
            logs += it
            println("LOG  $it")
        }

        println("==== ${from.name} → ${to.name}  $date  直达 ${result.directTrainCount} 趟，共发 ${result.queriesMade} 个请求，得到 ${result.tickets.size} 个票段")
        for (t in result.tickets.filter { it.hasSeats }) {
            val seats = t.seats.filter { it.available }.joinToString(" ") { "${it.label}${it.value}" }
            println("有票  ${t.trainNo}  ${t.fromName} ${t.departTime} → ${t.toName} ${t.arriveTime}  [${t.kind}]  $seats")
        }

        val priceMe = result.tickets.firstOrNull { it.hasSeats }
        if (priceMe != null) {
            val priced = finder.loadPrices(priceMe, date)
            println("价格  ${priced.trainNo} ${priced.fromName}→${priced.toName}：${priced.prices}")
            assertTrue("有票的票段应当查得到价格", priced.prices.isNotEmpty())
        }

        assertTrue("应当有直达车次", result.directTrainCount > 0)
        assertTrue(result.tickets.any { it.kind == RideKind.DIRECT })
        assertFalse(
            "站名不应是电报码",
            result.tickets.any { it.fromName.matches(Regex("[A-Z]{3}")) || it.toName.matches(Regex("[A-Z]{3}")) }
        )
    }
}

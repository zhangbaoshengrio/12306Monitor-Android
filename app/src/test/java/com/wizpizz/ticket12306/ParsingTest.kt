package com.wizpizz.ticket12306

import com.google.gson.JsonParser
import com.wizpizz.ticket12306.api.ResultParser
import com.wizpizz.ticket12306.model.SeatInfo
import com.wizpizz.ticket12306.model.StationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 用真实抓下来的 12306 响应测试解析。 */
class ParsingTest {

    private fun resource(name: String) =
        JsonParser.parseString(
            javaClass.classLoader!!.getResource(name)!!.readText()
        ).asJsonObject

    @Test
    fun parsesTrainsFromRealQueryResponse() {
        val resp = ResultParser.parseQuery(resource("left_ticket_sample.json"))
        assertEquals(listOf("D2255", "D3077", "D2373"), resp.trains.map { it.trainNo })
        assertEquals("合肥南", resp.stationNames["ENH"])
        assertEquals("宜昌东", resp.stationNames["HAN"])

        val d2373 = resp.trains.first { it.trainNo == "D2373" }
        assertEquals("5l000D237304", d2373.trainCode)
        assertEquals("ENH", d2373.fromCode)
        assertEquals("HAN", d2373.toCode)
        assertEquals("NKH", d2373.originCode)
        assertEquals("ICW", d2373.terminalCode)
        assertEquals("09:24", d2373.departTime)
        assertEquals("13:38", d2373.arriveTime)
        assertEquals("04:14", d2373.duration)
        // 价格接口要的是第 16、17 号字段（站序号），不是历时
        assertEquals("02", d2373.fromStationNo)
        assertEquals("08", d2373.toStationNo)
        assertEquals("MOO", d2373.seatTypes)

        val seats = d2373.seats.associate { it.label to it.value }
        assertEquals("有", seats["二等座"])
        assertEquals("13", seats["一等座"])
        assertEquals("有", seats["无座"])
        assertFalse("这趟车没有商务座，不应出现", seats.containsKey("商务座"))
    }

    @Test
    fun soldOutSeatIsKeptButNotAvailable() {
        val resp = ResultParser.parseQuery(resource("left_ticket_sample.json"))
        // 抓取时：D3077 二等座「无」、一等座 3 张；D2255 二等座 1 张、一等座「无」
        val d3077 = resp.trains.first { it.trainNo == "D3077" }
        val second = d3077.seats.first { it.label == "二等座" }
        assertEquals("无", second.value)
        assertFalse(second.available)
        assertTrue(d3077.seats.first { it.label == "一等座" }.available)

        val d2255 = resp.trains.first { it.trainNo == "D2255" }
        assertFalse(d2255.seats.first { it.label == "一等座" }.available)
        assertEquals("1", d2255.seats.first { it.label == "二等座" }.value)
    }

    @Test
    fun seatAvailabilityRules() {
        assertTrue(SeatInfo("二等座", "有").available)
        assertTrue(SeatInfo("二等座", "13").available)
        assertFalse(SeatInfo("二等座", "无").available)
        assertFalse(SeatInfo("二等座", "0").available)
    }

    @Test
    fun parsesStopListInOrder() {
        val stops = ResultParser.parseStopNames(resource("stops_sample.json"))
        assertEquals(14, stops.size)
        assertEquals(1, stops.indexOf("合肥南"))
        assertEquals(7, stops.indexOf("宜昌东"))
        assertEquals("成都东", stops.last())
    }

    @Test
    fun parsesStopTimesAndTreatsDashesAsMissing() {
        val stops = ResultParser.parseStops(resource("stops_sample.json"))
        assertEquals(14, stops.size)
        val first = stops.first()
        assertEquals("南京南", first.name)
        assertEquals("", first.arriveTime)      // 始发站没有到时
        assertEquals("08:32", first.departTime)
        assertEquals("", first.stayTime)
        val hefei = stops[1]
        assertEquals("合肥南", hefei.name)
        assertEquals("09:20", hefei.arriveTime)
        assertEquals("09:24", hefei.departTime)
        assertEquals("4分钟", hefei.stayTime)
    }

    @Test
    fun parsesPrices() {
        val prices = ResultParser.parsePrices(resource("price_sample.json"))
        assertEquals("¥241.0", prices["二等座"])
        assertEquals("¥386.0", prices["一等座"])
        assertEquals("¥241.0", prices["无座"])
        assertEquals(3, prices.size)
    }

    @Test
    fun shortOrMalformedTrainLineIsSkipped() {
        assertEquals(null, ResultParser.parseTrain("a|b|c"))
    }

    @Test
    fun officialStationListMatches12306() {
        val file = File("src/main/assets/station_name.js")
        assertTrue("找不到站名表：${file.absolutePath}", file.exists())
        StationData.load(file.readText())
        assertTrue("站点数量应在 3000 以上", StationData.search("", 1).isNotEmpty())
        assertEquals("ENH", StationData.findByName("合肥南")?.code)
        assertEquals("HAN", StationData.findByName("宜昌东")?.code)
        assertEquals("宜昌东", StationData.findByCode("HAN")?.name)
        assertEquals("VNP", StationData.findByName("北京南")?.code)
    }

    @Test
    fun stationSearchByNamePinyinAndInitials() {
        StationData.load(File("src/main/assets/station_name.js").readText())
        assertEquals("合肥南", StationData.search("合肥南").first().name)
        assertEquals("合肥南", StationData.search("hfn").first().name)
        assertTrue(StationData.search("hefei").map { it.name }.containsAll(listOf("合肥", "合肥南")))
        assertTrue(StationData.search("合肥").map { it.name }.containsAll(listOf("合肥", "合肥南")))
        val common = StationData.search("")
        assertTrue("空输入应返回常用站", common.size >= 10)
        assertNotNull(common.firstOrNull { it.name == "北京南" })
    }
}

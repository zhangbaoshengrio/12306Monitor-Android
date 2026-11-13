package com.wizpizz.ticket12306

import com.wizpizz.ticket12306.api.QueryResponse
import com.wizpizz.ticket12306.api.RawTrain
import com.wizpizz.ticket12306.api.TicketApiException
import com.wizpizz.ticket12306.api.TicketFinder
import com.wizpizz.ticket12306.api.TicketService
import com.wizpizz.ticket12306.model.QueryRequest
import com.wizpizz.ticket12306.model.RideKind
import com.wizpizz.ticket12306.model.SeatInfo
import com.wizpizz.ticket12306.model.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TicketFinderTest {

    // 这趟车的经停站：0 南京南  1 合肥南(上车)  2 六安  3 汉口  4 宜昌东(终点)  5 恩施
    private val stops = listOf(
        Station("南京南", "NKH"), Station("合肥南", "ENH"), Station("六安", "UAH"),
        Station("汉口", "HKN"), Station("宜昌东", "HAN"), Station("恩施", "ESN")
    )
    private val board = stops[1]
    private val dest = stops[4]
    private val request = QueryRequest("2026-11-13", board, dest)

    private val sold = listOf(SeatInfo("二等座", "无"), SeatInfo("一等座", "无"))

    private class Fake(
        val stopNames: List<String>,
        val trainNos: List<String>,
        val stationNames: Map<String, String>,
        val seatsFor: (String, String, String) -> List<SeatInfo>,
        val failPairQueries: (Int) -> Boolean = { false },  // 参数：第几次「非直达」票段查询（从 1 开始）
        val neighbourTrains: List<String> = emptyList()      // 12306 顺带返回的、终点是邻近车站（ABN）的车次
    ) : TicketService {
        val queryCalls = mutableListOf<Pair<String, String>>()
        val stopCalls = mutableListOf<String>()
        val priceCalls = mutableListOf<String>()
        private var pairQueryCount = 0

        override fun queryTickets(fromCode: String, toCode: String, date: String): QueryResponse {
            queryCalls += fromCode to toCode
            if (!(fromCode == "ENH" && toCode == "HAN")) {
                pairQueryCount++
                if (failPairQueries(pairQueryCount)) throw TicketApiException("boom")
            }
            fun raw(spec: String, to: String): RawTrain {
                val no = spec.substringBefore('@')
                val code = if ('@' in spec) spec.substringAfter('@') else "code-$spec"
                return RawTrain(
                    trainNo = no, trainCode = code, originCode = "NKH", terminalCode = "ESN",
                    fromCode = fromCode, toCode = to,
                    departTime = "09:00", arriveTime = "13:00", duration = "04:00",
                    fromStationNo = "02", toStationNo = "05", seatTypes = "MOO",
                    seats = seatsFor(fromCode, to, no)
                )
            }
            return QueryResponse(
                trains = trainNos.map { raw(it, toCode) } + neighbourTrains.map { raw(it, "ABN") },
                stationNames = stationNames
            )
        }

        override fun getStopNames(train: RawTrain, date: String): List<String> {
            stopCalls += train.trainNo
            return stopNames
        }

        override fun getStops(trainCode: String, originCode: String, terminalCode: String, date: String) =
            stopNames.map { com.wizpizz.ticket12306.model.StopInfo(it, "", "", "") }

        var failPrices = false

        override fun getPrices(
            trainCode: String, fromStationNo: String, toStationNo: String, seatTypes: String, date: String
        ): Map<String, String> {
            priceCalls += "$trainCode $fromStationNo->$toStationNo $seatTypes"
            if (failPrices) throw TicketApiException("price boom")
            return mapOf("二等座" to "¥100.0")
        }
    }

    private val names = stops.associate { it.code to it.name }

    private fun finder(service: TicketService, maxSegmentQueries: Int = 60, maxSwapQueries: Int = 0) = TicketFinder(
        service,
        stationByName = { n -> stops.firstOrNull { it.name == n } },
        stationByCode = { c -> stops.firstOrNull { it.code == c } },
        maxSegmentQueries = maxSegmentQueries,
        maxSwapQueries = maxSwapQueries,
        pause = {}
    )

    @Test
    fun queriesEachSegmentOnceAcrossTrainsAndClassifiesThem() = runBlocking {
        val service = Fake(
            stopNames = stops.map { it.name },
            trainNos = listOf("A", "B"),
            stationNames = names,
            seatsFor = { from, to, train ->
                when {
                    from == "NKH" && to == "HAN" && train == "A" -> listOf(SeatInfo("二等座", "5"))
                    from == "ENH" && to == "HKN" && train == "B" -> listOf(SeatInfo("二等座", "有"))
                    else -> sold
                }
            }
        )
        val logs = mutableListOf<String>()
        val result = finder(service).find(request) { logs += it }

        // 直达 1 次 + 5 个不同的票段各 1 次，两趟车共用，没有重复
        assertEquals(6, service.queryCalls.size)
        assertEquals(service.queryCalls.size, service.queryCalls.toSet().size)
        assertEquals(1, service.queryCalls.count { it == ("ENH" to "HAN") })
        assertEquals(listOf("A", "B"), service.stopCalls)

        val withSeats = result.tickets.filter { it.hasSeats }
        assertEquals(2, withSeats.size)

        val earlier = withSeats.first { it.fromName == "南京南" && it.toName == "宜昌东" }
        assertEquals("A", earlier.trainNo)
        assertEquals(RideKind.COVERS, earlier.kind)
        assertEquals(1, earlier.earlierStops)
        assertEquals(0, earlier.extraStops)

        val shorter = withSeats.first { it.fromName == "合肥南" && it.toName == "汉口" }
        assertEquals("B", shorter.trainNo)
        assertEquals(RideKind.ENDS_EARLY, shorter.kind)
        assertEquals(0, shorter.earlierStops)
        assertEquals(1, shorter.extraStops)

        // 没票的票段也保留：2 趟车 × (直达 + 5 个票段)
        assertEquals(2 * 6, result.tickets.size)
        assertEquals(2, result.tickets.count { it.kind == RideKind.DIRECT })

        // 价格不在 find 里查
        assertTrue(service.priceCalls.isEmpty())
        assertTrue(result.tickets.all { it.prices.isEmpty() })

        assertEquals(2, result.directTrainCount)
        assertEquals(1 + 2 + 5, result.queriesMade)
        assertTrue(logs.any { it.contains("直达 2 趟") })
        // 站名要显示名字，不是电报码
        assertTrue(result.tickets.none { it.fromName.matches(Regex("[A-Z]{3}")) || it.toName.matches(Regex("[A-Z]{3}")) })
    }

    @Test
    fun trainsWhoseDirectTicketHasSeatsAreNotSearchedFurther() = runBlocking {
        val service = Fake(
            stops.map { it.name }, listOf("A", "B"), names,
            { from, to, train ->
                if (from == "ENH" && to == "HAN" && train == "A") listOf(SeatInfo("二等座", "3")) else sold
            }
        )
        val result = finder(service).find(request)
        assertEquals(listOf("B"), service.stopCalls)            // 只有 B 直达没票，才去查它的经停站
        val a = result.tickets.filter { it.trainNo == "A" }
        assertEquals(1, a.size)                                  // A 只有直达这一张
        assertTrue(a.single().best)
        assertEquals(2, result.directTrainCount)
    }

    @Test
    fun noDirectTrainsMeansNothingElseIsQueried() = runBlocking {
        val service = Fake(stops.map { it.name }, emptyList(), names, { _, _, _ -> sold })
        val result = finder(service).find(request)
        assertTrue(result.tickets.isEmpty())
        assertEquals(1, result.queriesMade)
        assertTrue(service.stopCalls.isEmpty())
    }

    @Test
    fun trainWhoseStopsLackBothStationsIsSkipped() = runBlocking {
        val service = Fake(listOf("南京南", "合肥南", "六安"), listOf("A"), names, { _, _, _ -> sold })
        val logs = mutableListOf<String>()
        val result = finder(service).find(request) { logs += it }
        assertEquals(1, service.queryCalls.size)          // 只有直达那一次
        assertEquals(1, result.tickets.size)              // 直达票仍然返回
        assertTrue(logs.any { it.contains("跳过") })
    }

    @Test
    fun aSingleFailedSegmentDoesNotStopTheSearch() = runBlocking {
        val service = Fake(
            stops.map { it.name }, listOf("A"), names,
            seatsFor = { from, to, _ ->
                if (from == "ENH" && to == "UAH") listOf(SeatInfo("二等座", "3")) else sold
            },
            failPairQueries = { it == 1 }
        )
        val logs = mutableListOf<String>()
        val result = finder(service).find(request) { logs += it }
        assertTrue(logs.any { it.contains("出错") })
        assertTrue(result.tickets.any { it.toName == "六安" && it.hasSeats })
    }

    @Test
    fun stopsAfterThreeConsecutiveFailures() = runBlocking {
        val service = Fake(stops.map { it.name }, listOf("A"), names, { _, _, _ -> sold }, failPairQueries = { true })
        try {
            finder(service).find(request)
            fail("应该抛出异常")
        } catch (e: TicketApiException) {
            assertTrue(e.message!!.contains("连续 3 次"))
        }
        // 直达 1 次 + 3 次失败就停，不会把剩下的票段都试一遍
        assertEquals(4, service.queryCalls.size)
    }

    @Test
    fun trainsToANeighbouringStationAreNotTreatedAsDirect() = runBlocking {
        // 12306 查 合肥南→宜昌东 时，会把终点是宜昌北(ABN) 的车次也一起返回
        val service = Fake(
            stops.map { it.name }, listOf("A"), names, { _, _, _ -> sold },
            neighbourTrains = listOf("NEAR")
        )
        val logs = mutableListOf<String>()
        val result = finder(service).find(request) { logs += it }
        assertEquals(1, result.directTrainCount)
        assertTrue(result.tickets.none { it.trainNo == "NEAR" })
        assertEquals(listOf("A"), service.stopCalls)    // 也不会去查邻站车次的经停站
        assertTrue(logs.any { it.contains("邻近车站") })
    }

    @Test
    fun segmentQueriesAreCappedInSearchOrder() = runBlocking {
        val service = Fake(stops.map { it.name }, listOf("A"), names, { _, _, _ -> sold })
        val logs = mutableListOf<String>()
        finder(service, maxSegmentQueries = 2).find(request) { logs += it }

        // 直达之后，先查 合肥南→汉口、合肥南→六安
        assertEquals(
            listOf("ENH" to "HAN", "ENH" to "HKN", "ENH" to "UAH"),
            service.queryCalls
        )
        assertTrue(logs.any { it.contains("略过") })
    }

    @Test
    fun segmentsAreQueriedInTheOrderYouDescribed() = runBlocking {
        val service = Fake(stops.map { it.name }, listOf("A"), names, { _, _, _ -> sold })
        finder(service).find(request)
        // 起点 合肥南：终点 宜昌东(直达) → 汉口 → 六安；然后起点 南京南：宜昌东 → 汉口 → 六安
        assertEquals(
            listOf(
                "ENH" to "HAN", "ENH" to "HKN", "ENH" to "UAH",
                "NKH" to "HAN", "NKH" to "HKN", "NKH" to "UAH"
            ),
            service.queryCalls
        )
    }

    @Test
    fun theFirstSegmentWithSeatsInThatOrderIsMarkedBest() = runBlocking {
        val service = Fake(
            stops.map { it.name }, listOf("A"), names,
            { from, to, _ ->
                when {
                    from == "ENH" && to == "UAH" -> listOf(SeatInfo("二等座", "2"))   // 顺序里第 3 个
                    from == "NKH" && to == "HAN" -> listOf(SeatInfo("二等座", "9"))   // 顺序里第 4 个
                    else -> sold
                }
            }
        )
        val result = finder(service).find(request)
        val best = result.tickets.filter { it.best }
        assertEquals(1, best.size)
        assertEquals("六安", best.single().toName)
        assertEquals(2, best.single().order)
        assertEquals(2, best.single().extraStops)
        // 另一张有票但不是最优
        assertFalse(result.tickets.first { it.fromName == "南京南" && it.toName == "宜昌东" }.best)
    }

    @Test
    fun noSeatsAnywhereMeansNothingIsBest() = runBlocking {
        val service = Fake(stops.map { it.name }, listOf("A"), names, { _, _, _ -> sold })
        val result = finder(service).find(request)
        assertTrue(result.tickets.none { it.best })
    }

    @Test
    fun ticketsAreGroupedByTrainAndOrderedWithinEachTrain() = runBlocking {
        val service = Fake(stops.map { it.name }, listOf("A", "B"), names, { _, _, _ -> sold })
        val result = finder(service).find(request)
        val codes = result.tickets.map { it.trainCode }
        assertEquals(listOf("code-A", "code-B"), codes.distinct())
        // 每趟车的票连在一起，且按查询顺序排
        for (code in listOf("code-A", "code-B")) {
            val mine = result.tickets.filter { it.trainCode == code }
            assertEquals(mine.map { it.order }.sorted(), mine.map { it.order })
            assertEquals(RideKind.DIRECT, mine.first().kind)
        }
        assertEquals(codes, codes.sortedBy { listOf("code-A", "code-B").indexOf(it) })
    }

    @Test
    fun sameTrainNumberWithDifferentRunsAreBothKept() = runBlocking {
        // G583 这个车次号对应两趟不同的车（内部编号不同），都要保留
        val service = Fake(stops.map { it.name }, listOf("G583@c1", "G583@c2"), names, { _, _, _ -> sold })
        val result = finder(service).find(request)
        val direct = result.tickets.filter { it.kind == RideKind.DIRECT }
        assertEquals(2, direct.size)
        assertEquals(setOf("c1", "c2"), direct.map { it.trainCode }.toSet())
    }

    @Test
    fun loadPricesFillsInPricesForOneTicket() = runBlocking {
        val service = Fake(
            stops.map { it.name }, listOf("A"), names,
            { from, to, _ -> if (from == "NKH" && to == "HAN") listOf(SeatInfo("二等座", "5")) else sold }
        )
        val f = finder(service)
        val ticket = f.find(request).tickets.first { it.hasSeats }
        assertTrue(ticket.prices.isEmpty())

        val priced = f.loadPrices(ticket, request.date)
        assertEquals("¥100.0", priced.prices["二等座"])
        // 用的是这张票自己的编号和站序号
        assertEquals(listOf("code-A 02->05 MOO"), service.priceCalls)
        assertEquals(ticket.trainNo, priced.trainNo)
    }

    @Test
    fun loadPricesReturnsTheTicketUnchangedWhenThePriceLookupFails() = runBlocking {
        val service = Fake(
            stops.map { it.name }, listOf("A"), names,
            { from, to, _ -> if (from == "NKH" && to == "HAN") listOf(SeatInfo("二等座", "5")) else sold }
        )
        val f = finder(service)
        val ticket = f.find(request).tickets.first { it.hasSeats }
        service.failPrices = true
        assertEquals(ticket, f.loadPrices(ticket, request.date))
    }

    @Test
    fun soldOutSegmentsStayInTheResultsSoTheUserCanSeeWhatWasTried() = runBlocking {
        val service = Fake(stops.map { it.name }, listOf("A"), names, { _, _, _ -> sold })
        val result = finder(service).find(request)
        assertEquals(6, result.tickets.size)
        assertTrue(result.tickets.none { it.hasSeats })
    }

    @Test
    fun segmentPlanFollowsTheSearchOrder() {
        // 上车站第 1 位、终点站第 3 位、前面只有 1 个站：起点 1 然后 0；每个起点下终点 3 然后 2
        assertEquals(
            listOf(1 to 3, 1 to 2, 0 to 3, 0 to 2),
            TicketFinder.planSegments(boardIdx = 1, destIdx = 3, maxBefore = 3)
        )
        // 上车站前面很多站时，最多往前看 maxBefore 个
        val plan = TicketFinder.planSegments(boardIdx = 5, destIdx = 8, maxBefore = 3)
        assertEquals(4 * 3, plan.size)
        assertEquals(listOf(5 to 8, 5 to 7, 5 to 6, 4 to 8), plan.take(4))
        assertEquals(2 to 6, plan.last())
        assertFalse(plan.any { it.second <= it.first })
        assertFalse(plan.any { it.first > 5 })      // 起点不会在上车站之后
        assertFalse(plan.any { it.second <= 5 })    // 终点一定在上车站之后
        assertFalse(plan.any { it.second > 8 })     // 不会超过终点站

        // 上车站是第一站
        assertEquals(listOf(0 to 3, 0 to 2, 0 to 1), TicketFinder.planSegments(0, 3, 3))
    }

    @Test
    fun classifiesSegmentsAgainstTheRide() {
        val b = 1; val d = 4
        assertEquals(RideKind.DIRECT, TicketFinder.classify(1, 4, b, d))
        assertEquals(RideKind.COVERS, TicketFinder.classify(0, 4, b, d))
        assertEquals(RideKind.ENDS_EARLY, TicketFinder.classify(1, 3, b, d))
        assertEquals(RideKind.ENDS_EARLY, TicketFinder.classify(0, 2, b, d))
    }

    // ── 换座 ─────────────────────────────────────────────────

    private fun swapService(seatsFor: (String, String) -> List<SeatInfo>) = Fake(
        stopNames = stops.map { it.name }, trainNos = listOf("A"), stationNames = names,
        seatsFor = { from, to, _ -> seatsFor(from, to) }
    )

    @Test
    fun secondSeatJoinsWhereTheFirstTicketEnds() = runBlocking {
        val service = swapService { from, to ->
            when (from to to) {
                "ENH" to "HKN", "HKN" to "HAN" -> listOf(SeatInfo("二等座", "3"))
                else -> sold
            }
        }
        val result = finder(service, maxSwapQueries = 20).find(request)

        val second = result.tickets.single { it.kind == RideKind.SECOND_SEAT }
        assertEquals("汉口", second.fromName)
        assertEquals("宜昌东", second.toName)
        assertEquals("汉口", second.afterName)
        assertEquals("", second.date)
        assertFalse(second.best)
        // 最优的还是第一张票，换座票排在这趟车的最后
        assertEquals("汉口", result.tickets.single { it.best }.toName)
        assertEquals(second, result.tickets.last())
        // 正好接上就不再查别的换座起点
        assertEquals(1, service.queryCalls.count { it.second == "HAN" && it.first != "ENH" && it.first != "NKH" })
    }

    @Test
    fun secondSeatNeverLeavesAGap() = runBlocking {
        // 第一张票只能买到六安；汉口→宜昌东有票，但和六安接不上（12306 不让买中间断开的两段）
        val service = swapService { from, to ->
            when (from to to) {
                "ENH" to "UAH", "HKN" to "HAN" -> listOf(SeatInfo("二等座", "有"))
                else -> sold
            }
        }
        val result = finder(service, maxSwapQueries = 20).find(request)
        assertTrue(result.tickets.none { it.kind == RideKind.SECOND_SEAT })
    }

    @Test
    fun secondSeatFallsBackToAnotherFirstTicketThatConnects() = runBlocking {
        // 最优的第一张票买到汉口，但汉口→宜昌东没票；买到六安的那张后面能接上
        val service = swapService { from, to ->
            when (from to to) {
                "ENH" to "HKN", "ENH" to "UAH", "UAH" to "HAN" -> listOf(SeatInfo("二等座", "有"))
                else -> sold
            }
        }
        val result = finder(service, maxSwapQueries = 20).find(request)

        val second = result.tickets.single { it.kind == RideKind.SECOND_SEAT }
        assertEquals("六安", second.fromName)
        assertEquals("六安", second.afterName)
    }

    @Test
    fun ticketsThatAlreadyReachTheDestinationAreNotFirstTickets() = runBlocking {
        // 南京南→宜昌东（提前买、坐到终点）也有票，它不需要第二张票，不能拿它的终点去查「宜昌东→宜昌东」
        val service = swapService { from, to ->
            when (from to to) {
                "ENH" to "HKN", "NKH" to "HAN" -> listOf(SeatInfo("二等座", "有"))
                else -> sold
            }
        }
        finder(service, maxSwapQueries = 20).find(request)
        assertTrue(service.queryCalls.none { it.first == it.second })
    }

    @Test
    fun standingOnlyDoesNotCountAsASecondSeat() = runBlocking {
        val service = swapService { from, to ->
            when (from to to) {
                "ENH" to "HKN" -> listOf(SeatInfo("二等座", "3"))
                "HKN" to "HAN" -> listOf(SeatInfo("二等座", "无"), SeatInfo("无座", "有"))
                else -> sold
            }
        }
        val result = finder(service, maxSwapQueries = 20).find(request)
        assertTrue(result.tickets.none { it.kind == RideKind.SECOND_SEAT })
    }

    @Test
    fun noSwapSearchWhenTheBestTicketAlreadyReachesTheDestination() = runBlocking {
        val service = swapService { from, to ->
            if (from == "NKH" && to == "HAN") listOf(SeatInfo("二等座", "3")) else sold
        }
        val before = finder(service).find(request).queriesMade
        val service2 = swapService { from, to ->
            if (from == "NKH" && to == "HAN") listOf(SeatInfo("二等座", "3")) else sold
        }
        assertEquals(before, finder(service2, maxSwapQueries = 20).find(request).queriesMade)
    }

    @Test
    fun dayOffsetIsCountedFromTheBoardingDeparture() {
        fun raw(depart: String, duration: String) = RawTrain(
            "K1", "code", "A", "B", "X", "Y", depart, "00:00", duration, "01", "09", "", emptyList()
        )
        // 22:00 上车，全程 10 小时；换座站到终点还有 3 小时 → 上车 7 小时后（第二天 05:00）到换座站
        assertEquals(1L, TicketFinder.dayOffset(raw("22:00", "10:00"), raw("05:00", "03:00")))
        // 09:00 上车，4 小时后到换座站，还是当天
        assertEquals(0L, TicketFinder.dayOffset(raw("09:00", "06:00"), raw("13:00", "02:00")))
    }
}

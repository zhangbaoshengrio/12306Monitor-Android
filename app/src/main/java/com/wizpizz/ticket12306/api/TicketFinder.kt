package com.wizpizz.ticket12306.api

import com.wizpizz.ticket12306.model.FindResult
import com.wizpizz.ticket12306.model.QueryRequest
import com.wizpizz.ticket12306.model.RideKind
import com.wizpizz.ticket12306.model.Station
import com.wizpizz.ticket12306.model.StopInfo
import com.wizpizz.ticket12306.model.StationData
import com.wizpizz.ticket12306.model.TrainTicket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 查票核心。
 *
 * 你要坐的区间是「上车站→终点站」。直达票卖完时，只要一趟车经过这两个站，
 * 起点在上车站之前、或终点在终点站之后（甚至终点在终点站之前）的票，也能让你在上车站上车。
 * 做法：
 *   1. 查 上车站→终点站 的直达车次；
 *   2. 取每趟车的完整经停站表，按固定顺序列出票段：起点先是上车站、再是它之前的站（一站一站往前），
 *      每个起点下，终点从终点站开始、一站一站缩短到上车站的下一站（买短乘长）；
 *   3. 把所有车次里相同的票段合并，每个票段只查一次，从结果里挑出我们关心的车次；
 *   4. 每趟车按上面的顺序，第一个有票的票段标为最优。没票的票段也保留，界面折叠显示。
 *      价格不在这里查，界面需要时调用 loadPrices。
 *
 * @param maxBefore 往上车站之前最多再看几个站作为起点
 * @param maxSegmentQueries 最多额外查多少个票段；超出时先查顺序靠前的
 * @param pause     两次请求之间的间隔，避免请求太密
 */
class TicketFinder(
    private val service: TicketService,
    private val stationByName: (String) -> Station? = StationData::findByName,
    private val stationByCode: (String) -> Station? = StationData::findByCode,
    private val maxBefore: Int = 3,
    private val maxSegmentQueries: Int = 60,
    private val pause: suspend () -> Unit = { delay(Random.nextLong(250, 500)) }
) {

    private class Plan(val train: RawTrain, val stops: List<Station>, val boardIdx: Int, val destIdx: Int) {
        /** (起点序号, 终点序号) → 在这趟车的查询顺序里排第几；直达是 0。 */
        val ranks: Map<Pair<Int, Int>, Int> =
            planSegments(boardIdx, destIdx, Int.MAX_VALUE).withIndex().associate { it.value to it.index }
    }

    suspend fun find(request: QueryRequest, onLog: (String) -> Unit = {}): FindResult =
        withContext(Dispatchers.IO) {
            val date = request.date
            val board = request.board
            val dest = request.dest
            var queries = 0
            var consecutiveFailures = 0

            // 单个请求失败不致命，记一笔继续；连续失败太多就停下来。
            fun <T> guarded(what: String, block: () -> T): T? = try {
                block().also { consecutiveFailures = 0 }
            } catch (e: TicketApiException) {
                consecutiveFailures++
                onLog("$what 出错：${e.message}")
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    throw TicketApiException("连续 $MAX_CONSECUTIVE_FAILURES 次请求失败，已停止。最后一次：${e.message}")
                }
                null
            }

            // ── 1. 直达车次 ────────────────────────────────────────
            val directResp = service.queryTickets(board.code, dest.code, date)
            queries++
            val names = directResp.stationNames.toMutableMap()
            // 12306 会把同城邻近车站（比如宜昌东 ↔ 宜昌北）的车次一起返回，这里只留起终点完全一致的
            val direct = directResp.trains.filter { it.fromCode == board.code && it.toCode == dest.code }
            val ignored = directResp.trains.size - direct.size
            onLog(
                "${board.name} → ${dest.name}：直达 ${direct.size} 趟" +
                    (if (ignored > 0) "（另有 $ignored 趟到邻近车站，已忽略）" else "") +
                    " ${summary(direct)}"
            )
            if (direct.isEmpty()) return@withContext FindResult(emptyList(), 0, queries)

            val found = ArrayList<TrainTicket>()
            for (t in direct) {
                found += toTicket(t, RideKind.DIRECT, names, 0, 0, 0)
            }

            // ── 2. 每趟车的经停站表（直达有票的车不用再找，只处理直达没票的） ──
            val soldOut = if (request.searchAll) direct else direct.filter { t -> t.seats.none { it.available } }
            if (soldOut.size < direct.size) onLog("${direct.size - soldOut.size} 趟直达有票，不用再找")
            val plans = ArrayList<Plan>()
            for (t in soldOut) {
                pause()
                val stopNames = guarded("${t.trainNo} 经停站") { service.getStopNames(t, date) } ?: continue
                queries++
                val stops = stopNames.map { stationByName(it) ?: Station(it, "") }
                val bi = stops.indexOfFirst { it.name == board.name }
                val di = stops.indexOfFirst { it.name == dest.name }
                if (bi < 0 || di < 0 || bi >= di) {
                    onLog("${t.trainNo}：经停站里没有按顺序找到 ${board.name} 和 ${dest.name}，跳过")
                    continue
                }
                plans += Plan(t, stops, bi, di)
            }

            // ── 3. 合并票段：相同起终点只查一次 ────────────────────
            val candidates = LinkedHashMap<Pair<String, String>, MutableMap<String, Plan>>()
            val rank = HashMap<Pair<String, String>, Int>()   // 票段在查询顺序里的位置（多趟车取最靠前的）
            for (plan in plans) {
                for ((p, n) in planSegments(plan.boardIdx, plan.destIdx, maxBefore)) {
                    val a = plan.stops[p]
                    val b = plan.stops[n]
                    if (a.code.isEmpty() || b.code.isEmpty()) continue
                    if (a.code == board.code && b.code == dest.code) continue // 第 1 步已查
                    val key = a.code to b.code
                    candidates.getOrPut(key) { LinkedHashMap() }[plan.train.trainCode] = plan
                    rank.merge(key, plan.ranks.getValue(p to n), ::minOf)
                }
            }
            val ordered = candidates.entries.sortedBy { rank.getValue(it.key) }
            val wanted = ordered.take(maxSegmentQueries).associate { it.key to it.value }
            onLog(
                "共 ${plans.size} 趟车，共 ${candidates.size} 个可查的票段，" +
                    if (ordered.size > wanted.size) "先查顺序靠前的 ${wanted.size} 个，其余 ${ordered.size - wanted.size} 个略过"
                    else "全部查询"
            )

            var done = 0
            for ((pair, byTrain) in wanted) {
                done++
                pause()
                val resp = guarded("票段 ${pair.first}→${pair.second}") {
                    service.queryTickets(pair.first, pair.second, date)
                } ?: continue
                queries++
                names.putAll(resp.stationNames)
                val matched = resp.trains.filter {
                    it.trainCode in byTrain && it.fromCode == pair.first && it.toCode == pair.second
                }
                val fromName = names[pair.first] ?: stationByCode(pair.first)?.name ?: pair.first
                val toName = names[pair.second] ?: stationByCode(pair.second)?.name ?: pair.second
                onLog("[$done/${wanted.size}] $fromName → $toName：${if (matched.isEmpty()) "无这些车次" else summary(matched)}")
                for (rt in matched) {
                    val plan = byTrain.getValue(rt.trainCode)
                    val p = plan.stops.indexOfFirst { it.code == pair.first }
                    val n = plan.stops.indexOfFirst { it.code == pair.second }
                    found += toTicket(
                        rt, classify(p, n, plan.boardIdx, plan.destIdx), names,
                        order = plan.ranks.getValue(p to n),
                        earlierStops = plan.boardIdx - p,
                        extraStops = plan.destIdx - n
                    )
                }
            }

            // ── 4. 去重；按车次分组，每趟车内按查询顺序排；第一个有票的标为最优 ──
            val unique = found.distinctBy { "${it.trainCode}|${it.trainNo}|${it.fromName}|${it.toName}" }
            val bestOrder = unique.filter { it.hasSeats }.groupBy { it.trainCode }
                .mapValues { (_, list) -> list.minOf { it.order } }
            // 车次之间按上车站的发车时间排
            val groupDepart = unique.groupBy { it.trainCode }
                .mapValues { (_, list) -> (list.firstOrNull { it.kind == RideKind.DIRECT } ?: list.first()).departTime }
            val tickets = unique
                .map { it.copy(best = it.hasSeats && it.order == bestOrder[it.trainCode]) }
                .sortedWith(compareBy({ groupDepart.getValue(it.trainCode) }, { it.trainCode }, { it.order }))

            FindResult(tickets = tickets, directTrainCount = direct.size, queriesMade = queries)
        }

    /** 查这一张票各席别的价格。价格接口一次只能查一个票段，所以不在 find 里批量查，由界面按需调用。失败时原样返回。 */
    suspend fun loadPrices(ticket: TrainTicket, date: String): TrainTicket =
        withContext(Dispatchers.IO) {
            try {
                val prices = service.getPrices(
                    ticket.trainCode, ticket.fromStationNo, ticket.toStationNo, ticket.seatTypes, date
                )
                if (prices.isEmpty()) ticket else ticket.copy(prices = prices)
            } catch (e: TicketApiException) {
                ticket
            }
        }

    /** 取这趟车的经停站表（站名、到时、发时、停留）。失败返回空表。 */
    suspend fun loadStops(ticket: TrainTicket, date: String): List<StopInfo> =
        withContext(Dispatchers.IO) {
            try {
                service.getStops(ticket.trainCode, ticket.originCode, ticket.terminalCode, date)
            } catch (e: TicketApiException) {
                emptyList()
            }
        }

    private fun toTicket(
        t: RawTrain, kind: RideKind, names: Map<String, String>,
        order: Int, earlierStops: Int, extraStops: Int
    ) = TrainTicket(
        trainNo = t.trainNo,
        trainCode = t.trainCode,
        fromName = names[t.fromCode] ?: stationByCode(t.fromCode)?.name ?: t.fromCode,
        toName = names[t.toCode] ?: stationByCode(t.toCode)?.name ?: t.toCode,
        departTime = t.departTime,
        arriveTime = t.arriveTime,
        duration = t.duration,
        fromStationNo = t.fromStationNo,
        toStationNo = t.toStationNo,
        seatTypes = t.seatTypes,
        seats = t.seats,
        kind = kind,
        originCode = t.originCode,
        terminalCode = t.terminalCode,
        order = order,
        earlierStops = earlierStops,
        extraStops = extraStops
    )

    private fun summary(trains: List<RawTrain>): String =
        trains.joinToString("  ") { t ->
            val have = t.seats.filter { it.available }
            if (have.isEmpty()) "${t.trainNo} 无票"
            else "${t.trainNo} " + have.joinToString(" ") { "${it.label}${it.value}" }
        }

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 3

        /**
         * 一趟车要查的票段 (起点序号, 终点序号)，按优先顺序排好，序号是在这趟车经停站表里的位置。
         * 起点：上车站，然后是它之前的站一站一站往前（最多 maxBefore 个）；
         * 每个起点下，终点从终点站开始一站一站缩短，直到上车站的下一站。第一个就是直达。
         */
        fun planSegments(boardIdx: Int, destIdx: Int, maxBefore: Int): List<Pair<Int, Int>> {
            val starts = boardIdx downTo (boardIdx - maxBefore).coerceAtLeast(0)
            val ends = destIdx downTo (boardIdx + 1)
            return starts.flatMap { p -> ends.map { n -> p to n } }
        }

        /** 票段 (p→n) 相对于要坐的区间 (boardIdx→destIdx) 是哪一种。p 一定不在上车站之后。 */
        fun classify(p: Int, n: Int, boardIdx: Int, destIdx: Int): RideKind = when {
            p == boardIdx && n == destIdx -> RideKind.DIRECT
            n < destIdx -> RideKind.ENDS_EARLY
            else -> RideKind.COVERS
        }
    }
}

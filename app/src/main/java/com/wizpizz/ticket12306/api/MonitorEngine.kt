package com.wizpizz.ticket12306.api

import android.util.Log
import com.wizpizz.ticket12306.model.MonitorConfig
import com.wizpizz.ticket12306.model.TrainTicket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

private const val TAG = "MonitorEngine"

class MonitorEngine(private val config: MonitorConfig) {

    private val api = TicketApi(config.cookie)

    /**
     * 买短乘长查询：
     *   1. 合肥南→宜昌东 → 得到 G583 等车次
     *   2. 拿 G583 完整站表：...合肥→合肥南→六安→金寨→...→宜昌东
     *   3. 查所有能让你在「合肥南」上车的票段：
     *      - 合肥南→六安、合肥南→金寨、合肥南→宜昌东（直接在合肥南发起）
     *      - 合肥→六安、合肥→宜昌东（合肥南是中间站，同样能上车）
     */
    suspend fun queryOnce(onLog: (String) -> Unit = {}): List<TrainTicket> {
        val found = mutableListOf<TrainTicket>()

        fun desc(tickets: List<TrainTicket>, filterNo: String? = null): String {
            val t = if (filterNo != null) tickets.filter { it.trainNo == filterNo } else tickets
            if (t.isEmpty()) return "无车次"
            return t.joinToString("  ") { tk ->
                val s = if (tk.seats.isEmpty()) "无票"
                        else tk.seats.entries.joinToString(" ") { (k, v) -> "$k:$v" }
                "${tk.trainNo} $s"
            }
        }

        // ── Step 1: 直查 board→dest，找到途经这两站的车次 ──────────
        val directTickets = api.queryTickets(config.boardStation, config.destStation, config.date)
        onLog("${config.boardStation.name} → ${config.destStation.name}：${desc(directTickets)}")
        found.addAll(directTickets)

        val trainMeta = directTickets
            .filter { it.originCode.isNotEmpty() && it.terminalCode.isNotEmpty() }
            .distinctBy { it.trainNoInternal }

        // ── Step 2: 对每个车次，取完整站表，展开所有票段查询 ────────
        for (meta in trainMeta) {
            delay(Random.nextLong(400, 800))

            val stops = api.getStopList(
                meta.trainNoInternal, meta.originCode, meta.terminalCode, config.date
            )

            if (stops.isEmpty()) {
                onLog("${meta.trainNo}：获取经停站失败，跳过扩展查询")
                continue
            }

            val boardIdx = stops.indexOfFirst { it.name == config.boardStation.name }
            if (boardIdx < 0) {
                onLog("${meta.trainNo}：站表中未找到 ${config.boardStation.name}，跳过")
                continue
            }

            val prevStops = stops.subList(0, boardIdx)           // 合肥南前面的站
            val nextStops = stops.subList(boardIdx + 1, stops.size) // 合肥南后面的站

            if (prevStops.isNotEmpty()) {
                onLog("${meta.trainNo}：前序站 ${prevStops.joinToString("→") { it.name }}")
            }
            onLog("${meta.trainNo}：后续站 ${nextStops.joinToString("→") { it.name }}")

            // ── A: 合肥南 → 每个后续站（直接在合肥南上车）────────
            for (next in nextStops) {
                if (next.code == config.destStation.code) continue // 已在 Step1 查过
                delay(Random.nextLong(500, 1000))
                val tickets = api.queryTickets(config.boardStation, next, config.date)
                val matched = tickets.filter { it.trainNo == meta.trainNo }
                onLog("${config.boardStation.name} → ${next.name}：${desc(matched, meta.trainNo)}")
                found.addAll(matched)
            }

            // ── B: 每个前序站 → 每个后续站（合肥南作为中间站上车）──
            for (prev in prevStops) {
                for (next in nextStops) {
                    delay(Random.nextLong(500, 1000))
                    val tickets = api.queryTickets(prev, next, config.date)
                    val matched = tickets.filter { it.trainNo == meta.trainNo }
                    onLog("${prev.name} → ${next.name}：${desc(matched, meta.trainNo)}")
                    found.addAll(matched)
                }
            }
        }

        val deduped = found.distinctBy { "${it.trainNo}|${it.fromStation}|${it.toStation}" }

        // ── Step 3: 查价格 ───────────────────────────────────────────
        return deduped.map { ticket ->
            delay(Random.nextLong(200, 500))
            val prices = api.queryPrice(
                ticket.trainNoInternal, ticket.fromStationNo, ticket.toStationNo, config.date
            )
            if (prices.isNotEmpty()) ticket.copy(prices = prices) else ticket
        }
    }

    fun monitorFlow(): Flow<List<TrainTicket>> = flow {
        while (true) {
            emit(queryOnce())
            val wait = (config.intervalSeconds * 1000L) + Random.nextLong(0, 3000)
            Log.d(TAG, "waiting ${wait}ms before next poll")
            delay(wait)
        }
    }
}

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
     * 单次查询，onLog 每查一次回调一行日志显示在 UI 上
     */
    suspend fun queryOnce(onLog: (String) -> Unit = {}): List<TrainTicket> {
        val found = mutableListOf<TrainTicket>()

        fun resultDesc(tickets: List<TrainTicket>, filterTrainNo: String? = null): String {
            val list = if (filterTrainNo != null) tickets.filter { it.trainNo == filterTrainNo } else tickets
            if (list.isEmpty()) return "无车次"
            return list.joinToString("  ") { t ->
                val s = if (t.seats.isEmpty()) "无票" else t.seats.entries.joinToString(" ") { (k, v) -> "$k:$v" }
                "${t.trainNo} $s"
            }
        }

        // ── Step 1: 直查 board→dest ──────────────────────────────
        val directTickets = api.queryTickets(config.boardStation, config.destStation, config.date)
        onLog("${config.boardStation.name} → ${config.destStation.name}：${resultDesc(directTickets)}")
        found.addAll(directTickets)

        // ── Step 2: 对每个车次取完整经停站列表 ──────────────────
        val trainMeta = directTickets
            .filter { it.originCode.isNotEmpty() && it.terminalCode.isNotEmpty() }
            .distinctBy { it.trainNoInternal }

        for (meta in trainMeta) {
            if (meta.originCode == config.boardStation.code) {
                onLog("${meta.trainNo}：${config.boardStation.name} 是始发站，无前序站")
                continue
            }

            delay(Random.nextLong(500, 1000))

            val stops = api.getStopList(
                meta.trainNoInternal, meta.originCode, meta.terminalCode, config.date
            )
            if (stops.isEmpty()) {
                onLog("${meta.trainNo}：获取经停站失败")
                continue
            }

            val boardIdx = stops.indexOfFirst { it.name == config.boardStation.name }
            if (boardIdx <= 0) {
                onLog("${meta.trainNo}：经停站列表中未找到 ${config.boardStation.name}")
                continue
            }

            val prevStops = stops.subList(0, boardIdx)
            val nextStops = stops.subList(boardIdx + 1, stops.size)
            onLog("${meta.trainNo}：前序站 ${prevStops.joinToString("、") { it.name }}")

            val toStations = buildList {
                add(config.destStation)
                nextStops.take(2).forEach { add(it) }
            }.distinctBy { it.code }

            // ── Step 3: 查 前序站 → 后续站 ──────────────────────
            for (prev in prevStops) {
                for (to in toStations) {
                    delay(Random.nextLong(600, 1200))
                    val tickets = api.queryTickets(prev, to, config.date)
                    val matched = tickets.filter { it.trainNo == meta.trainNo }
                    onLog("${prev.name} → ${to.name}：${resultDesc(matched.ifEmpty { tickets }, meta.trainNo)}")
                    found.addAll(matched)
                }
            }
        }

        val deduped = found.distinctBy { "${it.trainNo}|${it.fromStation}|${it.toStation}" }

        // ── Step 4: 查价格 ───────────────────────────────────────
        return deduped.map { ticket ->
            delay(Random.nextLong(300, 700))
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

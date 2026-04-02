package com.wizpizz.ticket12306.api

import android.util.Log
import com.wizpizz.ticket12306.model.MonitorConfig
import com.wizpizz.ticket12306.model.Station
import com.wizpizz.ticket12306.model.TrainTicket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

private const val TAG = "MonitorEngine"

/**
 * 买短乘长查询逻辑：
 *
 * 用户输入：经过站 (board) + 终点站 (dest)
 *
 * 步骤：
 * 1. 查 board→dest，得到途经这两站的所有车次（G583 等）
 * 2. 对每个车次，用 czxx API 取完整经停站列表（含 board 前面的站）
 * 3. 若 board 不是该车次始发站，则 board 前面有站 → 查「前序站 → 后续站」的票
 *    只要 board 是中间站，买这张票就能在 board 上车
 * 4. 合并去重后返回
 */
class MonitorEngine(private val config: MonitorConfig) {

    private val api = TicketApi(config.cookie)

    /**
     * 单次查询，返回所有可供在 board 上车的票段
     */
    suspend fun queryOnce(): List<TrainTicket> {
        val found = mutableListOf<TrainTicket>()

        // ── Step 1: 直查 board→dest ────────────────────────────────
        val directTickets = api.queryTickets(config.boardStation, config.destStation, config.date)
        Log.d(TAG, "direct board→dest: ${directTickets.size} results")
        found.addAll(directTickets)

        // ── Step 2: 取每个车次的经停站列表 ─────────────────────────
        // 按 (trainNoInternal, originCode, terminalCode) 去重
        val trainMeta = directTickets
            .filter { it.originCode.isNotEmpty() && it.terminalCode.isNotEmpty() }
            .distinctBy { it.trainNoInternal }

        for (meta in trainMeta) {
            // 如果 board == 始发站，前面没有站，不需要再查
            if (meta.originCode == config.boardStation.code) {
                Log.d(TAG, "${meta.trainNo}: board is origin, skip prev-query")
                continue
            }

            delay(Random.nextLong(500, 1000))

            val stops = api.getStopList(
                meta.trainNoInternal,
                meta.originCode,
                meta.terminalCode,
                config.date
            )
            Log.d(TAG, "${meta.trainNo}: got ${stops.size} stops")
            if (stops.isEmpty()) continue

            val boardIdx = stops.indexOfFirst { it.name == config.boardStation.name }
            if (boardIdx <= 0) {
                Log.d(TAG, "${meta.trainNo}: board not found or is first stop in list")
                continue
            }

            val prevStops = stops.subList(0, boardIdx)      // board 前面的站
            val nextStops = stops.subList(boardIdx + 1, stops.size) // board 后面的站

            // ── Step 3: 查 前序站 → 后续站 ───────────────────────
            // 只查「能让你在 board 上车」的票段：
            //   FROM 在 board 之前（所以 board 是中间站，可上车）
            //   TO   在 board 之后（否则到了 board 就下车了，没用）
            //
            // 为控制请求数，对每个 prevStop 只查 dest 和紧邻 board 的 1~2 个站
            val toStations = buildList {
                add(config.destStation)                    // 最理想：一票到终点
                nextStops.take(2).forEach { add(it) }     // 备用：近一两站也能上车
            }.distinctBy { it.code }

            for (prev in prevStops) {
                for (to in toStations) {
                    delay(Random.nextLong(600, 1200))
                    val tickets = api.queryTickets(prev, to, config.date)
                    // 只保留这班车次的结果（避免混入其他车次）
                    found.addAll(tickets.filter { it.trainNo == meta.trainNo })
                    Log.d(TAG, "${meta.trainNo} ${prev.name}→${to.name}: ${tickets.count { it.trainNo == meta.trainNo }} tickets")
                }
            }
        }

        val deduped = found.distinctBy { "${it.trainNo}|${it.fromStation}|${it.toStation}" }

        // ── Step 4: 批量查价格 ────────────────────────────────────
        return deduped.map { ticket ->
            delay(Random.nextLong(300, 700))
            val prices = api.queryPrice(
                ticket.trainNoInternal,
                ticket.fromStationNo,
                ticket.toStationNo,
                config.date
            )
            if (prices.isNotEmpty()) ticket.copy(prices = prices) else ticket
        }
    }

    /**
     * 持续轮询的 Flow
     */
    fun monitorFlow(): Flow<List<TrainTicket>> = flow {
        while (true) {
            emit(queryOnce())
            val wait = (config.intervalSeconds * 1000L) + Random.nextLong(0, 3000)
            Log.d(TAG, "waiting ${wait}ms before next poll")
            delay(wait)
        }
    }
}

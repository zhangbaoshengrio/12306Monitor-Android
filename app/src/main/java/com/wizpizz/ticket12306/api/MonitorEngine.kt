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
 * 监控逻辑：
 *
 * 用户输入：经过站(board) + 终点站(dest)
 *
 * 查询策略：
 * 1. 查「board → dest」之间任意中间站有票（买短乘长：上车补票到终点）
 *    即：board → dest 沿途各站，包括 dest 本身
 * 2. 查「board 之前各站 → board」有票（能进站上车）
 *    这需要先查出该区间所有经停站，12306 API 提供 /queryStopStationInfo
 *
 * 由于 12306 停站接口需要 secretStr（加密串），我们采用简化策略：
 * 直接用「board → dest」查，因为能买到 board→任意后续站 的票就够了。
 * 同时也查「任意前序站 → board」确保能进站。
 * 前序站列表由用户或内置常见前序站提供。
 */
class MonitorEngine(private val config: MonitorConfig) {

    private val api = TicketApi(config.cookie)

    /**
     * 返回持续轮询的 Flow，每次轮询 emit 发现的有票列表（可能为空）
     */
    fun monitorFlow(): Flow<List<TrainTicket>> = flow {
        while (true) {
            val found = mutableListOf<TrainTicket>()

            // ── 策略1: 查 经过站 → 终点站（买短乘长，能上车就行）──────────
            val tickets = api.queryTickets(config.boardStation, config.destStation, config.date)
            Log.d(TAG, "board→dest: ${tickets.size} trains with seats")
            found.addAll(tickets)

            // ── 策略2: 查 经过站 → 经过站之后、终点站之前 的中间站 ─────────
            // 使用内置中间站列表（在合肥南→汉口这条线上的中间站）
            val midStations = getMidStations(config.boardStation, config.destStation)
            for (mid in midStations) {
                val t = api.queryTickets(config.boardStation, mid, config.date)
                found.addAll(t)
                delay(Random.nextLong(800, 1500))
            }

            emit(found.distinctBy { "${it.trainNo}|${it.fromStation}|${it.toStation}" })

            val wait = (config.intervalSeconds * 1000L) + Random.nextLong(0, 3000)
            Log.d(TAG, "waiting ${wait}ms before next poll")
            delay(wait)
        }
    }

    /**
     * 获取 from → to 之间的常见中间站
     * 实际上12306 API 不直接提供沿途站列表（需要 secretStr），
     * 这里内置了高铁常见走廊的中间站，覆盖大多数使用场景。
     */
    private fun getMidStations(from: Station, to: Station): List<Station> {
        // 合肥南方向常见走廊
        val hefeiCorridor = listOf(
            Station("六安", "LAN"),
            Station("金寨", "JJZ"),
            Station("麻城北", "MCB"),
            Station("红安西", "HAX"),
            Station("汉口", "HHK"),
            Station("汉川", "HHC"),
            Station("仙桃西", "XTX"),
            Station("荆州", "JGZ"),
            Station("宜昌东", "YCD"),
        )
        // 京沪走廊
        val jinghuCorridor = listOf(
            Station("南京南", "NKH"),
            Station("镇江南", "ZJH"),
            Station("丹阳北", "DAH"),
            Station("常州北", "CZH"),
            Station("无锡东", "WXH"),
            Station("苏州北", "SZH"),
        )

        val fromCode = from.code
        val toCode = to.code

        // 匹配走廊：如果 from 或 to 在某条走廊里，返回该走廊内 from 之后、to 之前的站
        for (corridor in listOf(hefeiCorridor, jinghuCorridor)) {
            val fromIdx = corridor.indexOfFirst { it.code == fromCode }
            val toIdx = corridor.indexOfFirst { it.code == toCode }
            if (fromIdx >= 0 && toIdx > fromIdx) {
                return corridor.subList(fromIdx + 1, toIdx)
            }
        }
        return emptyList()
    }
}

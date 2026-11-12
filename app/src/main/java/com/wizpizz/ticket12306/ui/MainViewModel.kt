package com.wizpizz.ticket12306.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wizpizz.ticket12306.api.TicketApi
import com.wizpizz.ticket12306.api.TicketApiException
import com.wizpizz.ticket12306.api.TicketFinder
import com.wizpizz.ticket12306.model.QueryRequest
import com.wizpizz.ticket12306.model.Station
import com.wizpizz.ticket12306.model.StopInfo
import com.wizpizz.ticket12306.model.StationData
import com.wizpizz.ticket12306.model.TrainTicket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ── Form state ──────────────────────────────────────────────
    val boardStationInput = MutableStateFlow("")
    val destStationInput = MutableStateFlow("")
    val dateInput = MutableStateFlow(LocalDate.now().toString())

    val boardStationSelected = MutableStateFlow<Station?>(null)
    val destStationSelected = MutableStateFlow<Station?>(null)

    // ── Query state ─────────────────────────────────────────────
    val errorMsg = MutableStateFlow<String?>(null)
    val isQuerying = MutableStateFlow(false)
    val queryResult = MutableStateFlow<List<TrainTicket>?>(null)
    val queryLog = MutableStateFlow<List<String>>(emptyList())
    val showResults = MutableStateFlow(false)
    /** 直达有票也继续找买短乘长的票。先默认开，方便查看。 */
    val searchAll = MutableStateFlow(true)
    val loadingPrices = MutableStateFlow<Set<String>>(emptySet())
    /** 车次内部编号 → 经停站表。点开车次时才查，查过就缓存。 */
    val stops = MutableStateFlow<Map<String, List<StopInfo>>>(emptyMap())
    val loadingStops = MutableStateFlow<Set<String>>(emptySet())

    /** 选站页的「常用车站」：最近选过的，最多 9 个，存在本机。 */
    val recentStations = MutableStateFlow<List<Station>>(emptyList())
    private val prefs = app.getSharedPreferences("stations", android.content.Context.MODE_PRIVATE)

    private val finder = TicketFinder(TicketApi())

    init {
        if (!StationData.isLoaded) {
            app.assets.open("station_name.js").use { StationData.load(it) }
        }
        recentStations.value = prefs.getString("recent", "").orEmpty().split(",")
            .mapNotNull { StationData.findByCode(it) }
    }

    fun addRecent(station: Station) {
        val list = (listOf(station) + recentStations.value.filter { it.code != station.code }).take(9)
        recentStations.value = list
        prefs.edit().putString("recent", list.joinToString(",") { it.code }).apply()
    }

    fun swapStations() {
        val b = boardStationSelected.value
        boardStationSelected.value = destStationSelected.value
        destStationSelected.value = b
        val t = boardStationInput.value
        boardStationInput.value = destStationInput.value
        destStationInput.value = t
    }

    fun setDateOffset(days: Long) { dateInput.value = LocalDate.now().plusDays(days).toString() }

    /** 结果页的前一天/后一天。不会早于今天。 */
    fun shiftDate(days: Long) {
        val d = runCatching { LocalDate.parse(dateInput.value.trim()) }.getOrNull() ?: LocalDate.now()
        val next = d.plusDays(days)
        if (next.isBefore(LocalDate.now())) return
        dateInput.value = next.toString()
        queryOnce()
    }

    fun backToSearch() { showResults.value = false }

    /** 展开一趟车时查它的经停站表。 */
    fun loadStops(ticket: TrainTicket) {
        val code = ticket.trainCode
        if (code in stops.value || code in loadingStops.value) return
        viewModelScope.launch {
            loadingStops.value = loadingStops.value + code
            val list = finder.loadStops(ticket, dateInput.value.trim())
            if (list.isNotEmpty()) stops.value = stops.value + (code to list)
            loadingStops.value = loadingStops.value - code
        }
    }

    fun key(t: TrainTicket) = "${t.trainCode}|${t.trainNo}|${t.fromName}|${t.toName}"

    /** 每趟车的最优票段，查完就顺手把价格查了（一趟一个请求），其余的点开再查。 */
    private suspend fun loadBestPrices(date: String) {
        val targets = queryResult.value?.filter { it.best && it.prices.isEmpty() } ?: return
        for (t in targets) {
            val k = key(t)
            loadingPrices.value = loadingPrices.value + k
            val priced = finder.loadPrices(t, date)
            queryResult.value = queryResult.value?.map { if (key(it) == k) priced else it }
            loadingPrices.value = loadingPrices.value - k
            kotlinx.coroutines.delay(250)
        }
    }

    /** 点开一张票时再查它的价格。 */
    fun loadPrices(ticket: TrainTicket) {
        val k = key(ticket)
        if (ticket.prices.isNotEmpty() || k in loadingPrices.value) return
        viewModelScope.launch {
            loadingPrices.value = loadingPrices.value + k
            val priced = finder.loadPrices(ticket, dateInput.value.trim())
            queryResult.value = queryResult.value?.map { if (key(it) == k) priced else it }
            loadingPrices.value = loadingPrices.value - k
        }
    }

    fun queryOnce() {
        val board = boardStationSelected.value
        val dest = destStationSelected.value
        val date = dateInput.value.trim()

        if (board == null) { errorMsg.value = "请选择上车站"; return }
        if (dest == null) { errorMsg.value = "请选择终点站"; return }
        if (date.isEmpty()) { errorMsg.value = "请填写日期"; return }

        showResults.value = true
        viewModelScope.launch {
            isQuerying.value = true
            errorMsg.value = null
            queryLog.value = emptyList()
            queryResult.value = null
            stops.value = emptyMap()
            try {
                val result = finder.find(QueryRequest(date, board, dest, searchAll.value)) { line ->
                    queryLog.value = queryLog.value + line
                }
                queryResult.value = result.tickets
                loadBestPrices(date)
            } catch (e: TicketApiException) {
                errorMsg.value = e.message
            } catch (e: Exception) {
                errorMsg.value = "查询失败: ${e.message}"
            } finally {
                isQuerying.value = false
            }
        }
    }
}

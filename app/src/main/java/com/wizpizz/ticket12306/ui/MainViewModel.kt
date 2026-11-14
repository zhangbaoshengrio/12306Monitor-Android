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
    /** 自动查票价还没跑完（这期间还没有价格的票都显示「查询中」）。 */
    val pricesPending = MutableStateFlow(false)
    private var priceJob: kotlinx.coroutines.Job? = null
    /** 车次内部编号 → 经停站表。点开车次时才查，查过就缓存。 */
    val stops = MutableStateFlow<Map<String, List<StopInfo>>>(emptyMap())
    val loadingStops = MutableStateFlow<Set<String>>(emptySet())

    /** 选站页的「常用车站」：最近选过的，最多 9 个，存在本机。 */
    val recentStations = MutableStateFlow<List<Station>>(emptyList())
    private val prefs = app.getSharedPreferences("stations", android.content.Context.MODE_PRIVATE)

    private val finder = TicketFinder(TicketApi())

    // 查票过程自动写文件，不用开关；每次查询从头覆盖，方便事后用 adb 拉出来看
    private val logFile = java.io.File(app.filesDir, "query.log")
    private fun log(line: String) {
        runCatching { logFile.appendText("${System.currentTimeMillis() / 1000} $line\n") }
    }

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

    /**
     * 查完票就把所有有票的票段的价格都查出来（一张票一个请求，一个接一个地查，最优的先查）。
     * 没查到的隔一会儿再试一轮；还是没有的，界面上可以点一下重试。
     */
    private suspend fun loadAllPrices(date: String) {
        pricesPending.value = true
        try {
            repeat(2) { round ->
                val targets = queryResult.value?.filter { it.hasSeats && it.prices.isEmpty() }
                    ?.sortedByDescending { it.best }.orEmpty()
                if (targets.isEmpty()) return
                if (round > 0) kotlinx.coroutines.delay(2000)
                for (t in targets) {
                    val k = key(t)
                    loadingPrices.value = loadingPrices.value + k
                    val priced = finder.loadPrices(t, date)
                    queryResult.value = queryResult.value?.map { if (key(it) == k) priced else it }
                    loadingPrices.value = loadingPrices.value - k
                    kotlinx.coroutines.delay(250)
                }
            }
        } finally {
            pricesPending.value = false
        }
    }

    /** 自动查没查到价格时，点一下这张票再查一次。 */
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
        priceJob?.cancel()
        loadingPrices.value = emptySet()
        runCatching { logFile.writeText("") }
        log("query ${board.name} -> ${dest.name} date=$date searchAll=${searchAll.value}")
        viewModelScope.launch {
            isQuerying.value = true
            errorMsg.value = null
            queryLog.value = emptyList()
            queryResult.value = null
            stops.value = emptyMap()
            try {
                val result = finder.find(QueryRequest(date, board, dest, searchAll.value)) { line ->
                    queryLog.value = queryLog.value + line
                    log(line)
                }
                queryResult.value = result.tickets
                log("result: ${result.tickets.size} tickets, direct=${result.directTrainCount}, queries=${result.queriesMade}")
                priceJob = viewModelScope.launch { loadAllPrices(date) }
            } catch (e: TicketApiException) {
                errorMsg.value = e.message
                log("error: ${e.message}")
            } catch (e: Exception) {
                errorMsg.value = "查询失败: ${e.message}"
                log("error: ${e.message}")
            } finally {
                isQuerying.value = false
            }
        }
    }
}

package com.wizpizz.ticket12306.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.wizpizz.ticket12306.model.MonitorConfig
import com.wizpizz.ticket12306.model.Station
import com.wizpizz.ticket12306.model.TrainTicket
import com.wizpizz.ticket12306.service.MonitorService
import com.wizpizz.ticket12306.service.ACTION_START
import com.wizpizz.ticket12306.service.ACTION_STOP
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ── Form state ──────────────────────────────────────────────
    val boardStationInput = MutableStateFlow("")
    val destStationInput = MutableStateFlow("")
    val dateInput = MutableStateFlow(
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    )
    val cookieInput = MutableStateFlow("")
    val intervalInput = MutableStateFlow("8")

    val boardStationSelected = MutableStateFlow<Station?>(null)
    val destStationSelected = MutableStateFlow<Station?>(null)

    // ── Monitor state ───────────────────────────────────────────
    val isRunning: StateFlow<Boolean> get() = _isRunning
    private val _isRunning = MutableStateFlow(MonitorService.isRunning)

    val foundTickets: StateFlow<List<TrainTicket>> get() = _foundTickets
    private val _foundTickets = MutableStateFlow(MonitorService.lastFoundTickets)

    val errorMsg = MutableStateFlow<String?>(null)

    init {
        MonitorService.onStatusChanged = { running -> _isRunning.value = running }
        MonitorService.onTicketsFound = { tickets -> _foundTickets.value = tickets }
    }

    fun startMonitor() {
        val board = boardStationSelected.value
        val dest = destStationSelected.value
        val cookie = cookieInput.value.trim()
        val date = dateInput.value.trim()
        val interval = intervalInput.value.toIntOrNull() ?: 8

        if (board == null) { errorMsg.value = "请选择经过站"; return }
        if (dest == null) { errorMsg.value = "请选择终点站"; return }
        if (cookie.isEmpty()) { errorMsg.value = "请填写 Cookie"; return }
        if (date.isEmpty()) { errorMsg.value = "请填写日期"; return }

        val config = MonitorConfig(
            date = date,
            boardStation = board,
            destStation = dest,
            cookie = cookie,
            intervalSeconds = interval
        )
        MonitorService.currentConfig = config
        _foundTickets.value = emptyList()
        errorMsg.value = null

        val intent = Intent(getApplication(), MonitorService::class.java).apply {
            action = ACTION_START
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun stopMonitor() {
        val intent = Intent(getApplication(), MonitorService::class.java).apply {
            action = ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    override fun onCleared() {
        super.onCleared()
        MonitorService.onStatusChanged = null
        MonitorService.onTicketsFound = null
    }
}

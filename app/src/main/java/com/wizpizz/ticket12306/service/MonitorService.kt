package com.wizpizz.ticket12306.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wizpizz.ticket12306.MainActivity
import com.wizpizz.ticket12306.R
import com.wizpizz.ticket12306.api.MonitorEngine
import com.wizpizz.ticket12306.model.MonitorConfig
import com.wizpizz.ticket12306.model.TrainTicket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private const val TAG = "MonitorService"
const val CHANNEL_MONITOR = "monitor_running"
const val CHANNEL_FOUND = "ticket_found"
const val NOTIF_ID_RUNNING = 1001
const val NOTIF_ID_FOUND = 1002

const val ACTION_START = "com.wizpizz.ticket12306.START"
const val ACTION_STOP = "com.wizpizz.ticket12306.STOP"
const val EXTRA_CONFIG = "config_json"

class MonitorService : LifecycleService() {

    private var monitorJob: Job? = null

    companion object {
        var currentConfig: MonitorConfig? = null
        var isRunning = false
        var lastFoundTickets: List<TrainTicket> = emptyList()

        // Callbacks to notify UI
        var onTicketsFound: ((List<TrainTicket>) -> Unit)? = null
        var onStatusChanged: ((Boolean) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val config = currentConfig ?: return START_NOT_STICKY
                startMonitor(config)
            }
            ACTION_STOP -> stopMonitor()
        }
        return START_STICKY
    }

    private fun startMonitor(config: MonitorConfig) {
        if (isRunning) return
        isRunning = true
        onStatusChanged?.invoke(true)

        startForeground(NOTIF_ID_RUNNING, buildRunningNotification(config))

        val engine = MonitorEngine(config)
        monitorJob = lifecycleScope.launch(Dispatchers.IO) {
            engine.monitorFlow()
                .catch { e -> Log.e(TAG, "monitor error: ${e.message}") }
                .collect { tickets ->
                    if (tickets.isNotEmpty()) {
                        lastFoundTickets = tickets
                        Log.i(TAG, "FOUND ${tickets.size} tickets!")
                        showFoundNotification(tickets)
                        vibrate()
                        onTicketsFound?.invoke(tickets)
                    }
                }
        }
        Log.d(TAG, "Monitor started for ${config.boardStation.name}→${config.destStation.name}")
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        isRunning = false
        onStatusChanged?.invoke(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Monitor stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        onStatusChanged?.invoke(false)
    }

    // ── Notifications ──────────────────────────────────────────

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "监控运行中", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "余票监控后台运行通知" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FOUND, "发现余票", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "发现余票时的提醒通知"
                    enableVibration(true)
                }
        )
    }

    private fun buildRunningNotification(config: MonitorConfig): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle("余票监控运行中")
            .setContentText("${config.boardStation.name} → ${config.destStation.name}  ${config.date}")
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun showFoundNotification(tickets: List<TrainTicket>) {
        val first = tickets.first()
        val detail = tickets.take(3).joinToString("\n") {
            "${it.trainNo} ${it.fromStation}→${it.toStation} ${it.departTime}  " +
            it.seats.entries.take(2).joinToString(" ") { (k, v) -> "$k:$v" }
        }
        // 点通知主体 → 打开本 APP
        val openAppIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // "去购票" 按钮 → 尝试打开 12306 APP，失败则打开网页
        val buy12306Intent = PendingIntent.getActivity(
            this, 2,
            open12306Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_FOUND)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle("★ 发现余票！共 ${tickets.size} 个区间")
            .setContentText("${first.trainNo} ${first.fromStation}→${first.toStation} ${first.departTime}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent)
            .addAction(0, "去 12306 购票 →", buy12306Intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID_FOUND, notif)
    }

    /** 优先打开 12306 APP，若未安装则跳转到网页购票 */
    private fun open12306Intent(): Intent {
        val pm = packageManager
        val appPackage = "com.MobileTicket"
        return try {
            pm.getLaunchIntentForPackage(appPackage)
                ?: throw PackageManager.NameNotFoundException()
        } catch (e: Exception) {
            // 未安装 APP，打开手机端网页
            Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://kyfw.12306.cn/otn/leftTicket/init"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed: ${e.message}")
        }
    }
}

package com.wizpizz.ticket12306.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 「去12306」的执行体。要用前台服务：12306 盖住我们之后，普通后台进程会被系统冻结，脚本就停了。 */
class FillService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fill", "自动填写 12306", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "fill")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("正在帮你打开 12306 的确认订单页")
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        val i = intent ?: return START_NOT_STICKY.also { stopSelf() }
        scope.launch {
            try {
                Auto12306.run(
                    applicationContext,
                    i.getStringExtra("from").orEmpty(), i.getStringExtra("to").orEmpty(),
                    i.getStringExtra("date").orEmpty(), i.getStringExtra("train").orEmpty(),
                    i.getStringExtra("seat").orEmpty(), i.getStringExtra("via").orEmpty()
                )
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

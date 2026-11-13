package com.wizpizz.ticket12306.auto

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.wizpizz.ticket12306.model.StationData
import com.wizpizz.ticket12306.model.TrainTicket
import java.time.LocalDate

/** 「去12306」：打开 12306，用 root 把这张票一路点到「确认订单」页，选乘车人和提交订单由用户自己来。 */
object Auto12306 {
    @Volatile private var busy = false

    @Volatile private var logFile: java.io.File? = null

    private fun trace(line: String) {
        android.util.Log.d("Auto12306", line)
        runCatching { logFile?.appendText("${java.time.LocalTime.now()} $line\n") }
    }

    private fun rootShell(cmd: String): String = try {
        trace("exec ${cmd.take(60)}")
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        trace("$cmd => ${out.take(300).replace('\n', ' ')}")
        out
    } catch (e: java.io.IOException) {
        throw Fill12306.Failure("没有 root 权限（需要 Magisk 授权本 App）")
    }

    /** 在我们自己还在前台时调用：先起前台服务（否则 12306 盖住后，系统会冻结我们的进程），再打开 12306。 */
    private val SEAT_ORDER = listOf("二等座", "硬座", "硬卧", "一等座", "软卧", "动卧", "商务座", "特等座")

    /** 没指定席别时选哪个：有票的里面按常见程度排，无座放最后。 */
    fun defaultSeat(t: TrainTicket): String? {
        val open = t.seats.filter { it.available }.map { it.label }
        return SEAT_ORDER.firstOrNull { it in open } ?: open.firstOrNull { it != "无座" } ?: open.firstOrNull()
    }

    /** @param second 换座的一组票里的第二张；有它时两张票走 12306「中转」里的车内换座方案，放进同一个订单 */
    fun start(context: Context, t: TrainTicket, date: String, seat: String? = defaultSeat(t), second: TrainTicket? = null) {
        if (busy) return toast(context, "正在操作 12306，请稍等")
        val app = context.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(Fill12306.APP)
            ?: return toast(app, "没有安装 12306 App")
        val toName = second?.toName ?: t.toName
        if (seat == null) return toast(app, "这张票没有余票")
        if (StationData.findByName(t.fromName) == null) return toast(app, "找不到车站 ${t.fromName}")
        if (StationData.findByName(toName) == null) return toast(app, "找不到车站 $toName")
        busy = true
        app.startForegroundService(
            Intent(app, FillService::class.java)
                .putExtra("from", t.fromName).putExtra("to", toName)
                .putExtra("date", date).putExtra("train", t.trainNo)
                .putExtra("seat", seat).putExtra("via", second?.fromName.orEmpty())
        )
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(launch)
    }

    /** 在前台服务里跑，结束后清掉 busy。 */
    suspend fun run(app: Context, fromName: String, toName: String, date: String, trainNo: String, seat: String, via: String) {
        logFile = java.io.File(app.filesDir, "auto.log").also { it.writeText("") }
        val msg = try {
            val day = LocalDate.parse(date.trim())
            Fill12306(::rootShell, onStatus = { trace("status: $it"); toast(app, it) })
                .run(
                    StationData.findByName(fromName)!!, StationData.findByName(toName)!!,
                    "${day.monthValue}月${day.dayOfMonth}日", trainNo, seat, via
                )
        } catch (e: Fill12306.Failure) {
            trace("failure: ${e.message}")
            e.message ?: "没有完成"
        } catch (e: Throwable) {
            trace("error: $e")
            "出错了：${e.message}"
        } finally {
            busy = false
        }
        trace("done: $msg")
        toast(app, msg)
    }

    private fun toast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
}

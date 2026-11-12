package com.wizpizz.ticket12306.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wizpizz.ticket12306.model.StopInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val BASE = "https://kyfw.12306.cn/otn"
private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/**
 * 12306 查票接口的客户端。查票不需要登录：先打开一次查询页拿到匿名会话 cookie，再发请求即可。
 *
 * 余票查询的接口名会变（现在是 queryG）。接口名过期时 12306 不返回车次，而是在 c_url 里告诉我们新名字，
 * 这里看到 c_url 就换成新名字重试。
 */
class TicketApi(client: OkHttpClient? = null) : TicketService {

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var queryName = "queryG"
    @Volatile private var sessionReady = false

    override fun queryTickets(fromCode: String, toCode: String, date: String): QueryResponse {
        var problem = "未知错误"
        repeat(MAX_ATTEMPTS) {
            ensureSession()
            val (code, body) = get(
                "$BASE/leftTicket/$queryName" +
                    "?leftTicketDTO.train_date=$date" +
                    "&leftTicketDTO.from_station=$fromCode" +
                    "&leftTicketDTO.to_station=$toCode" +
                    "&purpose_codes=ADULT"
            )
            val root = parseObject(body)
            if (root == null) {
                // 返回的不是 JSON（多半是会话失效后的跳转页）：重新拿会话再试
                sessionReady = false
                problem = "HTTP $code，返回的不是 JSON"
                return@repeat
            }
            if (root.get("status")?.asBoolean == true) return ResultParser.parseQuery(root)

            val newName = root.get("c_url")?.asString?.substringAfterLast('/')
            if (newName != null && newName.matches(NAME_RE) && newName != queryName) {
                queryName = newName
                problem = "接口名已更新为 $newName"
                return@repeat
            }
            // 12306 给出了明确的错误信息（比如系统繁忙）：重试没有用，直接报出来
            throw TicketApiException("余票查询失败：" + ResultParser.messageOf(root).ifEmpty { "接口返回 status=false" })
        }
        throw TicketApiException("余票查询失败：$problem")
    }

    override fun getStopNames(train: RawTrain, date: String): List<String> =
        getStops(train.trainCode, train.originCode, train.terminalCode, date).map { it.name }

    override fun getStops(trainCode: String, originCode: String, terminalCode: String, date: String): List<StopInfo> {
        val root = getJson(
            "$BASE/czxx/queryByTrainNo" +
                "?train_no=$trainCode" +
                "&from_station_telecode=$originCode" +
                "&to_station_telecode=$terminalCode" +
                "&depart_date=$date",
            "经停站查询"
        )
        return ResultParser.parseStops(root)
    }

    override fun getPrices(
        trainCode: String, fromStationNo: String, toStationNo: String, seatTypes: String, date: String
    ): Map<String, String> {
        val root = getJson(
            "$BASE/leftTicket/queryTicketPrice" +
                "?train_no=$trainCode" +
                "&from_station_no=$fromStationNo" +
                "&to_station_no=$toStationNo" +
                "&seat_types=$seatTypes" +
                "&train_date=$date",
            "票价查询"
        )
        return ResultParser.parsePrices(root)
    }

    @Synchronized
    private fun ensureSession() {
        if (sessionReady) return
        get("$BASE/leftTicket/init")
        sessionReady = true
    }

    private fun getJson(url: String, what: String): JsonObject {
        ensureSession()
        val (code, body) = get(url)
        val root = parseObject(body)
            ?: run { sessionReady = false; throw TicketApiException("$what 失败：HTTP $code，返回的不是 JSON") }
        if (root.get("status")?.asBoolean != true) {
            throw TicketApiException("$what 失败：" + ResultParser.messageOf(root).ifEmpty { "status=false" })
        }
        return root
    }

    /** 返回 (HTTP 状态码, 响应体)。12306 会用 302 状态码返回 JSON 提示，所以不论状态码都把响应体交出去。 */
    private fun get(url: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UA)
            .header("Referer", "$BASE/leftTicket/init")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        try {
            http.newCall(request).execute().use { r ->
                return r.code to (r.body?.string() ?: "")
            }
        } catch (e: java.io.IOException) {
            throw TicketApiException("网络错误：${e.message}")
        }
    }

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val NAME_RE = Regex("^[A-Za-z0-9]{1,20}$")
    }
}

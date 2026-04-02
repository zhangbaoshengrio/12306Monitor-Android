package com.wizpizz.ticket12306.api

import android.util.Log
import com.google.gson.JsonParser
import com.wizpizz.ticket12306.model.Station
import com.wizpizz.ticket12306.model.StationData
import com.wizpizz.ticket12306.model.TrainTicket
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "TicketApi"

// 余票查询接口
private const val QUERY_URL = "https://kyfw.12306.cn/otn/leftTicket/query"
// 经停站查询接口
private const val STOP_URL = "https://kyfw.12306.cn/otn/czxx/queryByTrainNo"
// 票价查询接口
private const val PRICE_URL = "https://kyfw.12306.cn/otn/leftTicket/queryTicketPrice"

// result 字符串 | 分割后各字段的位置
private const val IDX_TRAIN_NO_INTERNAL = 2  // 内部编号，如 5l000G58300
private const val IDX_TRAIN_NO = 3           // 展示车次，如 G583
private const val IDX_FROM_STATION_NAME = 6
private const val IDX_TO_STATION_NAME = 7
private const val IDX_DEPART_TIME = 8
private const val IDX_ARRIVE_TIME = 9
private const val IDX_FROM_STATION_NO = 10   // 出发站在全程中的序号（价格查询用）
private const val IDX_TO_STATION_NO = 11     // 到达站在全程中的序号（价格查询用）
private const val IDX_ORIGIN_CODE = 15       // 本次列车实际始发站电报码
private const val IDX_TERMINAL_CODE = 16     // 本次列车实际终点站电报码
private const val IDX_SWZ = 32   // 商务/特等座
private const val IDX_YDZ = 31   // 一等座
private const val IDX_EDZ = 30   // 二等座
private const val IDX_YW = 28    // 硬卧
private const val IDX_RW = 23    // 软卧
private const val IDX_YZ = 29    // 硬座
private const val IDX_WZ = 26    // 无座

class TicketApi(private val cookie: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 查询从 from 到 to 的列车余票列表
     */
    fun queryTickets(from: Station, to: Station, date: String): List<TrainTicket> {
        val url = "$QUERY_URL" +
            "?leftTicketDTO.train_date=$date" +
            "&leftTicketDTO.from_station=${from.code}" +
            "&leftTicketDTO.to_station=${to.code}" +
            "&purpose_codes=ADULT"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "https://kyfw.12306.cn/otn/leftTicket/init")
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cookie", cookie)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            Log.d(TAG, "query ${from.name}→${to.name}: HTTP ${response.code}")
            if (!response.isSuccessful) return emptyList()
            parseResult(body)
        } catch (e: Exception) {
            Log.e(TAG, "query failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取车次完整经停站列表（用于找合肥南前面的站）
     * originCode/terminalCode 为该次车实际始发/终到站电报码
     */
    fun getStopList(
        trainNoInternal: String,
        originCode: String,
        terminalCode: String,
        date: String
    ): List<Station> {
        val url = "$STOP_URL" +
            "?train_no=${trainNoInternal}" +
            "&from_station_telecode=${originCode}" +
            "&to_station_telecode=${terminalCode}" +
            "&depart_date=$date"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "https://kyfw.12306.cn/otn/leftTicket/init")
            .header("Accept", "*/*")
            .header("Cookie", cookie)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            if (!response.isSuccessful) return emptyList()
            val root = JsonParser.parseString(body).asJsonObject
            if (root.get("status")?.asBoolean != true) return emptyList()
            val data = root.getAsJsonObject("data")
                ?.getAsJsonArray("data") ?: return emptyList()

            data.mapNotNull { el ->
                val name = el.asJsonObject.get("station_name")?.asString?.trim()
                    ?: return@mapNotNull null
                // 用站名反查电报码
                StationData.findByName(name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getStopList failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 查询指定车次/区间的票价
     * seat_types 常用值：O=二等 M=一等 9=商务 3=硬卧 4=软卧 1=硬座
     */
    fun queryPrice(
        trainNoInternal: String,
        fromStationNo: String,
        toStationNo: String,
        date: String
    ): Map<String, String> {
        val url = "$PRICE_URL" +
            "?train_no=$trainNoInternal" +
            "&from_station_no=$fromStationNo" +
            "&to_station_no=$toStationNo" +
            "&seat_types=O9M953141" +
            "&train_date=$date"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "https://kyfw.12306.cn/otn/leftTicket/init")
            .header("Accept", "*/*")
            .header("Cookie", cookie)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyMap()
            if (!response.isSuccessful) return emptyMap()
            val root = JsonParser.parseString(body).asJsonObject
            if (root.get("status")?.asBoolean != true) return emptyMap()
            val data = root.getAsJsonObject("data") ?: return emptyMap()

            // 价格接口返回席别代码 → 价格字符串，映射为可读名称
            buildMap {
                fun tryPut(humanName: String, vararg keys: String) {
                    for (k in keys) {
                        val v = data.get(k)?.asString?.trim() ?: continue
                        if (v.isNotEmpty()) { put(humanName, v); return }
                    }
                }
                tryPut("商务/特等座", "9", "P", "0")
                tryPut("一等座", "M")
                tryPut("二等座", "O")
                tryPut("高级软卧", "6")
                tryPut("软卧", "4")
                tryPut("硬卧", "3")
                tryPut("硬座", "1")
                tryPut("无座", "WZ")
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryPrice failed: ${e.message}")
            emptyMap()
        }
    }

    private fun parseResult(json: String): List<TrainTicket> {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            if (root.get("status")?.asBoolean != true) return emptyList()
            val results = root.getAsJsonObject("data")
                ?.getAsJsonArray("result") ?: return emptyList()

            results.mapNotNull { el ->
                val fields = el.asString.split("|")
                if (fields.size < 35) return@mapNotNull null
                // 席别余量（空 = 无票，不过滤，全部展示）
                val seats = buildMap<String, String> {
                    fun add(key: String, idx: Int) {
                        val v = fields[idx].trim()
                        if (v.isNotEmpty() && v != "0") put(key, v)
                    }
                    add("商务/特等座", IDX_SWZ)
                    add("一等座", IDX_YDZ)
                    add("二等座", IDX_EDZ)
                    add("硬卧", IDX_YW)
                    add("软卧", IDX_RW)
                    add("硬座", IDX_YZ)
                    add("无座", IDX_WZ)
                }
                TrainTicket(
                    trainNo = fields[IDX_TRAIN_NO].trim(),
                    trainNoInternal = fields[IDX_TRAIN_NO_INTERNAL].trim(),
                    originCode = fields.getOrNull(IDX_ORIGIN_CODE)?.trim() ?: "",
                    terminalCode = fields.getOrNull(IDX_TERMINAL_CODE)?.trim() ?: "",
                    fromStation = fields[IDX_FROM_STATION_NAME].trim(),
                    toStation = fields[IDX_TO_STATION_NAME].trim(),
                    fromStationNo = fields.getOrNull(IDX_FROM_STATION_NO)?.trim() ?: "",
                    toStationNo = fields.getOrNull(IDX_TO_STATION_NO)?.trim() ?: "",
                    departTime = fields[IDX_DEPART_TIME].trim(),
                    arriveTime = fields[IDX_ARRIVE_TIME].trim(),
                    seats = seats
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${e.message}")
            emptyList()
        }
    }
}

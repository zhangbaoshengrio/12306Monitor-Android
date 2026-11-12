package com.wizpizz.ticket12306.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.wizpizz.ticket12306.model.SeatInfo
import com.wizpizz.ticket12306.model.StopInfo

/**
 * 解析 12306 返回的 JSON。
 *
 * 余票结果里每趟车是一个用 | 分隔的字符串，用到的字段位置：
 *   2 内部编号  3 车次  4 始发站码  5 终点站码  6 出发站码  7 到达站码
 *   8 出发时间  9 到达时间  10 历时  16 出发站序号  17 到达站序号
 *   35 席别代码串
 *   席别余量：21 高级软卧  23 软卧  24 软座  25 特等座  26 无座  28 硬卧
 *            29 硬座  30 二等座  31 一等座  32 商务座  33 动卧
 * 余量的值是 数字（张数）/ "有" / "无"，空字符串表示这趟车没有这个席别。
 */
object ResultParser {

    private const val MIN_FIELDS = 36

    private val SEAT_FIELDS = listOf(
        32 to "商务座", 25 to "特等座", 31 to "一等座", 30 to "二等座",
        21 to "高级软卧", 23 to "软卧", 33 to "动卧", 24 to "软座",
        28 to "硬卧", 29 to "硬座", 26 to "无座"
    )

    // 价格接口 data 里的键 → 席别名。同一个席别可能有两种键，按顺序取第一个有值的。
    private val PRICE_KEYS = listOf(
        "商务座" to listOf("A9", "9"),
        "特等座" to listOf("P"),
        "一等座" to listOf("M"),
        "二等座" to listOf("O"),
        "高级软卧" to listOf("A6", "6"),
        "软卧" to listOf("A4", "4"),
        "动卧" to listOf("F"),
        "软座" to listOf("A2", "2"),
        "硬卧" to listOf("A3", "3"),
        "硬座" to listOf("A1", "1"),
        "无座" to listOf("WZ")
    )

    /** root 是余票接口 status 为 true 的完整响应。 */
    fun parseQuery(root: JsonObject): QueryResponse {
        val data = root.getAsJsonObject("data") ?: return QueryResponse(emptyList(), emptyMap())
        val names = data.getAsJsonObject("map")
            ?.entrySet()?.associate { it.key to it.value.asString }
            ?: emptyMap()
        val trains = data.getAsJsonArray("result")
            ?.mapNotNull { parseTrain(it.asString) }
            ?: emptyList()
        return QueryResponse(trains, names)
    }

    fun parseTrain(line: String): RawTrain? {
        val f = line.split("|")
        if (f.size < MIN_FIELDS) return null
        val seats = SEAT_FIELDS.mapNotNull { (idx, label) ->
            val v = f[idx].trim()
            if (v.isEmpty()) null else SeatInfo(label, v)
        }
        return RawTrain(
            trainNo = f[3].trim(),
            trainCode = f[2].trim(),
            originCode = f[4].trim(),
            terminalCode = f[5].trim(),
            fromCode = f[6].trim(),
            toCode = f[7].trim(),
            departTime = f[8].trim(),
            arriveTime = f[9].trim(),
            duration = f[10].trim(),
            fromStationNo = f[16].trim(),
            toStationNo = f[17].trim(),
            seatTypes = f[35].trim(),
            seats = seats
        )
    }

    /** root 是经停站接口 status 为 true 的完整响应。 */
    fun parseStopNames(root: JsonObject): List<String> =
        root.getAsJsonObject("data")?.getAsJsonArray("data")
            ?.mapNotNull { it.asJsonObject.get("station_name")?.asString?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** 经停站表：站名、到时、发时、停留。"----" 当作没有。 */
    fun parseStops(root: JsonObject): List<StopInfo> =
        root.getAsJsonObject("data")?.getAsJsonArray("data")?.mapNotNull { e ->
            val o = e.asJsonObject
            fun str(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty().let { if (it.startsWith("-")) "" else it }
            val name = str("station_name")
            if (name.isEmpty()) null else StopInfo(name, str("arrive_time"), str("start_time"), str("stopover_time"))
        } ?: emptyList()

    /** root 是票价接口 status 为 true 的完整响应。 */
    fun parsePrices(root: JsonObject): Map<String, String> {
        val data = root.getAsJsonObject("data") ?: return emptyMap()
        return buildMap {
            for ((label, keys) in PRICE_KEYS) {
                val v = keys.firstNotNullOfOrNull { k ->
                    data.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
                }
                if (v != null) put(label, v)
            }
        }
    }

    /** 12306 的 messages 有时是数组、有时是字符串。 */
    fun messageOf(root: JsonObject): String {
        val m = root.get("messages") ?: return ""
        return when {
            m.isJsonArray -> (m as JsonArray).joinToString("；") { it.asString }
            m.isJsonPrimitive -> m.asString
            else -> ""
        }
    }
}

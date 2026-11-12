package com.wizpizz.ticket12306.model

data class Station(
    val name: String,
    val code: String,
    val pinyin: String = "",
    val initials: String = ""
)

/** 一个席别的余票情况。value 是 12306 返回的原文：数字（张数）、"有"、"无"。 */
data class SeatInfo(val label: String, val value: String) {
    val available: Boolean
        get() = value == "有" || (value.toIntOrNull() ?: 0) > 0
}

/**
 * 这张票相对于「你要坐的区间（上车站→终点站）」的关系。
 * 按这趟车的经停站顺序判断。
 */
enum class RideKind {
    /** 票就是上车站→终点站本身 */
    DIRECT,

    /** 票的起点在上车站之前（或就是上车站）、终点在终点站之后（或就是终点站）：票完整覆盖你要坐的区间 */
    COVERS,

    /** 票的终点在终点站之前：票只管到半路，后面一段要自己补票 */
    ENDS_EARLY
}

/** 一个可查询的票段（某趟车从 fromName 到 toName）。 */
data class TrainTicket(
    val trainNo: String,          // 展示车次，如 D2373
    val trainCode: String,        // 内部编号，如 5l000D237304（价格接口要用）
    val fromName: String,
    val toName: String,
    val departTime: String,
    val arriveTime: String,
    val duration: String,         // 如 04:14
    val fromStationNo: String,    // 出发站在全程中的序号（价格接口要用）
    val toStationNo: String,
    val seatTypes: String,        // 这趟车的席别代码串（价格接口要用），如 MOO
    val seats: List<SeatInfo>,
    val kind: RideKind,
    val prices: Map<String, String> = emptyMap(), // 席别名 → 价格，如 "二等座" → "¥241.0"
    val order: Int = 0,           // 在这趟车的查询顺序里排第几（0 = 直达，越小越优先）
    val earlierStops: Int = 0,    // 起点比上车站早几站（要从更早的站买票）
    val extraStops: Int = 0,      // 票的终点比终点站早几站（买到这里，之后多坐几站）
    val best: Boolean = false,    // 这趟车按顺序查到的第一个有票的票段
    val originCode: String = "",  // 这趟车的始发站、终点站电报码（查经停站要用）
    val terminalCode: String = ""
) {
    val hasSeats: Boolean get() = seats.any { it.available }
}

/** 经停站表里的一站。时间没有的（始发站没有到时、终点站没有发时）是空字符串。 */
data class StopInfo(val name: String, val arriveTime: String, val departTime: String, val stayTime: String)

/** 一次查询：哪天，从哪个站上车，到哪个站下车。 */
data class QueryRequest(
    val date: String,        // yyyy-MM-dd
    val board: Station,
    val dest: Station,
    val searchAll: Boolean = false   // 直达有票的车也继续找买短乘长的票
)

data class FindResult(
    val tickets: List<TrainTicket>,
    val directTrainCount: Int,
    val queriesMade: Int
)

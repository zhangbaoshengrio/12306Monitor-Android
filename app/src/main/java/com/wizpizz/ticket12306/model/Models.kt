package com.wizpizz.ticket12306.model

data class Station(
    val name: String,
    val code: String
)

data class TrainTicket(
    val trainNo: String,           // 车次，如 G583
    val trainNoInternal: String,   // 内部编号，如 5l000G58300（用于 czxx/价格接口）
    val originCode: String,        // 本次列车实际始发站电报码（fields[15]）
    val terminalCode: String,      // 本次列车实际终点站电报码（fields[16]）
    val fromStation: String,       // 本段票的出发站名
    val toStation: String,         // 本段票的到达站名
    val fromStationNo: String,     // 出发站在本次车中的序号（fields[10]，用于价格查询）
    val toStationNo: String,       // 到达站在本次车中的序号（fields[11]，用于价格查询）
    val departTime: String,        // 出发时间
    val arriveTime: String,        // 到达时间
    val seats: Map<String, String>,// 席别 → 余量，如 "二等座" → "有"；空表示全部无票
    val prices: Map<String, String> = emptyMap() // 席别 → 价格，如 "二等座" → "¥183.5"
)

data class MonitorConfig(
    val date: String,           // 查询日期 yyyy-MM-dd
    val boardStation: Station,  // 你要上车的经过站
    val destStation: Station,   // 最终目的地
    val cookie: String,         // 12306 登录 Cookie
    val intervalSeconds: Int = 8  // 轮询间隔
)

sealed class MonitorState {
    object Idle : MonitorState()
    object Running : MonitorState()
    data class Found(val tickets: List<TrainTicket>) : MonitorState()
    data class Error(val message: String) : MonitorState()
}

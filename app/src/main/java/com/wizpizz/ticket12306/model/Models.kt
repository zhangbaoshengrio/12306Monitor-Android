package com.wizpizz.ticket12306.model

data class Station(
    val name: String,
    val code: String
)

data class TrainTicket(
    val trainNo: String,       // 车次，如 G1474
    val fromStation: String,   // 出发站名
    val toStation: String,     // 到达站名
    val departTime: String,    // 出发时间
    val arriveTime: String,    // 到达时间
    val seats: Map<String, String>  // 席别 → 余量，如 "二等座" → "有"
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

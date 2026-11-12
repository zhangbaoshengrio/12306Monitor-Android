package com.wizpizz.ticket12306.api

import com.wizpizz.ticket12306.model.SeatInfo
import com.wizpizz.ticket12306.model.StopInfo

class TicketApiException(message: String) : Exception(message)

/** 12306 余票接口里的一趟车（出发站→到达站这一段），字段按原样取出。 */
data class RawTrain(
    val trainNo: String,        // 展示车次，如 D2373
    val trainCode: String,      // 内部编号，如 5l000D237304
    val originCode: String,     // 这趟车的始发站电报码
    val terminalCode: String,   // 这趟车的终点站电报码
    val fromCode: String,       // 本次查询的出发站电报码
    val toCode: String,         // 本次查询的到达站电报码
    val departTime: String,
    val arriveTime: String,
    val duration: String,
    val fromStationNo: String,  // 出发站在这趟车全程中的序号
    val toStationNo: String,
    val seatTypes: String,      // 席别代码串，价格接口要用
    val seats: List<SeatInfo>
)

data class QueryResponse(
    val trains: List<RawTrain>,
    val stationNames: Map<String, String>   // 电报码 → 站名，来自同一个响应
)

/** 查票用到的三个 12306 接口。拆成接口，方便用假数据测试查询逻辑。 */
interface TicketService {
    fun queryTickets(fromCode: String, toCode: String, date: String): QueryResponse

    /** 这趟车的全部经停站名，按顺序。 */
    fun getStopNames(train: RawTrain, date: String): List<String>

    /** 这趟车的经停站表（站名、到时、发时、停留）。 */
    fun getStops(trainCode: String, originCode: String, terminalCode: String, date: String): List<StopInfo>

    /** 席别名 → 价格，如 "二等座" → "¥241.0"。参数都取自余票结果里的同名字段。 */
    fun getPrices(trainCode: String, fromStationNo: String, toStationNo: String, seatTypes: String, date: String): Map<String, String>
}

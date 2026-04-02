package com.wizpizz.ticket12306.model

/**
 * 常用站名与电报码对照表
 * 完整列表见：https://kyfw.12306.cn/otn/resources/js/framework/station_name.js
 */
object StationData {

    // 预置常用站（用于自动补全）
    val commonStations = listOf(
        Station("北京", "BJP"),
        Station("北京南", "VNP"),
        Station("北京西", "BXP"),
        Station("上海", "SHH"),
        Station("上海虹桥", "AOH"),
        Station("广州南", "IZQ"),
        Station("深圳北", "IOQ"),
        Station("成都东", "ICW"),
        Station("武汉", "WHN"),
        Station("南京南", "NKH"),
        Station("杭州东", "HGH"),
        Station("西安北", "EAY"),
        Station("郑州东", "ZHH"),
        Station("合肥南", "HFN"),
        Station("合肥", "HFH"),
        Station("六安", "LAN"),
        Station("金寨", "JJZ"),
        Station("麻城北", "MCB"),
        Station("汉口", "HHK"),
        Station("汉川", "HHC"),
        Station("仙桃西", "XTX"),
        Station("荆州", "JGZ"),
        Station("宜昌东", "YCD"),
        Station("重庆北", "CQW"),
        Station("重庆西", "CQX"),
        Station("长沙南", "LHH"),
        Station("南昌西", "KNW"),
        Station("福州南", "FZS"),
        Station("厦门北", "AMF"),
        Station("济南西", "JNK"),
        Station("青岛北", "QDK"),
        Station("哈尔滨西", "HBB"),
        Station("沈阳北", "SYB"),
        Station("大连北", "DLT"),
        Station("天津南", "TJP"),
        Station("石家庄", "SJP"),
        Station("太原南", "TYV"),
        Station("呼和浩特东", "HHE"),
        Station("乌鲁木齐", "WLQ"),
        Station("拉萨", "LSA"),
        Station("昆明南", "KMQ"),
        Station("贵阳北", "GYV"),
        Station("南宁东", "NNZ"),
        Station("兰州西", "LZX"),
    )

    fun search(query: String): List<Station> {
        if (query.isBlank()) return commonStations
        return commonStations.filter {
            it.name.contains(query) || it.code.contains(query.uppercase())
        }
    }
}

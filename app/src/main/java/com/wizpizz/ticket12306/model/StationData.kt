package com.wizpizz.ticket12306.model

import java.io.InputStream

/**
 * 12306 官方站名表（assets/station_name.js，3000+ 个站）。
 * 使用前要先 load 一次。来源：https://kyfw.12306.cn/otn/resources/js/framework/station_name.js
 */
object StationData {

    private val commonNames = listOf(
        "北京南", "上海虹桥", "广州南", "深圳北", "成都东", "武汉", "南京南", "杭州东",
        "西安北", "郑州东", "长沙南", "合肥南", "重庆北", "天津", "济南西", "青岛"
    )

    @Volatile private var all: List<Station> = emptyList()
    @Volatile private var byName: Map<String, Station> = emptyMap()
    @Volatile private var byCode: Map<String, Station> = emptyMap()

    val isLoaded: Boolean get() = all.isNotEmpty()

    /** 全部车站，按 12306 站名表的顺序（大体按拼音）。 */
    val stations: List<Station> get() = all

    /** 解析 station_name.js 的内容。每个站形如 `bjb|北京北|VAP|beijingbei|bjb|0|...`，以 @ 分隔。 */
    fun parse(text: String): List<Station> =
        text.split('@').drop(1).mapNotNull { rec ->
            val p = rec.trim().trim('\'', ';').split('|')
            if (p.size < 5 || p[1].isBlank() || p[2].isBlank()) null
            else Station(name = p[1], code = p[2], pinyin = p[3], initials = p[4])
        }

    fun load(text: String) {
        val list = parse(text)
        all = list
        byName = list.associateBy { it.name }
        byCode = list.associateBy { it.code }
    }

    fun load(input: InputStream) = load(input.bufferedReader(Charsets.UTF_8).use { it.readText() })

    fun findByName(name: String): Station? = byName[name]

    fun findByCode(code: String): Station? = byCode[code]

    /** 按站名、拼音、拼音首字母搜索；没有输入时返回常用站。 */
    fun search(query: String, limit: Int = 50): List<Station> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return commonNames.mapNotNull { byName[it] }
        return all
            .mapNotNull { s -> rank(s, q)?.let { it to s } }
            .sortedWith(compareBy({ it.first }, { it.second.name.length }))
            .take(limit)
            .map { it.second }
    }

    private fun rank(s: Station, q: String): Int? = when {
        s.name == q -> 0
        s.name.startsWith(q) -> 1
        s.initials == q -> 2
        s.initials.startsWith(q) -> 3
        s.pinyin.startsWith(q) -> 4
        s.name.contains(q) -> 5
        s.pinyin.contains(q) -> 6
        s.code.equals(q, ignoreCase = true) -> 7
        else -> null
    }
}

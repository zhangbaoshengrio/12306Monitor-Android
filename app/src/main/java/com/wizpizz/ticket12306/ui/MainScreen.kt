package com.wizpizz.ticket12306.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.viewinterop.AndroidView
import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.wizpizz.ticket12306.model.StopInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wizpizz.ticket12306.model.RideKind
import com.wizpizz.ticket12306.model.Station
import com.wizpizz.ticket12306.model.StationData
import com.wizpizz.ticket12306.model.TrainTicket
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val Blue = Color(0xFF1B6AD0)
private val Orange = Color(0xFFC2410C)
private val Green = Color(0xFF1B7F45)
private val Grey = Color(0xFF5F6B7A)
private val Bg = Color(0xFFF2F5F9)
private val Line = Color(0xFFE1E6ED)

private const val LOGIN_USER_PREFS = "login_status"
private const val LOGIN_USER_KEY = "user"

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val showResults by vm.showResults.collectAsState()
    var webBuy by remember { mutableStateOf<WebBuy?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(LOGIN_USER_PREFS, android.content.Context.MODE_PRIVATE) }
    // 只是「最近一次看到的登录名」，给首页右上角一个提示；真的有没有登录，还是看网页自己的 cookie
    var loginUser by remember { mutableStateOf(prefs.getString(LOGIN_USER_KEY, null)) }
    fun setLoginUser(name: String?) {
        loginUser = name
        prefs.edit().putString(LOGIN_USER_KEY, name).apply()
    }
    BackHandler(enabled = showResults) { vm.backToSearch() }
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (showResults) ResultsScreen(vm, onBuy = { webBuy = it })
        else SearchScreen(vm, loginUser = loginUser, onLogin = { showLogin = true })
        webBuy?.let { WebScreen(it, onClose = { webBuy = null }) }
        if (showLogin) LoginScreen(onClose = { showLogin = false }, onUserChanged = ::setLoginUser)
    }
}

// ─────────────────────────────────────────────────────────────
// 搜索页
// ─────────────────────────────────────────────────────────────

@Composable
private fun SearchScreen(vm: MainViewModel, loginUser: String?, onLogin: () -> Unit) {
    val board by vm.boardStationSelected.collectAsState()
    val dest by vm.destStationSelected.collectAsState()
    val date by vm.dateInput.collectAsState()
    val error by vm.errorMsg.collectAsState()
    val searchAll by vm.searchAll.collectAsState()
    val recent by vm.recentStations.collectAsState()
    var picking by remember { mutableStateOf<Boolean?>(null) }   // true = 上车站, false = 终点站
    var pickingDate by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth().background(Blue)
                .statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 56.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("买短乘长查票", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("直达票售罄？查询可在上车站乘坐的其他票段", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                // 提前在这里登录一次 12306，之后点「网页购票」就不用再登录了（跟网页购票用的是同一个网页、同一份登录状态）
                // 登录过之后这里换成显示用户名，再点进去是看登录状态/换账号，不是去登录
                TextButton(onClick = onLogin) {
                    Text(
                        if (loginUser != null) "您好，$loginUser" else "提前登录 12306",
                        color = Color.White, fontSize = 13.sp
                    )
                }
            }
        }

        Card(
            modifier = Modifier.padding(horizontal = 16.dp).offset(y = (-36).dp).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Box {
                    Column {
                        StationRow("上车站", "你要上车的站", board) { picking = true }
                        HorizontalDivider(color = Line)
                        StationRow("终点站", "你最终要去的站", dest) { picking = false }
                    }
                    Surface(
                        onClick = { vm.swapStations() },
                        shape = CircleShape,
                        color = Color.White,
                        border = BorderStroke(1.dp, Line),
                        modifier = Modifier.align(Alignment.CenterEnd).size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("⇅", color = Blue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HorizontalDivider(color = Line)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().clickable { pickingDate = true }.padding(bottom = 10.dp)) {
                    Text("出发日期", color = Grey, fontSize = 12.sp)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        val d = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                        Text(
                            if (d != null) "${d.monthValue}月${d.dayOfMonth}日" else date,
                            fontSize = 24.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(weekdayOf(date).orEmpty(), color = Grey, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
                        Spacer(Modifier.weight(1f))
                        Text("›", color = Color(0xFFA0AAB8), fontSize = 22.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("今天" to 0L, "明天" to 1L, "后天" to 2L).forEach { (label, off) ->
                        val selected = date.trim() == LocalDate.now().plusDays(off).toString()
                        FilterChip(
                            selected = selected,
                            onClick = { vm.setDateOffset(off) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Blue.copy(alpha = 0.12f), selectedLabelColor = Blue)
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("直达有票时仍查询买短乘长方案", fontSize = 14.sp)
                        Text("关闭后，仅在直达票售罄时查询", color = Grey, fontSize = 12.sp)
                    }
                    Switch(checked = searchAll, onCheckedChange = { vm.searchAll.value = it })
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { vm.queryOnce() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)
                ) { Text("查询", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            }
        }

        val context = LocalContext.current
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        }
        Text(
            "版本 $version", color = Color(0xFF9AA5B3), fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), textAlign = TextAlign.Center
        )
    }

    picking?.let { isBoard ->
        StationPickerScreen(
            recent = recent,
            onSelected = {
                if (isBoard) vm.boardStationSelected.value = it else vm.destStationSelected.value = it
                vm.addRecent(it)
                picking = null
            },
            onBack = { picking = null }
        )
    }
    if (pickingDate) {
        DatePickerScreen(
            selected = runCatching { LocalDate.parse(date.trim()) }.getOrNull(),
            onSelected = { vm.dateInput.value = it.toString(); pickingDate = false },
            onBack = { pickingDate = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 选择日期（12306 样式的日历）
// ─────────────────────────────────────────────────────────────

/** 12306 的预售期：今天起 15 天（含今天）。之后的日期还没开售，日历里变灰、不能选。 */
private const val PRESALE_DAYS = 15L

private val LunarDays = listOf(
    "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
    "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
    "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
)

/** 农历几号（初一…三十）。 */
private fun lunarDay(d: LocalDate): String {
    val tz = android.icu.util.TimeZone.getTimeZone("Asia/Shanghai")
    val c = android.icu.util.ChineseCalendar(tz)
    c.timeInMillis = d.atTime(12, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    return LunarDays.getOrElse(c.get(android.icu.util.Calendar.DAY_OF_MONTH) - 1) { "" }
}

@Composable
private fun DatePickerScreen(selected: LocalDate?, onSelected: (LocalDate) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val today = remember { LocalDate.now() }
    val last = remember(today) { today.plusDays(PRESALE_DAYS - 1) }
    // 从这个月排到预售期最后一天所在的月份
    val months = remember(today) {
        generateSequence(today.withDayOfMonth(1)) { it.plusMonths(1) }.takeWhile { !it.isAfter(last) }.toList()
    }
    val red = Color(0xFFE02020)
    val off = Color(0xFFC5CCD6)

    Column(Modifier.fillMaxSize().background(Color.White).pointerInput(Unit) { detectTapGestures { } }) {
        Row(
            Modifier.fillMaxWidth().background(Blue).statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", color = Color.White, fontSize = 15.sp) }
            Text(
                "选择日期", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(64.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { i, w ->
                Text(
                    w, color = if (i == 0 || i == 6) red else Color(0xFF1F2937), fontSize = 14.sp,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                )
            }
        }
        HorizontalDivider(color = Line)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            months.forEach { first ->
                Text(
                    "${first.year}年${first.monthValue}月", fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().background(Bg).padding(vertical = 8.dp), textAlign = TextAlign.Center
                )
                val lead = first.dayOfWeek.value % 7   // 周日排第一列
                val cells = List(lead) { null } + (1..first.lengthOfMonth()).map { first.withDayOfMonth(it) }
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        for (i in 0 until 7) {
                            val d = week.getOrNull(i)
                            if (d == null) { Spacer(Modifier.weight(1f)); continue }
                            val enabled = !d.isBefore(today) && !d.isAfter(last)
                            val isSelected = d == selected && enabled
                            val weekend = i == 0 || i == 6
                            val color = when {
                                isSelected -> Color.White
                                !enabled -> off
                                d == today -> Blue
                                weekend -> red
                                else -> Color(0xFF1F2937)
                            }
                            Column(
                                Modifier.weight(1f).padding(horizontal = 2.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Blue else Color.Transparent)
                                    .clickable(enabled = enabled) { onSelected(d) }
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(d.dayOfMonth.toString(), color = color, fontSize = 18.sp)
                                Text(
                                    if (d == today) "今天" else lunarDay(d),
                                    color = if (isSelected || d == today || !enabled) color else Grey, fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "灰色的日期还没开售（12306 预售期 ${PRESALE_DAYS} 天）", color = Grey, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StationRow(label: String, hint: String, station: Station?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Text(label, color = Grey, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        if (station != null) Text(station.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        else Text(hint, fontSize = 20.sp, color = Color(0xFFA0AAB8))
    }
}

// ─────────────────────────────────────────────────────────────
// 结果页
// ─────────────────────────────────────────────────────────────

private enum class SortBy(val label: String) { DEPART("出发时间"), DURATION("耗时最短"), SEATS("有票优先") }

private data class Filters(
    val trainTypes: Set<String> = emptySet(),   // G/C、D、其他
    val seats: Set<String> = emptySet(),        // 席别名：只留有这些席别余票的票
    val kinds: Set<RideKind> = emptySet(),
    val froms: Set<String> = emptySet(),
    val tos: Set<String> = emptySet(),
    val times: Set<Int> = emptySet()            // 出发时段 0..3
) {
    val count get() = listOf(trainTypes, seats, kinds, froms, tos, times).count { it.isNotEmpty() }
}

private fun trainType(no: String) = when {
    no.startsWith("G") || no.startsWith("C") -> "高铁/城际"
    no.startsWith("D") -> "动车"
    else -> "其他"
}

private fun timeBucket(t: String) = ((t.take(2).toIntOrNull() ?: 0) / 6).coerceIn(0, 3)
private val TimeLabels = listOf("00:00–06:00", "06:00–12:00", "12:00–18:00", "18:00–24:00")

private fun Filters.matches(t: TrainTicket) =
    (trainTypes.isEmpty() || trainType(t.trainNo) in trainTypes) &&
        (seats.isEmpty() || t.seats.any { it.available && it.label in seats }) &&
        (kinds.isEmpty() || t.kind in kinds) &&
        (froms.isEmpty() || t.fromName in froms) &&
        (tos.isEmpty() || t.toName in tos) &&
        (times.isEmpty() || timeBucket(t.departTime) in times)

/** 一趟车：full 是这趟车查到的全部票段，shown 是通过筛选后要显示的。 */
private data class TrainGroup(val full: List<TrainTicket>, val shown: List<TrainTicket>) {
    val head: TrainTicket get() = full.firstOrNull { it.kind == RideKind.DIRECT } ?: full.first()
}

private fun minutes(d: String): Int {
    val p = d.split(":")
    return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultsScreen(vm: MainViewModel, onBuy: (WebBuy) -> Unit) {
    val board by vm.boardStationSelected.collectAsState()
    val dest by vm.destStationSelected.collectAsState()
    val date by vm.dateInput.collectAsState()
    val querying by vm.isQuerying.collectAsState()
    val result by vm.queryResult.collectAsState()
    val log by vm.queryLog.collectAsState()
    val error by vm.errorMsg.collectAsState()
    val loadingNow by vm.loadingPrices.collectAsState()
    val pricesPending by vm.pricesPending.collectAsState()
    val stopsByTrain by vm.stops.collectAsState()
    val loadingStops by vm.loadingStops.collectAsState()

    var filters by remember { mutableStateOf(Filters()) }
    var sort by remember { mutableStateOf(SortBy.DEPART) }
    var showFilter by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    val all = result ?: emptyList()
    // 自动查票价还没跑完时，所有还没价格的票都算「查询中」
    val loading = if (pricesPending) all.map(vm::key).toSet() else loadingNow
    val groups = remember(all, filters, sort) {
        // 查询结果已经按车次分组、组内按查询顺序排好；筛选只影响组里显示哪些票段
        val list = all.groupBy { it.trainCode }.values
            .map { full -> TrainGroup(full, full.filter { filters.matches(it) }) }
            .filter { it.shown.isNotEmpty() }
        when (sort) {
            SortBy.DEPART -> list
            SortBy.DURATION -> list.sortedBy { minutes(it.head.duration) }
            SortBy.SEATS -> list.sortedWith(compareByDescending<TrainGroup> { g -> g.full.any { it.best } }.thenBy { it.head.departTime })
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回 + 路线
        Row(
            Modifier.fillMaxWidth().background(Blue).statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { vm.backToSearch() }) { Text("‹ 返回", color = Color.White, fontSize = 15.sp) }
            Text(
                "${board?.name ?: ""}  →  ${dest?.name ?: ""}",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(64.dp))
        }
        // 日期条
        Row(
            Modifier.fillMaxWidth().background(Blue).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            val canGoBack = runCatching { LocalDate.parse(date.trim()).isAfter(LocalDate.now()) }.getOrDefault(false)
            TextButton(onClick = { vm.shiftDate(-1) }, enabled = canGoBack && !querying) {
                Text("‹ 前一天", color = Color.White.copy(alpha = if (canGoBack) 1f else 0.4f))
            }
            Text(
                date.trim().drop(5) + (weekdayOf(date)?.let { "  $it" } ?: ""),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
            TextButton(onClick = { vm.shiftDate(1) }, enabled = !querying) { Text("后一天 ›", color = Color.White) }
        }
        // 筛选 + 排序
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = filters.count > 0,
                onClick = { showFilter = true },
                label = { Text(if (filters.count > 0) "筛选 ${filters.count}" else "筛选") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Blue.copy(alpha = 0.12f), selectedLabelColor = Blue)
            )
            Spacer(Modifier.weight(1f))
            SortBy.values().forEach {
                Text(
                    it.label,
                    color = if (sort == it) Blue else Grey,
                    fontWeight = if (sort == it) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { sort = it }.padding(horizontal = 6.dp, vertical = 6.dp)
                )
            }
        }
        HorizontalDivider(color = Line)

        when {
            querying && all.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Blue)
                Spacer(Modifier.height(12.dp))
                Text("正在查询…", color = Grey)
                Spacer(Modifier.height(12.dp))
                log.takeLast(4).forEach { Text(it, fontSize = 12.sp, color = Grey) }
            }
            error != null && all.isEmpty() -> Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
            else -> LazyColumn(
                contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    val have = groups.count { g -> g.full.any { it.best } }
                    Text(
                        if (groups.isEmpty()) "没有符合条件的车次" else "共 ${groups.size} 趟车，其中 $have 趟有可购票段",
                        color = if (have > 0) Green else Grey, fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                }
                items(groups, key = { it.head.trainCode }) { g ->
                    TrainCard(
                        g, boardName = board?.name ?: "", destName = dest?.name ?: "", date = date.trim(),
                        loading = loading, keyOf = vm::key, onLoadPrice = { vm.loadPrices(it) },
                        stops = stopsByTrain[g.head.trainCode], loadingStops = g.head.trainCode in loadingStops,
                        onExpand = { vm.loadStops(g.head) }, onBuy = onBuy
                    )
                }
                item {
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "收起查询过程" else "查看查询过程", color = Blue) }
                    if (showLog) log.forEach { Text(it, fontSize = 12.sp, color = Grey, modifier = Modifier.padding(vertical = 1.dp)) }
                }
            }
        }
    }

    if (showFilter) {
        ModalBottomSheet(onDismissRequest = { showFilter = false }, containerColor = Color.White) {
            FilterSheet(all, filters, { filters = it }, shownCount = groups.size) { showFilter = false }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    all: List<TrainTicket>, filters: Filters, onChange: (Filters) -> Unit, shownCount: Int, onDone: () -> Unit
) {
    fun <T> Set<T>.toggle(v: T) = if (v in this) this - v else this + v
    val seatLabels = all.flatMap { t -> t.seats.filter { it.available }.map { it.label } }.distinct()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        FilterGroup("车次类型", listOf("高铁/城际", "动车", "其他"), filters.trainTypes) { onChange(filters.copy(trainTypes = filters.trainTypes.toggle(it))) }
        if (seatLabels.isNotEmpty())
            FilterGroup("有票的席别", seatLabels, filters.seats) { onChange(filters.copy(seats = filters.seats.toggle(it))) }
        Text("票的类型", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RideKind.DIRECT to "直达", RideKind.COVERS to "提前购票", RideKind.ENDS_EARLY to "买短乘长", RideKind.SECOND_SEAT to "换座").forEach { (k, label) ->
                OptionChip(label, k in filters.kinds) { onChange(filters.copy(kinds = filters.kinds.toggle(k))) }
            }
        }
        FilterGroup("出发车站", all.map { it.fromName }.distinct(), filters.froms) { onChange(filters.copy(froms = filters.froms.toggle(it))) }
        FilterGroup("到达车站", all.map { it.toName }.distinct(), filters.tos) { onChange(filters.copy(tos = filters.tos.toggle(it))) }
        Text("出发时间", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeLabels.forEachIndexed { i, label ->
                OptionChip(label, i in filters.times) { onChange(filters.copy(times = filters.times.toggle(i))) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onChange(Filters()) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("重置") }
            Button(
                onClick = onDone, modifier = Modifier.weight(2f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue)
            ) { Text("确定（$shownCount 趟）") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { OptionChip(it, it in selected) { onToggle(it) } }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Blue.copy(alpha = 0.12f), selectedLabelColor = Blue)
    )
}

// ─────────────────────────────────────────────────────────────
// 车次卡片：一趟车一张，里面是 12306 样式的票段，按查询顺序排好；点车次头可展开经停站
// ─────────────────────────────────────────────────────────────

@Composable
private fun TrainCard(
    group: TrainGroup, boardName: String, destName: String, date: String,
    loading: Set<String>, keyOf: (TrainTicket) -> String, onLoadPrice: (TrainTicket) -> Unit,
    stops: List<StopInfo>?, loadingStops: Boolean, onExpand: () -> Unit, onBuy: (WebBuy) -> Unit
) {
    var showSoldOut by remember { mutableStateOf(false) }
    val head = group.head
    // 换座：第一张票（只买到半路的最优票）和第二张票（换座票）当成一组，上下挨着显示；筛选只留下其中一张时另一张也带上
    val second = group.full.firstOrNull { it.kind == RideKind.SECOND_SEAT && it.hasSeats }
    val first = second?.let { s -> group.full.firstOrNull { it.kind == RideKind.ENDS_EARLY && it.hasSeats && it.toName == s.afterName } }
    val paired = first != null && second != null && (first in group.shown || second in group.shown)
    val withSeats = group.shown.filter { it.hasSeats && !(paired && (it == first || it == second)) }
    val soldOut = group.shown.filter { !it.hasSeats }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(Blue.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(head.trainNo, color = Blue, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "$boardName ${head.departTime} → $destName ${head.arriveTime}",
                    color = Grey, fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
            }
            @Composable
            fun block(t: TrainTicket, inPair: Boolean = false) = SegmentBlock(
                t, boardName, destName, date, loading = keyOf(t) in loading, onLoadPrice = { onLoadPrice(t) },
                stops = stops, loadingStops = loadingStops, onExpand = onExpand, onBuy = onBuy, inPair = inPair
            )
            if (paired && first != null && second != null) {
                Column(
                    Modifier.fillMaxWidth().padding(8.dp)
                        .border(1.dp, Blue.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                ) {
                    Text(
                        "一组票 · 两张都买，到${second.fromName}换座", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 12.dp, top = 6.dp)
                    )
                    block(first, inPair = true)
                    Text(
                        "↓ 到${second.fromName}不下车，换到第二张票的座位，不用补票", color = Green, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().background(Blue.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    block(second, inPair = true)
                    val context = LocalContext.current
                    Button(
                        onClick = { com.wizpizz.ticket12306.auto.Auto12306.start(context, first, first.date.ifEmpty { date }, second = second) },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).height(32.dp)
                    ) { Text("去12306 · 两张一起下单", fontSize = 13.sp) }
                }
                if (withSeats.isNotEmpty()) HorizontalDivider(color = Line)
            }
            if (withSeats.isEmpty() && !paired) {
                Text("该车次暂无可购票段", color = Grey, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
            withSeats.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider(color = Line)
                block(t)
            }
            if (soldOut.isNotEmpty()) {
                HorizontalDivider(color = Line)
                Text(
                    if (showSoldOut) "收起 ${soldOut.size} 个无票的票段 ▴" else "另有 ${soldOut.size} 个票段无票 ▾",
                    color = Blue, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clickable { showSoldOut = !showSoldOut }.padding(horizontal = 12.dp, vertical = 8.dp)
                )
                if (showSoldOut) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    soldOut.forEach {
                        Text(
                            "${it.fromName} ${it.departTime} → ${it.toName} ${it.arriveTime}   无票",
                            color = Color(0xFF9AA5B3), fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/** 经停站表（12306 样式）：这张票的起点和终点是蓝色实心点，区间之外的站变灰；你的上车站、终点站另加小标签。 */
@Composable
private fun StopsTable(stops: List<StopInfo>, fromName: String, toName: String, boardName: String, destName: String) {
    val bi = stops.indexOfFirst { it.name == fromName }
    val di = stops.indexOfFirst { it.name == toName }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().background(Blue.copy(alpha = 0.08f)).padding(vertical = 6.dp)) {
            Text("站名", color = Grey, fontSize = 12.sp, modifier = Modifier.weight(2.2f).padding(start = 40.dp))
            Text("到时", color = Grey, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("发时", color = Grey, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("停留", color = Grey, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        stops.forEachIndexed { i, st ->
            val key = i == bi || i == di
            val inRange = bi >= 0 && di >= 0 && i in bi..di
            val color = when {
                key -> Blue
                inRange || bi < 0 -> Color(0xFF1F2937)
                else -> Color(0xFFA0AAB8)
            }
            Row(
                Modifier.fillMaxWidth().height(34.dp).drawBehind {
                    val x = 20.dp.toPx()
                    val cy = size.height / 2
                    val lineColor = if (inRange) Blue else Color(0xFFD5DBE3)
                    if (i > 0) drawLine(lineColor, Offset(x, 0f), Offset(x, cy), 2.dp.toPx())
                    if (i < stops.lastIndex) drawLine(lineColor, Offset(x, cy), Offset(x, size.height), 2.dp.toPx())
                    if (key) drawCircle(Blue, 5.dp.toPx(), Offset(x, cy), style = Fill)
                    else drawCircle(lineColor, 4.dp.toPx(), Offset(x, cy), style = Stroke(1.5.dp.toPx()))
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(2.2f).padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(st.name, color = color, fontSize = 14.sp, fontWeight = if (key) FontWeight.Bold else FontWeight.Normal)
                    val role = when (st.name) { boardName -> "上车"; destName -> "终点"; else -> null }
                    if (role != null) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            role, color = Green, fontSize = 10.sp, maxLines = 1, softWrap = false,
                            modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Green.copy(alpha = 0.12f)).padding(horizontal = 4.dp)
                        )
                    }
                }
                for (v in listOf(st.arriveTime, st.departTime, st.stayTime)) {
                    Text(v.ifEmpty { "----" }, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun seatShort(label: String) = if (label == "无座") label else label.removeSuffix("座")

/** 12306 样式的一张票（紧凑版）：左出发、中车次和历时、右到达、最右价格；下面一行席别；再下面一行说明和两个购票按钮。 */
@Composable
private fun SegmentBlock(
    t: TrainTicket, boardName: String, destName: String, date: String, loading: Boolean, onLoadPrice: () -> Unit,
    stops: List<StopInfo>?, loadingStops: Boolean, onExpand: () -> Unit, onBuy: (WebBuy) -> Unit,
    inPair: Boolean = false   // 换座的一组票里的一张：补票说明写在两张票中间，这里不再重复
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var seatsOpen by remember { mutableStateOf(false) }
    val rideDate = t.date.ifEmpty { date }   // 过夜车的换座票是第二天的票
    val lowest = t.seats.filter { it.available }
        .mapNotNull { t.prices[it.label]?.removePrefix("¥")?.toDoubleOrNull() }.minOrNull()

    Column(
        Modifier.fillMaxWidth().clickable { seatsOpen = !seatsOpen; if (t.prices.isEmpty()) onLoadPrice() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t.departTime, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(t.fromName, fontSize = 13.sp)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t.trainNo, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    durationText(t.duration) + if (expanded) " ▴" else " ▾", color = Grey, fontSize = 11.sp,
                    modifier = Modifier.clickable { expanded = !expanded; if (expanded) onExpand() }.padding(vertical = 2.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(t.arriveTime, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(t.toName, fontSize = 13.sp)
            }
            Text(
                when {
                    lowest != null -> "¥${if (lowest % 1.0 == 0.0) lowest.toInt() else lowest}起"
                    loading -> "票价查询中…"
                    else -> "票价没查到\n点击重试"
                },
                color = if (lowest != null) Orange else Grey,
                fontSize = if (lowest != null) 16.sp else 11.sp, lineHeight = 14.sp, textAlign = TextAlign.End,
                fontWeight = if (lowest != null) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Row(Modifier.fillMaxWidth()) {
            t.seats.forEach { seat ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Grey)) { append(seatShort(seat.label) + " ") }
                        withStyle(SpanStyle(
                            color = if (seat.available) Green else Color(0xFFB0B8C4),
                            fontWeight = FontWeight.Medium
                        )) {
                            append(when {
                                !seat.available -> "无"
                                seat.value == "有" -> "有票"
                                else -> seat.value + "张"
                            })
                        }
                    },
                    fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
            }
            Text(if (seatsOpen) "▴" else "▾", color = Grey, fontSize = 12.sp)
        }

        // 点一下这张票，像 12306 那样展开：每个席别一行，价格、余票、预订
        if (seatsOpen) Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFFF5F7FA)).padding(horizontal = 10.dp)
        ) {
            t.seats.forEachIndexed { i, seat ->
                if (i > 0) HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(seatShort(seat.label), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        t.prices[seat.label] ?: if (loading) "…" else "—", color = Orange, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.2f)
                    )
                    Text(
                        when {
                            !seat.available -> "无"
                            seat.value == "有" -> "有票"
                            else -> seat.value + "张"
                        },
                        color = if (seat.available) Green else Color(0xFFB0B8C4), fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                    // 换座的一组票两张要一起下单，按钮在这一组的最下面
                    if (!inPair) Button(
                        onClick = { com.wizpizz.ticket12306.auto.Auto12306.start(context, t, rideDate, seat.label) },
                        enabled = seat.available,
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("预订", fontSize = 12.sp) }
                }
            }
        }

        if (expanded) {
            when {
                stops != null -> StopsTable(stops, t.fromName, t.toName, boardName, destName)
                loadingStops -> Text("经停站加载中…", color = Grey, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                else -> Text("经停站加载失败，收起后重新展开可重试", color = Grey, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (t.best || t.kind == RideKind.DIRECT) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.best) {
                        Text(
                            "最优", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Green).padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (t.kind == RideKind.DIRECT) Text("直达", color = Grey, fontSize = 12.sp)
                }
                if (t.earlierStops > 0)
                    Text("提前${t.earlierStops}站买票，从${t.fromName}买，在${boardName}上车", color = Blue, fontSize = 12.sp)
                if (t.extraStops > 0 && inPair)
                    Text("第一张票，先坐到${t.toName}", color = Blue, fontSize = 12.sp)
                else if (t.extraStops > 0)
                    Text("买到${t.toName}，多坐${t.extraStops}站要补票", color = Orange, fontSize = 12.sp)
                if (t.kind == RideKind.SECOND_SEAT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "换座", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Blue).padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("第二张票，到${t.fromName}换到这个座位", color = Blue, fontSize = 12.sp)
                    }
                    if (!inPair) Text("接着买到${t.afterName}的票，不用补票", color = Green, fontSize = 12.sp)
                    if (t.date.isNotEmpty()) Text("这张票是 ${t.date.drop(5)} 的（过夜车）", color = Orange, fontSize = 12.sp)
                }
            }
            OutlinedButton(
                onClick = {
                    copyPurchaseNote(context, purchaseNote(t, rideDate, boardName))
                    onBuy(WebBuy(purchaseUrl(t, rideDate), t.trainNo, "ticket_${t.trainCode}_${t.fromStationNo}_${t.toStationNo}"))
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(26.dp)
            ) { Text("网页购票", fontSize = 12.sp) }
            if (!inPair) {
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { com.wizpizz.ticket12306.auto.Auto12306.start(context, t, rideDate) },
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)
                ) { Text("去12306", fontSize = 12.sp) }
            }
        }
    }
}

private fun durationText(d: String): String {
    val p = d.split(":")
    val h = p.getOrNull(0)?.toIntOrNull() ?: return d
    val m = p.getOrNull(1)?.toIntOrNull() ?: 0
    return if (h > 0) "${h}小时${m}分" else "${m}分"
}

private fun weekdayOf(date: String): String? = runCatching {
    LocalDate.parse(date.trim()).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
}.getOrNull()

/** 要在 12306 里搜的内容：日期、车次、这张票的起终点；买短乘长的票另注明在哪站上车。 */
private fun purchaseNote(t: TrainTicket, date: String, boardName: String): String =
    "${date.drop(5)} ${t.trainNo} ${t.fromName}→${t.toName}" +
        when {
            t.kind == RideKind.SECOND_SEAT -> "（换座票）"
            t.fromName != boardName -> "（在${boardName}上车）"
            else -> ""
        }

/** 12306 网页版的余票查询页，出发站、到达站、日期已经填好（用这张票自己的起终点）。 */
private fun purchaseUrl(t: TrainTicket, date: String): String {
    fun part(name: String): String {
        val code = StationData.findByName(name)?.code.orEmpty()
        return java.net.URLEncoder.encode(name, "UTF-8") + "," + code
    }
    return "https://kyfw.12306.cn/otn/leftTicket/init?linktypeid=dc&fs=${part(t.fromName)}&ts=${part(t.toName)}&date=$date&flag=N,N,Y"
}

/** 把票的信息复制到剪贴板，方便在 12306 里对照。 */
private fun copyPurchaseNote(context: android.content.Context, note: String) {
    runCatching {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("车票", note))
        android.widget.Toast.makeText(context, "已复制：$note", android.widget.Toast.LENGTH_LONG).show()
    }
}

/** 打开 12306 APP（没装就打开网页）。它没有能直接打开某张票的链接。 */
private fun open12306App(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("com.MobileTicket")
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://kyfw.12306.cn/otn/leftTicket/init"))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

// ─────────────────────────────────────────────────────────────
// App 内的 12306 网页（官方网站，你自己在上面登录和购票；我们看不到你的密码）
// ─────────────────────────────────────────────────────────────

/**
 * 网页购票要打开的东西：余票查询页的网址，以及这张票在那一页结果表里的行 id（ticket_车次编号_出发站序号_到达站序号）。
 * 有行 id 时，网页先盖住，在后面自动点这一行的「预订」，到了订单页（或者要登录）再露出来。
 * 具体到哪个席别是 12306 自己决定的——它那一行只有一个不分席别的「预订」链接，我们这边管不到。
 */
private class WebBuy(val url: String, val trainNo: String, val rowId: String)

/** 调试用：现在的网址、结果行数、网页发过的请求和回应的开头、屏幕上看得见的弹窗文字。 */
private const val PROBE = """
(function(){
  if (!window.__hook && window.jQuery) {
    window.__hook = 1; window.__log = '';
    jQuery(document).ajaxComplete(function(e, x, o){ window.__log += ' | ' + o.url + ' ' + x.status + ' ' + (x.responseText || '').slice(0, 160); });
  }
  var t = '';
  document.querySelectorAll('.mask,.modal-login,[class*=dhtmlx],[class*=dhx]').forEach(function(e){
    var st = getComputedStyle(e);
    if (st.display != 'none' && st.visibility != 'hidden' && e.getBoundingClientRect().height > 0) t += ' [' + e.className + '] ' + (e.innerText || '').slice(0, 120);
  });
  return location.href.slice(0, 70) + ' rows=' + document.querySelectorAll('#queryLeftTable tr').length + ' ajax:' + (window.__log || '') + ' seen:' + t.slice(0, 600);
})()
"""

/** 在 12306 余票查询页里找到这张票那一行，点它的「预订」；每半秒跑一次，返回现在到哪一步了。只点这一个按钮。
 *  第一次点了「预订」之后，12306 如果判断还没登录，只会弹登录框，并不会在登录成功后自己继续下单——
 *  所以只要还停在查询页（还没到订单页，也没查到卖光），就隔几秒再点一次，而不是点一次就不管了。 */
private fun bookScript(rowId: String) = """
(function(){
  if (location.href.indexOf('confirmPassenger') >= 0) return 'order';
  if (location.href.indexOf('leftTicket/init') < 0) return 'other';
  var s = window.__buy || (window.__buy = {n: 0, lastClick: 0});
  var row = document.getElementById('$rowId');
  if (row) {
    var a = row.querySelector('a.btn72');
    if (!a) return 'soldout';
    var now = Date.now();
    if (now - s.lastClick > 4000) { a.click(); s.lastClick = now; }
    return 'clicked';
  }
  s.n++;
  if (s.n == 10 && !document.querySelector('#queryLeftTable tr')) {
    var q = document.getElementById('query_ticket');
    if (q) q.click();
  }
  return s.n > 50 ? 'notfound' : 'waiting';
})()
"""

/** 到了订单页之后，12306 自己偶尔会在加载乘车人信息时误判成「没登录」（哪怕其实登录着）。
 *  这段只找「写着『用户未登录』的那个提示框」，点掉它唯一的「确定」；不认识别的提示就不碰。 */

private const val LOGIN_DIALOG_SCRIPT = """
(function(){
  if (location.href.indexOf('confirmPassenger') < 0) return 'left';
  function vis(e) {
    var st = getComputedStyle(e);
    if (st.display === 'none' || st.visibility === 'hidden') return false;
    var r = e.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }
  var all = document.querySelectorAll('body *');
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    if (el.children.length === 0 && el.textContent && el.textContent.trim() === '用户未登录' && vis(el)) {
      var cur = el;
      for (var d = 0; d < 8 && cur; d++, cur = cur.parentElement) {
        var nodes = cur.querySelectorAll('*');
        for (var j = 0; j < nodes.length; j++) {
          var b = nodes[j];
          if (b.children.length === 0 && b.textContent && b.textContent.trim() === '确定' && vis(b)) {
            b.click();
            return 'dismissed';
          }
        }
      }
    }
  }
  return 'none';
})()
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebScreen(buy: WebBuy, onClose: () -> Unit) {
    val url = buy.url
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }
    var covered by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }

    // 点过「预订」之后，不能因为网页先盖住了就不再看它：用户可能要先登录，登录完网页会自己跳到订单页，
    // 这时候要把提示换掉，而不是让「请先登录」一直挂在那不动（之前出现过这个 bug）。所以这里不在揭开遮罩后就
    // 停止检查，而是一直看到订单页为止；只有点「直接看网页」或关掉这个页面，才真正不再管它。
    LaunchedEffect(webView) {
        val w = webView ?: return@LaunchedEffect
        var last = ""
        suspend fun run(js: String): String = suspendCancellableCoroutine { c ->
            w.evaluateJavascript(js) { if (c.isActive) c.resume(it.orEmpty().trim('"')) }
        }
        // 网页刚打开时是按加载中的那个窄页面算的缩放，后面表格、乘客信息这些东西撑开了页面，缩放就跟着不对了，
        // 右边会空出一截。揭开遮罩给用户看之前，轻轻缩一下再放回来，逼网页按现在的实际宽度重新算一次缩放。
        suspend fun nudgeZoom() {
            w.zoomOut(); delay(16); w.zoomIn()
        }
        suspend fun log(state: String) {
            val seen = run(PROBE)
            if ("$state $seen" != last) {
                last = "$state $seen"
                runCatching { java.io.File(context.filesDir, "web.log").appendText("${System.currentTimeMillis() / 1000} $state $seen\n") }
            }
        }

        var orderRetries = 0
        outer@ while (true) {
            var sinceClick = -1
            var elsewhere = 0
            var elsewhereAfterClick = 0
            var clickedOnce = false
            var retried = false
            var reachedOrder = false
            while (true) {
                delay(500)
                val state = run(bookScript(buy.rowId))
                log(state)
                when (state) {
                    "order" -> {
                        note = "已到订单页，请自己选乘车人、提交订单"; covered = false; nudgeZoom()
                        reachedOrder = true
                    }
                    "soldout" -> { note = "网页上 ${buy.trainNo} 这张票现在不能预订，请自己看一下"; covered = false; nudgeZoom() }
                    "notfound" -> { note = "网页上没找到 ${buy.trainNo}，请自己点查询再找"; covered = false; nudgeZoom() }
                    "other" -> {
                        if (clickedOnce) {
                            // 点过预订、也登录过了，网页却跳回了个人中心而不是订单页：重新打开查询页，再点一次预订（只重试一次）
                            if (++elsewhereAfterClick >= 8 && !retried) {
                                retried = true
                                elsewhereAfterClick = 0
                                sinceClick = -1
                                note = ""
                                covered = true
                                w.loadUrl(buy.url)
                            }
                        } else if (++elsewhere >= 16) {
                            // 刚打开时网页还没载入，地址也不是查询页；连着 8 秒都不在查询页才算真的去了别处
                            covered = false
                            nudgeZoom()
                        }
                    }
                    "clicked" -> {
                        clickedOnce = true
                        elsewhereAfterClick = 0
                        if (++sinceClick == 8) {
                            // 点了预订却没进订单页：多半是 12306 弹出了登录框；登录后下面会把这句话换掉
                            note = "请先登录 12306；登录后会接着进入订单页"; covered = false
                            nudgeZoom()
                        }
                    }
                }
                if (reachedOrder || state == "soldout" || state == "notfound") break
            }
            if (!reachedOrder) break@outer   // 卖光了或没找到：到此为止，不用再看

            // 到了订单页之后，12306 自己偶尔会在加载乘车人信息时误判成没登录（哪怕其实登录着）；
            // 这里再看一会儿，一旦看到那个「用户未登录」的提示，就自动关掉、回查询页重新点一次预订（最多重试 2 次）
            var problem = false
            var tries = 0
            while (tries < 20) {
                tries++
                delay(500)
                val r = run(LOGIN_DIALOG_SCRIPT)
                if (r == "dismissed") {
                    runCatching { java.io.File(context.filesDir, "web.log").appendText("${System.currentTimeMillis() / 1000} dismissed-login-dialog\n") }
                    problem = true
                    break
                }
                if (r == "left") break
            }
            if (!problem) break@outer
            if (orderRetries >= 2) {
                note = "12306 多次提示未登录，请自己重新进一次网页购票"; covered = false
                break@outer
            }
            orderRetries++
            note = "12306 提示未登录，自动重试一次…"
            covered = true
            w.loadUrl(buy.url)
            delay(1000)
        }
    }
    BackHandler { val w = webView; if (w != null && w.canGoBack()) w.goBack() else onClose() }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().background(Blue).statusBarsPadding().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("‹ 返回", color = Color.White, fontSize = 15.sp) }
            Text(
                "12306 购票", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            TextButton(onClick = { open12306App(context) }) { Text("用App", color = Color.White, fontSize = 14.sp) }
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Orange)
        if (note.isNotEmpty() && !covered) Text(
            note, color = Orange, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().background(Orange.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 6.dp)
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // 调试版可以从电脑上用 chrome://inspect 看这个网页
                if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true          // 12306 网页需要
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true            // 按电脑版页面宽度排版，再缩放到屏幕宽度
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true        // 双指缩放
                    settings.displayZoomControls = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (u.scheme == "https") return false   // 网页内的跳转（包括付款页）照常加载
                            // 其他协议（比如拉起支付宝）交给系统处理；处理不了就忽略
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            return true
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                    }
                    webView = this
                    loadUrl(url)
                }
            },
            onRelease = { it.destroy() }
        )
        // 网页在后面自己走到订单页，这段时间先盖住
        if (covered) Column(
            Modifier.fillMaxSize().background(Color.White).clickable(enabled = false) {},
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(color = Blue)
            Spacer(Modifier.height(16.dp))
            Text("正在网页里打开 ${buy.trainNo} 的订单页…", fontSize = 15.sp)
            Text("只点「预订」，不选乘车人、不提交订单", color = Grey, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = { covered = false; webView?.let { it.zoomOut(); it.zoomIn() } }) { Text("直接看网页", color = Blue, fontSize = 13.sp) }
        }
        }
    }
}

/** 12306 官方登录页网址：跟「网页购票」用的是同一个 WebView 实例、同一份 cookie，提前在这里登录一次，
 *  后面点「网页购票」时就不会再弹登录框了。这个页面只负责把登录页打开，剩下的（账号、验证码、滑块）全是用户自己操作。 */
private const val LOGIN_URL = "https://kyfw.12306.cn/otn/resources/login.html"

/** 12306 登录后，页面右上角会有一句「您好， 张宝胜 退出」；从整页文字里把中间的名字抠出来，没有就是没登录。 */
private const val WHOAMI_SCRIPT = """
(function(){
  var m = document.body.innerText.match(/您好[，,]\s*([^\n，,]{1,20})\s*退出/);
  return m ? m[1].trim() : '';
})()
"""

@Composable
private fun LoginScreen(onClose: () -> Unit, onUserChanged: (String?) -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }

    // 每隔一会看一眼页面右上角是不是出现了「您好，XXX」，有就报给首页显示；用户自己退出登录了就报空
    LaunchedEffect(webView) {
        val w = webView ?: return@LaunchedEffect
        while (true) {
            delay(1200)
            val name = suspendCancellableCoroutine<String> { c ->
                w.evaluateJavascript(WHOAMI_SCRIPT) { if (c.isActive) c.resume(it.orEmpty().trim('"')) }
            }
            onUserChanged(name.ifEmpty { null })
        }
    }

    BackHandler { val w = webView; if (w != null && w.canGoBack()) w.goBack() else onClose() }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().background(Blue).statusBarsPadding().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("‹ 返回", color = Color.White, fontSize = 15.sp) }
            Text(
                "登录 12306", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            TextButton(onClick = onClose) { Text("完成", color = Color.White, fontSize = 14.sp) }
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Orange)
        Text(
            "在这里登录一次，之后用「网页购票」就不用再登录", color = Grey, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        )
        AndroidView(
            modifier = Modifier.fillMaxSize().weight(1f),
            factory = { ctx ->
                if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (u.scheme == "https") return false
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            return true
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                    }
                    webView = this
                    loadUrl(LOGIN_URL)
                }
            },
            onRelease = { it.destroy() }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 选站页（12306「车站选择」样式）：搜索、常用车站、热门车站、按拼音 A–Z 排的国内站点，右侧字母索引
// ─────────────────────────────────────────────────────────────

private val HotStationNames = listOf("北京", "上海", "广州", "天津", "重庆", "成都", "长沙", "哈尔滨", "杭州")

@Composable
private fun StationPickerScreen(recent: List<Station>, onSelected: (Station) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var query by remember { mutableStateOf("") }
    val results = remember(query) { if (query.isBlank()) emptyList() else StationData.search(query, 60) }
    val hot = remember { HotStationNames.mapNotNull { StationData.findByName(it) } }
    val grouped = remember {
        StationData.stations.groupBy { it.pinyin.firstOrNull()?.uppercaseChar() ?: '#' }.toSortedMap()
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().background(Blue).statusBarsPadding().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", color = Color.White, fontSize = 15.sp) }
            Text(
                "车站选择", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(72.dp))
        }
        TextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("请输入（如：北京市/北京站/beijing/bj）", fontSize = 14.sp, color = Color(0xFF9AA5B3)) },
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Bg, unfocusedContainerColor = Bg,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
        )

        if (query.isNotBlank()) {
            if (results.isEmpty()) Text("没有找到「$query」", color = Grey, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.code }) { st ->
                    Column(Modifier.fillMaxWidth().clickable { onSelected(st) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(st.name, fontSize = 16.sp)
                        Text(st.code, color = Grey, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = Line)
                }
            }
        } else {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val headerCount = (if (recent.isNotEmpty()) 1 else 0) + 2
            val letterIndex = remember(grouped, headerCount) {
                var idx = headerCount
                grouped.entries.associate { (letter, list) -> letter to idx.also { idx += 1 + list.size } }
            }
            val labels = remember(grouped) { listOf("常", "热") + grouped.keys.map { it.toString() } }
            fun target(label: String): Int = when (label) {
                "常" -> 0
                "热" -> if (recent.isNotEmpty()) 1 else 0
                else -> letterIndex[label.first()] ?: 0
            }

            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(end = 28.dp)) {
                    if (recent.isNotEmpty()) item { StationChipGroup("常用车站", recent, onSelected) }
                    item { StationChipGroup("热门车站", hot, onSelected) }
                    item {
                        Column(Modifier.padding(start = 16.dp, top = 12.dp)) {
                            Text("国内站点", color = Blue, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.width(36.dp).height(3.dp).background(Blue))
                        }
                        HorizontalDivider(color = Line)
                    }
                    grouped.forEach { (letter, list) ->
                        item { Text(letter.toString(), color = Grey, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
                        items(list, key = { it.code }) { st ->
                            Text(
                                st.name, fontSize = 16.sp,
                                modifier = Modifier.fillMaxWidth().clickable { onSelected(st) }.padding(horizontal = 16.dp, vertical = 13.dp)
                            )
                            HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
                // 右侧字母索引：点或拖动跳转
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)
                        .pointerInput(labels) {
                            fun jump(y: Float) {
                                val i = (y / size.height * labels.size).toInt().coerceIn(0, labels.lastIndex)
                                scope.launch { listState.scrollToItem(target(labels[i])) }
                            }
                            detectTapGestures { jump(it.y) }
                        }
                        .pointerInput(labels) {
                            fun jump(y: Float) {
                                val i = (y / size.height * labels.size).toInt().coerceIn(0, labels.lastIndex)
                                scope.launch { listState.scrollToItem(target(labels[i])) }
                            }
                            detectVerticalDragGestures { change, _ -> jump(change.position.y) }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    labels.forEach { Text(it, color = Blue, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StationChipGroup(title: String, stations: List<Station>, onSelected: (Station) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        stations.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { st ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Bg)
                            .clickable { onSelected(st) }.padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(st.name, fontSize = 15.sp, maxLines = 1) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

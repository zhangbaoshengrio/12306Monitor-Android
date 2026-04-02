package com.wizpizz.ticket12306.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wizpizz.ticket12306.model.Station
import com.wizpizz.ticket12306.model.StationData
import com.wizpizz.ticket12306.model.TrainTicket

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val isRunning by vm.isRunning.collectAsState()
    val isQuerying by vm.isQuerying.collectAsState()
    val queryResult by vm.queryResult.collectAsState()
    val queryLog by vm.queryLog.collectAsState()
    val foundTickets by vm.foundTickets.collectAsState()
    val errorMsg by vm.errorMsg.collectAsState()

    val boardInput by vm.boardStationInput.collectAsState()
    val destInput by vm.destStationInput.collectAsState()
    val dateInput by vm.dateInput.collectAsState()
    val cookieInput by vm.cookieInput.collectAsState()
    val intervalInput by vm.intervalInput.collectAsState()
    val boardSelected by vm.boardStationSelected.collectAsState()
    val destSelected by vm.destStationSelected.collectAsState()

    val context = LocalContext.current
    var showBoardPicker by remember { mutableStateOf(false) }
    var showDestPicker by remember { mutableStateOf(false) }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookie = result.data?.getStringExtra(EXTRA_COOKIE) ?: ""
            if (cookie.isNotBlank()) vm.cookieInput.value = cookie
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("12306 余票查询") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── 错误提示 ──────────────────────────────────────────
            errorMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            // ── 经过站选择 ────────────────────────────────────────
            OutlinedTextField(
                value = boardSelected?.let { "${it.name} (${it.code})" } ?: boardInput,
                onValueChange = {
                    vm.boardStationInput.value = it
                    vm.boardStationSelected.value = null
                },
                label = { Text("经过站（你上车的站）") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = { showBoardPicker = true }) { Text("选择") }
                },
                enabled = !isRunning,
                singleLine = true
            )

            // ── 终点站选择 ────────────────────────────────────────
            OutlinedTextField(
                value = destSelected?.let { "${it.name} (${it.code})" } ?: destInput,
                onValueChange = {
                    vm.destStationInput.value = it
                    vm.destStationSelected.value = null
                },
                label = { Text("终点站（最终目的地）") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = { showDestPicker = true }) { Text("选择") }
                },
                enabled = !isRunning,
                singleLine = true
            )

            // ── 日期 ──────────────────────────────────────────────
            OutlinedTextField(
                value = dateInput,
                onValueChange = { vm.dateInput.value = it },
                label = { Text("日期 (yyyy-MM-dd)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRunning,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // ── Cookie ────────────────────────────────────────────
            OutlinedTextField(
                value = cookieInput,
                onValueChange = { vm.cookieInput.value = it },
                label = { Text("12306 Cookie") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                enabled = !isRunning,
                maxLines = 4,
                placeholder = { Text("JSESSIONID=...; tk=...", fontSize = 12.sp) }
            )
            if (!isRunning) {
                OutlinedButton(
                    onClick = {
                        loginLauncher.launch(
                            Intent(context, LoginActivity::class.java)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("登录 12306 自动获取 Cookie")
                }
            }

            // ── 轮询间隔 ──────────────────────────────────────────
            OutlinedTextField(
                value = intervalInput,
                onValueChange = { vm.intervalInput.value = it },
                label = { Text("轮询间隔（秒，建议 8~15）") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRunning,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // ── 操作按钮 ──────────────────────────────────────────
            val busy = isRunning || isQuerying
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.queryOnce() },
                    modifier = Modifier.weight(1f).height(50.dp),
                    enabled = !busy
                ) {
                    if (isQuerying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("查询", fontSize = 16.sp)
                    }
                }
                OutlinedButton(
                    onClick = { if (isRunning) vm.stopMonitor() else vm.startMonitor() },
                    modifier = Modifier.weight(1f).height(50.dp),
                    enabled = !isQuerying
                ) {
                    Text(if (isRunning) "停止监控" else "持续监控", fontSize = 15.sp)
                }
            }

            // ── 查询日志 ──────────────────────────────────────────
            if (queryLog.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        queryLog.forEach { line ->
                            Text(line, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isQuerying) {
                            Text("查询中...", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // ── 查询结果 ──────────────────────────────────────────
            val displayTickets = if (isRunning) foundTickets else queryResult ?: emptyList()
            val hasQueried = queryResult != null || isRunning

            if (hasQueried) {
                Divider()
                if (displayTickets.isEmpty() && !isQuerying) {
                    Text(
                        "未查到任何车次",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp
                    )
                } else if (displayTickets.isNotEmpty()) {
                    val withTickets = displayTickets.count { it.seats.isNotEmpty() }
                    val summary = if (withTickets > 0)
                        "共 ${displayTickets.size} 个区间  ★ 有票 $withTickets 个"
                    else
                        "共 ${displayTickets.size} 个区间，均无余票"
                    Text(
                        summary,
                        color = if (withTickets > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    displayTickets.forEach { ticket -> TicketCard(ticket) }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ── 站名选择弹窗 ──────────────────────────────────────────────
    if (showBoardPicker) {
        StationPickerDialog(
            query = boardInput,
            onQueryChange = { vm.boardStationInput.value = it },
            onSelected = {
                vm.boardStationSelected.value = it
                showBoardPicker = false
            },
            onDismiss = { showBoardPicker = false }
        )
    }
    if (showDestPicker) {
        StationPickerDialog(
            query = destInput,
            onQueryChange = { vm.destStationInput.value = it },
            onSelected = {
                vm.destStationSelected.value = it
                showDestPicker = false
            },
            onDismiss = { showDestPicker = false }
        )
    }
}

@Composable
fun TicketCard(ticket: TrainTicket) {
    val context = LocalContext.current
    val hasTickets = ticket.seats.isNotEmpty()
    val cardColor = if (hasTickets) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // ── 车次 + 区间 + 时间 ─────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ticket.trainNo,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = if (hasTickets) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${ticket.fromStation} → ${ticket.toStation}",
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${ticket.departTime}  ${ticket.arriveTime}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            // ── 余票状态 ──────────────────────────────────────────
            if (hasTickets) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    ticket.seats.forEach { (type, count) ->
                        val price = ticket.prices[type]
                        val label = if (price != null) "$type $count · $price" else "$type $count"
                        SuggestionChip(
                            onClick = {},
                            label = { Text(label, fontSize = 11.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF4CAF50),
                                labelColor = Color.White
                            )
                        )
                    }
                }
                // 有票但价格里还有其他席别价格（那些席别已售完）
                if (ticket.prices.isNotEmpty()) {
                    val soldOutPrices = ticket.prices.filter { (k, _) -> k !in ticket.seats }
                    if (soldOutPrices.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            soldOutPrices.forEach { (type, price) ->
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("$type 无票 · $price", fontSize = 11.sp) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
                // 无票，只显示价格供参考
                if (ticket.prices.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ticket.prices.forEach { (type, price) ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text("$type 无票 · $price", fontSize = 11.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        }
                    }
                } else {
                    Text("无票", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── 购票按钮（有票才显示）─────────────────────────────
            if (hasTickets) {
                Button(
                    onClick = { open12306(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("去购票", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

/** 优先打开 12306 APP，若未安装则打开网页 */
private fun open12306(context: android.content.Context) {
    val appPackage = "com.MobileTicket"
    val intent = try {
        context.packageManager.getLaunchIntentForPackage(appPackage)
            ?: throw PackageManager.NameNotFoundException()
    } catch (e: Exception) {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://kyfw.12306.cn/otn/leftTicket/init"))
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationPickerDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    onSelected: (Station) -> Unit,
    onDismiss: () -> Unit
) {
    val results = remember(query) { StationData.search(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择站点") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("输入站名搜索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(results) { station ->
                        ListItem(
                            headlineContent = { Text(station.name) },
                            supportingContent = { Text(station.code, color = Color.Gray, fontSize = 12.sp) },
                            modifier = Modifier.clickable { onSelected(station) }
                        )
                        Divider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

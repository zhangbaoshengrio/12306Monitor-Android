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
            TopAppBar(title = { Text("12306 余票监控") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── 状态卡片 ──────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRunning) "● 监控运行中" else "○ 未在监控",
                        color = if (isRunning) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (isRunning) {
                        TextButton(onClick = { vm.stopMonitor() }) {
                            Text("停止", color = Color.White)
                        }
                    }
                }
            }

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

            // ── 开始按钮 ──────────────────────────────────────────
            if (!isRunning) {
                Button(
                    onClick = { vm.startMonitor() },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("开始监控", fontSize = 16.sp)
                }
            }

            // ── 发现的余票列表 ────────────────────────────────────
            if (foundTickets.isNotEmpty()) {
                Divider()
                Text(
                    "★ 发现余票 ${foundTickets.size} 个区间",
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                foundTickets.forEach { ticket ->
                    TicketCard(ticket)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ticket.trainNo,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF1B5E20)
                )
                Spacer(Modifier.width(12.dp))
                Text("${ticket.fromStation} → ${ticket.toStation}", fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("${ticket.departTime} → ${ticket.arriveTime}", fontSize = 12.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ticket.seats.forEach { (type, count) ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text("$type: $count", fontSize = 12.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF4CAF50),
                                labelColor = Color.White
                            )
                        )
                    }
                }
                Button(
                    onClick = { open12306(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
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

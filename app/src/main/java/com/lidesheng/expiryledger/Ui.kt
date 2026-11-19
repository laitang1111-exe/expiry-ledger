package com.lidesheng.expiryledger

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val df: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** 顶部四个可点的阶段卡 */
private val STAGE_RANGES = listOf(7, 10, 20, 30)

/**
 * 分段归属（互斥，不叠加）：
 *   ≤7      → 7（含已过期、今天到期）
 *   8..10   → 10
 *   11..20  → 20
 *   21..30  → 30
 *   >30     → null（正常）
 * 一条记录只会落在一个段里，不会同时出现在 10 天和 20 天。
 */
private fun bucketOf(remainingDays: Long): Int? = when {
    remainingDays <= 7 -> 7
    remainingDays <= 10 -> 10
    remainingDays <= 20 -> 20
    remainingDays <= 30 -> 30
    else -> null
}

private fun bucketTitle(days: Int): String = when (days) {
    7 -> "7 天内（含已过期）"
    10 -> "8~10 天"
    20 -> "11~20 天"
    else -> "21~30 天"
}

// ═══════════════════════ 首页 ═══════════════════════

@Composable
private fun EditBatchDialog(
    batch: Batch,
    repo: LedgerRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onCaptureClick: () -> Unit = {},
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(batch.name) }
    var production by remember { mutableStateOf(batch.productionDate) }
    var valueText by remember { mutableStateOf(batch.shelfLifeValue.toString()) }
    var unit by remember { mutableStateOf(batch.unit) }
    var error by remember { mutableStateOf<String?>(null) }

    val v = valueText.toIntOrNull()
    val preview = if (v != null && v > 0) Dates.computeExpiry(production, v, unit) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑这一批") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("商品名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "条码 ${batch.barcode}　（改名会同步到同条码的所有批次）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("生产日期", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(production.format(df), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            DatePickerDialog(
                                context,
                                { _, y, m, d -> production = LocalDate.of(y, m + 1, d) },
                                production.year, production.monthValue - 1, production.dayOfMonth
                            ).show()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) { Text("改日期", fontSize = 13.sp) }

                    TextButton(
                        onClick = onCaptureClick,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) { Text("拍日期", fontSize = 13.sp) }
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = valueText,
                        onValueChange = { t -> valueText = t.filter { it.isDigit() }.take(4) },
                        label = { Text("保质期") },
                        modifier = Modifier.width(110.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.width(8.dp))
                    for (u in LifeUnit.entries) {
                        FilterChip(
                            selected = unit == u,
                            onClick = { unit = u },
                            label = { Text(u.label) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }

                if (preview != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "到期 ${preview.format(df)}　${Dates.formatRemaining(Dates.remainingDays(preview))}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (v == null || v <= 0) {
                    error = "保质期数字不正确"
                    return@TextButton
                }
                if (repo.existsSameBatch(batch.barcode, production, v, unit, batch.id)) {
                    error = "该条码已有同批日期，添加失败！"
                    return@TextButton
                }
                repo.updateBatch(batch.id, name.trim().ifBlank { "未命名商品" }, production, v, unit)
                onSaved()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: LedgerRepository,
    version: Int,
    notice: String?,
    onNoticeShown: () -> Unit,
    onAdd: () -> Unit,
    onTrash: () -> Unit,
    onProcessed: (Long) -> Unit,
    onChanged: () -> Unit,
    onOpenStage: (Int) -> Unit,
    onCaptureDate: (Batch) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val today = LocalDate.now()
    val all = remember(version) { repo.listActive() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        if (!notice.isNullOrBlank()) {
            snackbar.showSnackbar(notice)
            onNoticeShown()
        }
    }

    val counts = remember(version) {
        STAGE_RANGES.associateWith { d ->
            all.count { bucketOf(Dates.remainingDays(it.expiryDate, today)) == d }
        }
    }

    // 首页只显示正常（30 天以上）
    val normal = remember(version) {
        all.filter { bucketOf(Dates.remainingDays(it.expiryDate, today)) == null }
            .sortedBy { it.expiryDate }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("保质期台账") },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = onTrash) {
                        Icon(Icons.Filled.Delete, contentDescription = "回收站")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "录入")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StageCard(7, counts[7] ?: 0, Color(0xFFFF9800), Modifier.weight(1f)) { onOpenStage(7) }
                    StageCard(10, counts[10] ?: 0, Color(0xFFFFB300), Modifier.weight(1f)) { onOpenStage(10) }
                    StageCard(20, counts[20] ?: 0, Color(0xFFFFD54F), Modifier.weight(1f)) { onOpenStage(20) }
                    StageCard(30, counts[30] ?: 0, Color(0xFF42A5F5), Modifier.weight(1f)) { onOpenStage(30) }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF9E9E9E).copy(alpha = 0.14f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).background(Color(0xFF9E9E9E), MaterialTheme.shapes.small))
                    Spacer(Modifier.width(8.dp))
                    Text("正常（30 天以上）", fontWeight = FontWeight.Bold, color = Color(0xFF757575))
                    Spacer(Modifier.width(8.dp))
                    Text("${normal.size} 批", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }

            items(normal, key = { it.id }) { batch ->
                BatchRow(
                    batch = batch,
                    today = today,
                    repo = repo,
                    onChanged = onChanged,
                    onProcessed = { onProcessed(batch.id) },
                    onCaptureDate = onCaptureDate,
                )
                HorizontalDivider()
            }

            if (all.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text("还没有记录，点右下角 + 开始录入", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else if (normal.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("没有 30 天以上的批次，全部都在上面的阶段里", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StageCard(days: Int, count: Int, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable { onClick() }) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(count.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
            Text("$days 天内", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun BatchRow(
    batch: Batch,
    today: LocalDate,
    repo: LedgerRepository,
    onChanged: () -> Unit,
    onProcessed: () -> Unit,
    onCaptureDate: (Batch) -> Unit = {},
) {
    val remain = Dates.remainingDays(batch.expiryDate, today)
    val stage = Stage.of(remain)
    var confirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { editing = true }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(batch.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(batch.barcode, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(2.dp))
            Text(
                "生产 ${batch.productionDate.format(df)} → 到期 ${batch.expiryDate.format(df)}" +
                    "　（点这一行可改）",
                fontSize = 12.sp
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Dates.formatRemaining(remain),
                color = Color(stage.colorArgb),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Dates.approximateMonths(remain)?.let { approx ->
                Text(
                    approx,
                    color = Color(stage.colorArgb).copy(alpha = 0.75f),
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { confirm = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) { Text("已处理", fontSize = 12.sp) }
        }
    }

    if (editing) {
        EditBatchDialog(
            batch = batch,
            repo = repo,
            onDismiss = { editing = false },
            onSaved = {
                editing = false
                onChanged()
            },
            onCaptureClick = {
                editing = false
                onCaptureDate(batch)
            },
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确认标记为已处理？") },
            text = {
                Text(
                    "${batch.name}\n" +
                        "生产 ${batch.productionDate.format(df)} → 到期 ${batch.expiryDate.format(df)}\n" +
                        Dates.formatRemaining(remain) + "\n\n" +
                        "标记后这条会移进回收站，之后可以在回收站里恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onProcessed()
                }) { Text("确认处理") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("取消") }
            }
        )
    }
}

// ═══════════════════════ 阶段详情（点顶部卡片进入） ═══════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageListScreen(
    repo: LedgerRepository,
    days: Int,
    version: Int,
    onBack: () -> Unit,
    onProcessed: (Long) -> Unit,
    onChanged: () -> Unit,
    onCaptureDate: (Batch) -> Unit,
) {
    val today = LocalDate.now()
    val list = remember(version, days) {
        repo.listActive()
            .filter { bucketOf(Dates.remainingDays(it.expiryDate, today)) == days }
            .sortedBy { it.expiryDate }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${bucketTitle(days)}到期（${list.size} 批）") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("这个阶段没有商品", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(list, key = { it.id }) { b ->
                    BatchRow(
                        batch = b,
                        today = today,
                        repo = repo,
                        onChanged = onChanged,
                        onProcessed = { onProcessed(b.id) },
                        onCaptureDate = onCaptureDate,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ═══════════════════════ 搜索 ═══════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repo: LedgerRepository,
    version: Int,
    onBack: () -> Unit,
    onProcessed: (Long) -> Unit,
    onChanged: () -> Unit,
    onCaptureDate: (Batch) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val results = remember(query, version) { repo.search(query) }
    val today = LocalDate.now()

    // 搜索页只扫条码：扫到什么就把什么填进搜索框，立刻出结果
    if (scanning) {
        CameraCaptureScreen(
            mode = CaptureMode.BARCODE,
            onBarcode = { code ->
                query = code
                scanning = false
            },
            onText = { },
            onClose = { scanning = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("条码 / 名称 / 日期（如 2026-11 或 2026-11-25）") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            TextButton(onClick = { query = "" }) { Text("清空") }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { scanning = true },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                ) {
                    Text("扫码")
                }
            }

            Text(
                if (query.isBlank()) "全部 ${results.size} 批" else "找到 ${results.size} 批",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            if (results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "还没有记录" else "没有匹配的批次",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.id }) { b ->
                        BatchRow(
                            batch = b,
                            today = today,
                            repo = repo,
                            onChanged = onChanged,
                            onProcessed = { onProcessed(b.id) },
                            onCaptureDate = onCaptureDate,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ═══════════════════════ 录入 ═══════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(
    repo: LedgerRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDuplicate: () -> Unit,
    autoScan: Boolean = false,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var barcode by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var production by remember { mutableStateOf(LocalDate.now()) }
    var valueText by remember { mutableStateOf("6") }
    var unit by remember { mutableStateOf(LifeUnit.MONTH) }
    var knownInfo by remember { mutableStateOf<BarcodeInfo?>(null) }
    var camera by remember { mutableStateOf<CaptureMode?>(null) }
    var nameCandidates by remember { mutableStateOf<List<String>?>(null) }

    // 返回键两段式：第一次提示，2 秒内再按一次才退出到主页（防止误触丢草稿）
    var lastBackAt by remember { mutableStateOf(0L) }
    androidx.activity.compose.BackHandler(enabled = true) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackAt <= 2000L) {
            onBack()
        } else {
            lastBackAt = now
            scope.launch { snackbar.showSnackbar("再按一次返回退出到主页（草稿会丢失）") }
        }
    }

    // 从首页加号进来：先直接进扫码，扫到的条码填进这份草稿
    var autoScanDone by remember { mutableStateOf(!autoScan) }
    LaunchedEffect(Unit) {
        if (!autoScanDone) {
            autoScanDone = true
            camera = CaptureMode.BARCODE
        }
    }

    fun applyBarcode(code: String) {
        barcode = code
        val info = repo.findBarcode(code)
        knownInfo = info
        if (info != null) {
            if (name.isBlank()) name = info.name
            info.lastValue?.let { valueText = it.toString() }
            info.lastUnit?.let { unit = it }
        }
    }

    val preview = Dates.computeExpiry(production, valueText.toIntOrNull() ?: 1, unit)

    if (camera != null) {
        CameraCaptureScreen(
            mode = camera!!,
            onBarcode = { code ->
                applyBarcode(code)
                camera = null
            },
            onText = { raw ->
                val ex = TextExtract.extract(raw)
                val today = LocalDate.now()
                val rawDate = ex.productionDate
                // 生产日期必须早于今天，否则丢弃
                val okDate = rawDate?.takeIf { it.isBefore(today) }
                okDate?.let { production = it }
                ex.value?.let { valueText = it.toString() }
                ex.unit?.let { unit = it }
                camera = null
                val msg = when {
                    okDate != null -> "识别到生产日期 ${okDate.format(df)}"
                    rawDate != null -> "识别到 ${rawDate.format(df)}，不早于今天，已丢弃"
                    else -> "没识别出日期，已保留原文，请手动填写"
                }
                scope.launch { snackbar.showSnackbar(msg) }
            },
            onClose = { camera = null },
            onCandidates = { cands ->
                nameCandidates = cands
                camera = null
            },
        )
        return
    }

    val cands = nameCandidates
    if (cands != null) {
        AlertDialog(
            onDismissRequest = { nameCandidates = null },
            title = { Text("选择商品名称") },
            text = {
                Column {
                    Text(
                        "按字号从大到小排的候选，点一个填入：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(6.dp))
                    cands.forEach { c ->
                        TextButton(
                            onClick = { name = c; nameCandidates = null },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(c, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    nameCandidates = null
                    camera = CaptureMode.NAME
                }) { Text("重试") }
            },
            dismissButton = {
                TextButton(onClick = { nameCandidates = null }) { Text("都不对，手动输") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("录入一批") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = barcode,
                onValueChange = { applyBarcode(it.trim()) },
                label = { Text("条码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    TextButton(onClick = { camera = CaptureMode.BARCODE }) { Text("扫码") }
                }
            )

            if (knownInfo != null) {
                Text(
                    "上次：${knownInfo!!.name}　${knownInfo!!.lastValue ?: ""} ${knownInfo!!.lastUnit?.label ?: ""}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("商品名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { camera = CaptureMode.NAME }) { Text("拍名称") }
                }
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("生产日期", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            production.format(df),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            DatePickerDialog(
                                context,
                                { _, y, m, d -> production = LocalDate.of(y, m + 1, d) },
                                production.year, production.monthValue - 1, production.dayOfMonth
                            ).show()
                        }) { Text("改日期") }
                        TextButton(onClick = { camera = CaptureMode.TEXT }) { Text("拍日期") }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { t -> valueText = t.filter { it.isDigit() }.take(4) },
                    label = { Text("保质期") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("单位", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Row {
                        for (u in LifeUnit.entries) {
                            FilterChip(
                                selected = unit == u,
                                onClick = { unit = u },
                                label = { Text(u.label) },
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("预估到期日", fontSize = 12.sp)
                    Text(preview.format(df), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    val remain = Dates.remainingDays(preview)
                    Text(Dates.formatRemaining(remain), fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val v = valueText.toIntOrNull()
                    if (barcode.isBlank()) {
                        scope.launch { snackbar.showSnackbar("请先填写或扫描条码") }
                        return@Button
                    }
                    if (v == null || v <= 0) {
                        scope.launch { snackbar.showSnackbar("保质期数字不正确") }
                        return@Button
                    }
                    // 条码 + 生产日期 + 保质期 都一样（商品名不算）→ 视为同一批，拒绝重复添加
                    if (repo.existsSameBatch(barcode, production, v, unit)) {
                        onDuplicate()
                        return@Button
                    }
                    val finalName = name.ifBlank { "未命名商品" }
                    repo.addBatch(barcode, finalName, production, v, unit)
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("保存（新增一批）") }
        }
    }
}

// ═══════════════════════ 回收站 ═══════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    repo: LedgerRepository,
    version: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val list = remember(version) { repo.listTrash() }
    var pendingDelete by remember { mutableStateOf<Batch?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站（已处理）") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("还没有处理过的记录", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(list, key = { it.id }) { b ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name, fontWeight = FontWeight.SemiBold)
                            Text(b.barcode, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                            Text(
                                "生产 ${b.productionDate.format(df)} → 到期 ${b.expiryDate.format(df)}",
                                fontSize = 12.sp
                            )
                        }
                        IconButton(onClick = {
                            repo.restore(b.id)
                            onChanged()
                        }) { Icon(Icons.Filled.Refresh, contentDescription = "恢复") }

                        IconButton(onClick = { pendingDelete = b }) {
                            Icon(Icons.Filled.Delete, contentDescription = "彻底删除")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("彻底删除这条记录？") },
            text = {
                Text(
                    "${target.name}\n" +
                        "生产 ${target.productionDate.format(df)} → 到期 ${target.expiryDate.format(df)}\n\n" +
                        "彻底删除后无法恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.hardDelete(target.id)
                    pendingDelete = null
                    onChanged()
                }) { Text("彻底删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

// ═══════════════════════ 设置（权限 / 导出 / 导入） ═══════════════════════

private fun backupDir(): java.io.File =
    java.io.File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        "保质期台账"
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: LedgerRepository,
    version: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var hasAllFiles by remember { mutableStateOf(android.os.Environment.isExternalStorageManager()) }

    val all = remember(version) { repo.listAll() }
    val activeCount = all.count { it.deletedAt == null }
    val trashCount = all.size - activeCount

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasAllFiles = android.os.Environment.isExternalStorageManager()
        status = if (hasAllFiles) "已获得全部文件访问权限" else "仍未获得权限（可以稍后再申请）"
    }

    fun requestAllFiles() {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + context.packageName)
            )
            permLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                permLauncher.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e: Exception) {
                status = "这台手机打不开权限页，请到系统设置 → 应用 → 保质期管理器 → 权限里手动开"
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) { Backup.exportJson(repo) }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(text.toByteArray(Charsets.UTF_8))
                        }
                    }
                    status = "导出成功：${all.size} 条（含回收站 $trashCount 条）"
                } catch (e: Exception) {
                    status = "导出失败：${e.message}"
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    }
                    val r = withContext(Dispatchers.IO) { Backup.importJson(repo, text) }
                    status = "导入完成：新增 ${r.inserted} 条，跳过重复 ${r.skipped} 条，无效 ${r.failed} 条"
                    onChanged()
                } catch (e: Exception) {
                    status = "导入失败：${e.message}"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("当前数据", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(6.dp))
                    Text("清单 $activeCount 条　回收站 $trashCount 条　合计 ${all.size} 条", fontSize = 16.sp)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("文件权限", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (hasAllFiles) "已获得「所有文件访问权限」" else "未获得：只能通过系统文件选择器导出/导入",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(10.dp))
                    if (!hasAllFiles) {
                        Button(onClick = { requestAllFiles() }, modifier = Modifier.fillMaxWidth()) {
                            Text("申请全部文件访问权限")
                        }
                    } else {
                        Text(
                            "备份目录：Download/保质期台账/",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val name = "保质期台账_" + LocalDate.now().toString() + ".json"
                    exportLauncher.launch(name)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) { Text("导出到文件（自己选位置）") }

            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) { Text("从文件导入（自己选文件）") }

            if (hasAllFiles) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val text = withContext(Dispatchers.IO) { Backup.exportJson(repo) }
                                val f = withContext(Dispatchers.IO) {
                                    val dir = backupDir()
                                    dir.mkdirs()
                                    val file = java.io.File(dir, "保质期台账_" + LocalDate.now().toString() + ".json")
                                    file.writeText(text, Charsets.UTF_8)
                                    file
                                }
                                status = "已写入：${f.absolutePath}"
                            } catch (e: Exception) {
                                status = "写入失败：${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("快速导出到 Download/保质期台账/") }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    val dir = backupDir()
                                    dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                                        ?.maxByOrNull { it.lastModified() }
                                }
                                if (file == null) {
                                    status = "Download/保质期台账/ 里还没有备份文件"
                                } else {
                                    val text = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
                                    val r = withContext(Dispatchers.IO) { Backup.importJson(repo, text) }
                                    status = "已导入 ${file.name}：新增 ${r.inserted}，跳过 ${r.skipped}，无效 ${r.failed}"
                                    onChanged()
                                }
                            } catch (e: Exception) {
                                status = "导入失败：${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("从该目录导入最新备份") }
            }

            if (status.isNotBlank()) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Text(status, modifier = Modifier.padding(14.dp), fontSize = 13.sp)
                }
            }

            Text(
                "说明：\n" +
                    "· 导出的 JSON 含清单和回收站的全部记录\n" +
                    "· 导入是合并式：同条码 + 同生产日期 + 同保质期会跳过\n" +
                    "· 全部文件访问权限只用于把自己写进 Download 目录，不改动其它应用的文件",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

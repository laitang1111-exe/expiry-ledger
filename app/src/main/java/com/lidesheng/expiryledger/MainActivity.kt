package com.lidesheng.expiryledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

sealed interface Screen {
    data object Home : Screen
    data object Entry : Screen
    data object Trash : Screen
    data object Search : Screen
    data object Settings : Screen
    data class StageList(val days: Int) : Screen

    /** 给某一批拍日期改生产日期；returnStage = null 表示回首页 */
    data class OcrEdit(val batchId: Long, val returnStage: Int?) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    AppRoot()
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val repo = remember { LedgerRepository(context) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var version by remember { mutableIntStateOf(0) }
    var homeNotice by remember { mutableStateOf<String?>(null) }
    val backupScope = rememberCoroutineScope()

    // 任何一次保存动作后：刷新界面 + 往 /sdcard/赏味期/ 拍一份快照
    fun afterChange(action: String) {
        version++
        backupScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            AutoBackup.snapshot(repo, action)
        }
    }

    // 系统返回键：不在首页时回首页；键盘弹着时先交给系统收键盘
    val imeVisible = WindowInsets.isImeVisible
    androidx.activity.compose.BackHandler(enabled = screen != Screen.Home && !imeVisible) {
        screen = Screen.Home
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            repo = repo,
            version = version,
            notice = homeNotice,
            onNoticeShown = { homeNotice = null },
            onAdd = { screen = Screen.Entry },
            onTrash = { screen = Screen.Trash },
            onProcessed = { id ->
                repo.markProcessed(id)
                afterChange("标记已处理")
            },
            onChanged = { afterChange("编辑保存") },
            onOpenStage = { days -> screen = Screen.StageList(days) },
            onCaptureDate = { b -> screen = Screen.OcrEdit(b.id, null) },
            onSearch = { screen = Screen.Search },
            onSettings = { screen = Screen.Settings },
        )

        is Screen.StageList -> StageListScreen(
            repo = repo,
            days = s.days,
            version = version,
            onBack = { screen = Screen.Home },
            onProcessed = { id ->
                repo.markProcessed(id)
                afterChange("标记已处理")
            },
            onChanged = { afterChange("编辑保存") },
            onCaptureDate = { b -> screen = Screen.OcrEdit(b.id, s.days) },
        )

        is Screen.OcrEdit -> {
            CameraCaptureScreen(
                mode = CaptureMode.TEXT,
                onBarcode = { },
                onText = { raw ->
                    val ex = TextExtract.extract(raw)
                    val parsed = InfoParser.parse(raw)
                    val today = java.time.LocalDate.now()
                    var rawDate = ex.productionDate
                    var how = "识别到"
                    // 只拍到“保质期至某日”：用这条批次自己的保质期反推生产日期
                    if (rawDate == null) {
                        val expiry = parsed.expiryDate
                        val batch = repo.findBatch(s.batchId)
                        if (expiry != null && batch != null) {
                            val derived = Dates.productionFromExpiry(expiry, batch.shelfLifeValue, batch.unit)
                            if (derived.year >= 1990) {
                                rawDate = derived
                                how = "按保质期至 $expiry 反推"
                            }
                        }
                    }
                    if (rawDate != null && rawDate.isBefore(today)) {
                        repo.updateProductionDate(s.batchId, rawDate)
                        homeNotice = "$how $rawDate"
                        afterChange("拍日期改生产日期")
                        screen = if (s.returnStage == null) Screen.Home else Screen.StageList(s.returnStage)
                        null
                    } else if (rawDate != null) {
                        "识别到 $rawDate，不早于今天（$today），请移动摄像头换个日期"
                    } else {
                        "没识别出日期，请移动摄像头对准生产日期或保质期至"
                    }
                },
                onClose = {
                    screen = if (s.returnStage == null) Screen.Home else Screen.StageList(s.returnStage)
                },
            )
        }

        Screen.Search -> SearchScreen(
            repo = repo,
            version = version,
            onBack = { screen = Screen.Home },
            onProcessed = { id ->
                repo.markProcessed(id)
                afterChange("标记已处理")
            },
            onChanged = { afterChange("编辑保存") },
            onCaptureDate = { b -> screen = Screen.OcrEdit(b.id, null) },
        )

        Screen.Settings -> SettingsScreen(
            repo = repo,
            version = version,
            onBack = { screen = Screen.Home },
            onChanged = { afterChange("导入") },
        )

        Screen.Entry -> EntryScreen(
            repo = repo,
            autoScan = true,
            onBack = { screen = Screen.Home },
            onSaved = {
                afterChange("新增批次")
                screen = Screen.Home
            },
            onDuplicate = {
                homeNotice = "该条码已有同批日期，添加失败！"
                version++
                screen = Screen.Home
            },
        )

        Screen.Trash -> TrashScreen(
            repo = repo,
            version = version,
            onBack = { screen = Screen.Home },
            onChanged = { afterChange("回收站操作") },
        )
    }
}



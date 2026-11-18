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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

sealed interface Screen {
    data object Home : Screen
    data object Entry : Screen
    data object Trash : Screen
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
                version++
            },
            onChanged = { version++ },
            onOpenStage = { days -> screen = Screen.StageList(days) },
            onCaptureDate = { b -> screen = Screen.OcrEdit(b.id, null) },
        )

        is Screen.StageList -> StageListScreen(
            repo = repo,
            days = s.days,
            version = version,
            onBack = { screen = Screen.Home },
            onProcessed = { id ->
                repo.markProcessed(id)
                version++
            },
            onChanged = { version++ },
            onCaptureDate = { b -> screen = Screen.OcrEdit(b.id, s.days) },
        )

        is Screen.OcrEdit -> {
            CameraCaptureScreen(
                mode = CaptureMode.TEXT,
                onBarcode = { },
                onText = { raw ->
                    val ex = TextExtract.extract(raw)
                    val date = ex.productionDate
                    if (date != null) {
                        repo.updateProductionDate(s.batchId, date)
                        homeNotice = "已把生产日期改为 ${date}"
                    } else {
                        homeNotice = "没识别出日期，请手动改"
                    }
                    version++
                    screen = if (s.returnStage == null) Screen.Home else Screen.StageList(s.returnStage)
                },
                onClose = {
                    screen = if (s.returnStage == null) Screen.Home else Screen.StageList(s.returnStage)
                },
            )
        }

        Screen.Entry -> EntryScreen(
            repo = repo,
            autoScan = true,
            onBack = { screen = Screen.Home },
            onSaved = {
                version++
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
            onChanged = { version++ },
        )
    }
}

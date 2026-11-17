package com.lidesheng.expiryledger

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class CaptureMode { BARCODE, TEXT }

/**
 * 摄像头取景：BARCODE 模式扫条码，TEXT 模式做文字识别（读生产日期）。
 * 识别成功后回调并自动关闭。
 */
@Composable
fun CameraCaptureScreen(
    mode: CaptureMode,
    onBarcode: (String) -> Unit,
    onText: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("需要相机权限才能扫码/拍照")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("授予权限") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClose) { Text("返回手输") }
            }
        }
        return
    }

    val done = remember { AtomicBoolean(false) }
    val hint = if (mode == CaptureMode.BARCODE) "对准商品条码" else "对准包装上的生产日期"

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        val executor = Executors.newSingleThreadExecutor()
                        val barcodeScanner = BarcodeScanning.getClient()
                        val textRecognizer = TextRecognition.getClient(
                            ChineseTextRecognizerOptions.Builder().build()
                        )
                        var lastTextRun = 0L

                        analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                            if (done.get()) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            val media = proxy.image
                            if (media == null) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)

                            if (mode == CaptureMode.BARCODE) {
                                barcodeScanner.process(image)
                                    .addOnSuccessListener { codes ->
                                        val value = codes.mapNotNull { it.rawValue }
                                            .firstOrNull { it.length >= 6 }
                                        if (value != null && done.compareAndSet(false, true)) {
                                            onBarcode(value)
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            } else {
                                val now = System.currentTimeMillis()
                                if (now - lastTextRun < 900) {
                                    proxy.close()
                                    return@setAnalyzer
                                }
                                lastTextRun = now
                                textRecognizer.process(image)
                                    .addOnSuccessListener { result ->
                                        val text = result.text
                                        if (text.isNotBlank() && done.compareAndSet(false, true)) {
                                            onText(text)
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            }
                        }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                        )
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(color = Color(0x99000000), shape = MaterialTheme.shapes.medium) {
                Text(
                    text = hint,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Button(
            onClick = onClose,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
        ) { Text("取消 / 手输") }
    }
}

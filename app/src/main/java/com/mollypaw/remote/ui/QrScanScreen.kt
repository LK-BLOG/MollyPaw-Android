package com.mollypaw.remote.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫码配对。
 *
 * 相机取景用 CameraX，解码用 ZXing core（纯 Java，不依赖 GMS —— ML Kit 在
 * 国产机上一半装不了，包还大一圈）。
 *
 * 解码放在单线程池里跑，并且**一帧内只允许一个解码任务在飞**：
 * QR 解码不是免费的，60fps 全解会把中端机 CPU 烤熟。
 */
@Composable
fun QrScanScreen(
    onDecoded: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var denied by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("把电脑上的二维码放进取景框") }
    val fired = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        denied = !ok
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1C1310)),
    ) {
        if (granted) {
            CameraPreview(
                onQr = { text ->
                    if (fired.compareAndSet(false, true)) {
                        hint = "已识别，正在连接…"
                        onDecoded(text)
                    }
                },
                onHint = { hint = it },
            )
            // 取景框
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(260.dp)
                    .border(3.dp, Color(0xFFD4823A), RoundedCornerShape(22.dp))
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (denied) "没有相机权限，没法扫码" else "正在申请相机权限…",
                    color = Color(0xFFFFF8F0),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "你也可以返回上一页，直接把电脑上显示的那串配对码粘进去。",
                    color = Color(0xFFD2B48C),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                if (denied) {
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4823A)),
                    ) { Text("再试一次", color = Color(0xFF23160D)) }
                }
            }
        }

        // 顶部提示
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 28.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                hint,
                color = Color(0xFFFFF8F0),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }

        TextButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
        ) {
            Text("取消", color = Color(0xFFD2B48C), fontSize = 15.sp)
        }
    }
}

@Composable
private fun CameraPreview(
    onQr: (String) -> Unit,
    onHint: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val busy = remember { AtomicBoolean(false) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                )
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { executor.shutdownNow() }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    if (!busy.compareAndSet(false, true)) {
                        image.close()
                        return@setAnalyzer
                    }
                    try {
                        val text = decodeQr(image, reader)
                        if (text != null) {
                            onHint("已识别，正在连接…")
                            onQr(text)
                        }
                    } finally {
                        image.close()
                        busy.set(false)
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }.onFailure { onHint("相机打不开：" + (it.message ?: "未知原因")) }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/**
 * 从一帧 YUV 里抠出 QR。
 *
 * 只读 Y 平面（二维码是黑白的，色度信息没用，读 U/V 纯属浪费）。
 * 注意 rowStride 可能大于宽 —— 直接按 width 传会得到一张斜的图，解不出来。
 */
private fun decodeQr(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val width = image.width
    val height = image.height
    if (rowStride <= 0 || width <= 0 || height <= 0) return null

    val data = ByteArray(rowStride * height)
    val readLen = minOf(buffer.remaining(), data.size)
    buffer.get(data, 0, readLen)

    return try {
        val source = PlanarYUVLuminanceSource(
            data, rowStride, height, 0, 0, width, height, false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = reader.decodeWithState(bitmap)
        result?.text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
  }

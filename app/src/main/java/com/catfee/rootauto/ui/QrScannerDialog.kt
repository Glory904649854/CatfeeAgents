package com.catfee.rootauto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val ScannerInk = Color(0xFF111827)
private val ScannerAccent = Color(0xFFE11D2E)
private val ScannerMuted = Color(0xFF6B7280)

/**
 * 全屏扫码弹窗：黑底、白色取景框、红色角标，和 App 的黑白红主题一致。
 * 识别到第一条二维码后回调原始字符串，由调用方解析并绑定。
 */
@Composable
fun QrScannerDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                CameraPreview(
                    onQr = onResult,
                    modifier = Modifier.fillMaxSize()
                )
                ScannerOverlay()
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "需要相机权限",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "扫码绑定 Web 控制台需要访问相机。",
                        color = ScannerMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = ScannerInk
                        )
                    ) {
                        Text("允许使用相机", fontSize = 13.sp)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "扫码绑定",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 44.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "将控制台二维码放入框内",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "识别后自动填入地址和注册码并绑定",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ScannerOverlay() {
    val side = 252.dp
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val box = side.toPx()
            val left = (size.width - box) / 2f
            val top = (size.height - box) / 2f
            val dim = Color.Black.copy(alpha = 0.46f)

            drawRect(dim, size = Size(size.width, top))
            drawRect(
                dim,
                topLeft = Offset(0f, top + box),
                size = Size(size.width, size.height - top - box)
            )
            drawRect(dim, topLeft = Offset(0f, top), size = Size(left, box))
            drawRect(dim, topLeft = Offset(left + box, top), size = Size(size.width - left - box, box))
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(side)
                .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(20.dp))
        )

        Canvas(Modifier.align(Alignment.Center).size(side)) {
            val len = 30.dp.toPx()
            val stroke = 3.5.dp.toPx()
            val w = size.width
            val h = size.height
            val color = ScannerAccent

            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(color, Offset(x1, y1), Offset(x2, y2), stroke, StrokeCap.Round)

            // 左上
            line(0f, 0f, len, 0f)
            line(0f, 0f, 0f, len)
            // 右上
            line(w - len, 0f, w, 0f)
            line(w, 0f, w, len)
            // 左下
            line(0f, h - len, 0f, h)
            line(0f, h, len, h)
            // 右下
            line(w - len, h, w, h)
            line(w, h - len, w, h)
        }
    }
}

@Composable
private fun CameraPreview(
    onQr: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    val handled = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@Runnable
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || handled.get()) {
                    proxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { codes ->
                        val raw = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                        if (!raw.isNullOrBlank() && handled.compareAndSet(false, true)) {
                            mainHandler.post { onQr(raw) }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }
        }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            handled.set(true)
            runCatching { scanner.close() }
            executor.shutdown()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

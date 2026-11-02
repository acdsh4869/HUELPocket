@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)

package com.huelpocket.app.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.huelpocket.glassui.GlassButton
import com.huelpocket.glassui.GlassButtonStyle
import com.huelpocket.glassui.GlassIconButton
import com.huelpocket.glassui.GlassTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通用二维码扫码页（ML Kit + CameraX）。
 *
 * 从图书馆「扫码入馆」抽取而来，供多个场景复用（图书馆 / 胖乖设备码）。
 * 本组件只负责「扫出内容」；内容合法性由调用方用 [acceptFilter] 判定：
 * - 通过 → onScanned(raw)
 * - 不通过 → onRejected()（组件自动加 1.8s 防抖，不会连续触发）
 */
@OptIn(androidx.camera.core.ExperimentalGetImage::class)
// ImageProxy.image 需 ExperimentalGetImage；@OptIn 已声明，但 lint 对
// AndroidView/DisposableEffect 内的 lambda 识别不到，显式抑制该条
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QrScannerScreen(
    title: String,
    hint: String,
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
    acceptFilter: (String) -> Boolean = { true },
    onRejected: () -> Unit = {},
    cameraDeniedHint: String = "扫码需要使用相机。请到系统设置中允许口袋财大访问相机。",
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasCamera = remember {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var showPermissionGuide by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val handled = remember { AtomicBoolean(false) }
    val invalidLock = remember { AtomicBoolean(false) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(hasCamera) {
        if (!hasCamera) showPermissionGuide = true
    }

    DisposableEffect(hasCamera) {
        onDispose {
            cameraProvider?.unbindAll()
            analyzerExecutor.shutdown()
            scanner.close()
        }
    }

    if (!hasCamera || showPermissionGuide) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("需要相机权限", style = MaterialTheme.typography.titleMedium)
                    Text(
                        cameraDeniedHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.colors.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(
                            onClick = {
                                val settings = Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(settings) }
                            },
                            style = GlassButtonStyle.Prominent,
                        ) { Text("去系统设置") }
                        GlassButton(onClick = onBack) { Text("返回") }
                    }
                }
            }
        }
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        LaunchedEffect(Unit) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analysis.setAnalyzer(analyzerExecutor) { proxy: ImageProxy ->
                            try {
                                val mediaImage = proxy.image
                                if (mediaImage == null) {
                                    proxy.close()
                                    return@setAnalyzer
                                }
                                val input = InputImage.fromMediaImage(
                                    mediaImage,
                                    proxy.imageInfo.rotationDegrees,
                                )
                                scanner.process(input)
                                    .addOnSuccessListener { barcodes ->
                                        val raw = barcodes.firstNotNullOfOrNull {
                                            it.rawValue?.takeIf { v -> v.isNotBlank() }
                                        }
                                        if (raw != null) {
                                            if (acceptFilter(raw)) {
                                                if (handled.compareAndSet(false, true)) {
                                                    ContextCompat.getMainExecutor(context).execute {
                                                        onScanned(raw)
                                                    }
                                                }
                                            } else if (invalidLock.compareAndSet(false, true)) {
                                                ContextCompat.getMainExecutor(context).execute {
                                                    onRejected()
                                                }
                                                android.os.Handler(android.os.Looper.getMainLooper())
                                                    .postDelayed({ invalidLock.set(false) }, 1800L)
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            } catch (e: Exception) {
                                proxy.close()
                            }
                        }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                        cameraProvider = provider
                    } catch (e: Exception) {
                        cameraError = "相机启动失败，请重试。"
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTheme.colors.textSecondary,
                        )
                    }
                }
            }
            cameraError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

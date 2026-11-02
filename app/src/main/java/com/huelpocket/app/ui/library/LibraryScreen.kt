package com.huelpocket.app.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.huelpocket.app.ui.components.GlassCard
import com.huelpocket.app.ui.components.QrScannerScreen
import com.huelpocket.app.ui.nav.DOCK_CLEARANCE
import com.huelpocket.app.ui.web.EmbeddedWebViewScreen
import com.huelpocket.glassui.GlassButton
import com.huelpocket.glassui.GlassButtonStyle
import com.huelpocket.glassui.GlassIconButton
import com.huelpocket.glassui.GlassTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val XUEXITONG_PACKAGE = "com.chaoxing.mobile"

/** CAS 登录链入口（service 参数与学校官方一致） */
private const val LIBRARY_CAS_URL =
    "https://cas.huel.edu.cn/cas/login?service=https%3a%2f%2fzhstsg-jx.5read.com%2fclogin%2flogin%3ftype%3dxxt%26schoolId%3d1399"

/**
 * 官方座位预约入口（侦察确认，不要改动/再猜）：
 * 学校官方指南二维码解码 + 超星通用 appid 佐证。
 *
 * ⚠️ 该 URL 无超星会话时会被 302 强制跳微信 OAuth，绝不能冷打开——
 * 必须先在 WebView 内完成 CAS 登录建立超星会话，再显式 loadUrl 此地址。
 */
private const val SEAT_ENTRY_URL =
    "https://v1.chaoxing.com/wechatAuth/appDetail?appid=9f1e96512641ab37&fid=f1b4f65e74fa09b7"

/** 判定「CAS 登录链已完成落地」的 host 标记 */
private val LIBRARY_LANDING_MARKERS = listOf("5read.com", "chaoxing.com", "libsou.com")

/** 个人中心 / 登录页特征：落在这些页说明没到达预约页，交给 target 机制再跳一次 */
private val PERSONAL_CENTER_MARKERS =
    listOf("i.mooc.chaoxing.com/space", "passport", "/clogin/login", "/cas/login")

/** 微信 OAuth 特征：命中说明超星会话没建立成功 */
private fun isWechatOAuth(url: String?): Boolean =
    url != null && (url.contains("open.weixin.qq.com", true) || url.contains("/oauth2", true))

/** 直入式图书馆子页（重构后不再有独立 tab 与菜单页） */
enum class LibraryTabView { Appointment, Scan, ScannedUrl }

/**
 * 图书馆子页：只从学习 tab 的两张卡**直入**（预约座位 / 扫码入馆），
 * 没有「菜单页」这一层 —— 重构前的独立 tab 与菜单页已彻底删除。
 *
 * 返回语义（缺陷修复）：本组件的所有内部返回（系统返回键/手势、页内返回按钮）
 * 都直接退出到所属 tab（学习页对应区域），**绝不落入任何旧页面**。
 * 退出前把内部 view 复位回 startView，避免 rememberSaveable 在下次进入时
 * 恢复出中间态（ScannedUrl 等）盖过 startView。
 */
@Composable
fun LibraryScreen(startView: LibraryTabView, onExit: () -> Unit) {
    var view by rememberSaveable { mutableStateOf(startView.name) }
    var scannedUrl by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // 所有内部返回的唯一出口：复位 → 退出到父级（学习 tab 对应区域）
    val exitToParent = {
        view = startView.name
        onExit()
    }

    // 兜底 BackHandler：仅当当前没有子页注册自己的 BackHandler 时生效
    // （子页 BackHandler 组合顺序更靠后、优先级更高，正常路径都由它们接管）
    BackHandler(enabled = true) { exitToParent() }

    when {
        view == LibraryTabView.Appointment.name -> {
            var seatRestartKey by remember { mutableStateOf(0) }
            var authExpired by remember { mutableStateOf(false) }

            Box(Modifier.fillMaxSize()) {
                EmbeddedWebViewScreen(
                    title = "预约座位",
                    subtitle = "统一认证登录 → 自动进入座位预约",
                    url = LIBRARY_CAS_URL,
                    targetUrlAfterLogin = SEAT_ENTRY_URL,
                    landingUrlMarkers = LIBRARY_LANDING_MARKERS,
                    rejumpUrlMarkers = listOf("i.mooc.chaoxing.com/space", "passport"),
                    externalRestartKey = seatRestartKey,
                    onPageFinished = { _, url ->
                        when {
                            // 微信 OAuth = 超星会话没建立成功 → 提示重走 CAS
                            isWechatOAuth(url) -> { authExpired = true }
                            // 个人中心/登录页：target 机制会自动再跳一次预约入口
                            url != null && PERSONAL_CENTER_MARKERS.any { url.contains(it, true) } -> {
                                authExpired = false
                            }
                            url != null && url.contains("zhstsg-jx.5read.com", true) -> {
                                authExpired = false
                            }
                        }
                    },
                    onBack = exitToParent,
                )

                if (authExpired) {
                    GlassCard(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(24.dp),
                    ) {
                        Column(
                            Modifier.padding(20.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("登录态失效，请重新进入", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "图书馆会话未建立成功，需要重新完成统一身份认证。",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTheme.colors.textSecondary,
                            )
                            GlassButton(
                                onClick = {
                                    authExpired = false
                                    seatRestartKey++
                                },
                                style = GlassButtonStyle.Prominent,
                            ) { Text("重新进入") }
                        }
                    }
                }
            }
        }

        view == LibraryTabView.Scan.name -> {
            QrScannerScreen(
                title = "扫码进图书馆",
                hint = "对准图书馆闸机 / 座位上的二维码",
                onBack = exitToParent,
                onScanned = { raw ->
                    scannedUrl = raw
                    view = LibraryTabView.ScannedUrl.name
                },
                acceptFilter = { it.startsWith("http://", true) || it.startsWith("https://", true) },
                onRejected = { /* 非入馆码：留在扫码页继续扫 */ },
            )
        }

        view == LibraryTabView.ScannedUrl.name && scannedUrl != null -> {
            EmbeddedWebViewScreen(
                title = "扫码入馆",
                subtitle = "已使用图书馆会话打开扫码结果",
                url = scannedUrl.orEmpty(),
                // 扫到的任意 URL 打不开时：不展示裸错误页，给明确提示 + 兜底按钮
                failureTitle = "链接打开失败，可能不是入馆码",
                failureHint = "请扫描图书馆闸机 / 座位上的二维码，或用学习通原版扫一扫。",
                failureExtraActionLabel = "打开学习通扫一扫",
                onFailureExtraAction = {
                    openXuexitong(context)
                    exitToParent()
                },
                onBack = exitToParent,
            )
        }

        else -> {
            // 防御分支：ScannedUrl 但还没有扫码结果（理论上不可达）——立即退出，不渲染旧页面
            LaunchedEffect(Unit) { exitToParent() }
            Spacer(Modifier.size(1.dp))
        }
    }
}

/**
 * 拉起学习通。
 *
 * Android 11+ 包可见性限制：未在 <queries> 声明的包名对 PackageManager 不可见，
 * getLaunchIntentForPackage 会静默返回 null 被误判为「未安装」——
 * 因此这里用显式包名 Intent + resolveActivity，且仅 ActivityNotFoundException 才提示安装。
 */
private fun openXuexitong(context: Context) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setPackage(XUEXITONG_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val resolveInfo = runCatching {
        context.packageManager.resolveActivity(intent, 0)
    }.getOrNull()
    val activityInfo = resolveInfo?.activityInfo ?: return
    try {
        context.startActivity(
            intent.setClassName(activityInfo.packageName, activityInfo.name),
        )
    } catch (_: android.content.ActivityNotFoundException) {
        // 无法拉起学习通：静默返回（用户仍可自行打开学习通）
    }
}


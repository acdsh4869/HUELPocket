package com.huelpocket.app.ui.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.huelpocket.app.core.AppGraph
import com.huelpocket.app.ui.components.GlassCard
import com.huelpocket.app.ui.nav.DOCK_CLEARANCE
import com.huelpocket.glassui.GlassButton
import com.huelpocket.glassui.GlassButtonStyle
import com.huelpocket.glassui.GlassIconButton
import kotlinx.coroutines.launch

/**
 * 教务官方页免登容器（二期）。
 *
 * 打开前把 OkHttp 会话的 JSESSIONID 注入 CookieManager（域 xk.huel.edu.cn），
 * WebView 免登直达功能页；页面跳回登录页 = 会话失效 → 静默重登 → 重新注入 → 重载。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EasWebViewScreen(
    title: String,
    url: String,
    subtitle: String = "数据来自教务系统官方页面",
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canGoBack by remember { mutableStateOf(false) }
    var mainFrameFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var sessionReloading by remember { mutableStateOf(false) }

    fun injectSessionCookie() {
        val sessionId = AppGraph.tokenStore.zfSessionId.orEmpty()
        if (sessionId.isBlank()) return
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("https://xk.huel.edu.cn/", "JSESSIONID=$sessionId; Path=/jwglxt")
            setCookie("https://xk.huel.edu.cn/jwglxt", "JSESSIONID=$sessionId; Path=/jwglxt")
            flush()
        }
    }

    val webView = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                override fun onPageFinished(view: WebView, newUrl: String?) {
                    canGoBack = view.canGoBack()
                    CookieManager.getInstance().flush()
                    // 会话失效：被踢回登录页 → 静默重登 → 重新注入 → 重载
                    if (newUrl?.contains("login_slogin.html") == true && !sessionReloading) {
                        sessionReloading = true
                        scope.launch {
                            val vault = AppGraph.refreshCredentialVault.read()
                            val ok = vault != null && runCatching {
                                AppGraph.authRepository.beginLogin(vault.first, vault.second)
                            }.map { it is com.huelpocket.app.data.auth.AuthRepository.LoginStep.Success }
                                .getOrDefault(false)
                            if (ok) {
                                injectSessionCookie()
                                view.loadUrl(url)
                            }
                            sessionReloading = false
                        }
                    }
                    super.onPageFinished(view, newUrl)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) mainFrameFailed = true
                    super.onReceivedError(view, request, error)
                }
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { injectSessionCookie() }

    androidx.compose.runtime.LaunchedEffect(url, reloadKey) {
        if (reloadKey > 0) {
            mainFrameFailed = false
            webView.reload()
        } else {
            injectSessionCookie()
            webView.loadUrl(url)
        }
    }

    // 返回优先级：网页历史 → 关闭二级页回所属 tab；绝不直接跳回总览
    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else onBack?.invoke()
    }

    DisposableEffect(url) {
        onDispose { CookieManager.getInstance().flush() }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    onBack?.let { back ->
                        GlassIconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Box(Modifier.weight(1f)) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                if (mainFrameFailed) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(20.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("页面加载失败", style = MaterialTheme.typography.titleMedium)
                                GlassButton(
                                    onClick = { reloadKey++ },
                                    style = GlassButtonStyle.Prominent,
                                ) { Text("重试") }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(DOCK_CLEARANCE))
        }
    }
}

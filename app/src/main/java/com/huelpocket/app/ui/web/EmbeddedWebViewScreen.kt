package com.huelpocket.app.ui.web

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import com.huelpocket.app.ui.components.GlassCard
import com.huelpocket.app.ui.life.safeToast
import com.huelpocket.app.ui.nav.DOCK_CLEARANCE
import com.huelpocket.glassui.GlassButton
import com.huelpocket.glassui.GlassButtonStyle
import com.huelpocket.glassui.GlassIconButton

/**
 * 官方网页平台的内嵌容器。
 *
 * 只做展示与前进/后退：不注入 JS、不拦截改写请求；
 * WebView 实例保存在 [EmbeddedWebViewPool]，切 Tab 不销毁，Cookie / DOM Storage 持久。
 *
 * [targetUrlAfterLogin] / [landingUrlMarkers]：CAS 等登录链完成后强制跳转到目标页。
 * 判定口径——页面 URL 的 host 含任一 marker 且不是登录页本身，即视为「登录完成落地」；
 * 落地后仍未到达目标（含个人中心等默认页）就显式 loadUrl(target)，最多尝试 2 次防死循环。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedWebViewScreen(
    title: String,
    url: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    targetUrlAfterLogin: String? = null,
    landingUrlMarkers: List<String> = emptyList(),
    onPageFinished: ((title: String?, url: String?) -> Unit)? = null,
    /** 外部递增此值时强制重新加载初始 [url]（如会话失效后重走 CAS 登录） */
    externalRestartKey: Int = 0,
    /**
     * 登录后定向跳转的「仍未到达」特征：跳过目标后若仍落在这些 URL 上
     * （个人中心 / 登录页等），自动再跳一次目标页；总跳转上限 2 次防死循环。
     */
    rejumpUrlMarkers: List<String> = emptyList(),
    failureTitle: String = "页面加载失败",
    failureHint: String = "请检查校园网或登录网络后重试。",
    failureExtraActionLabel: String? = null,
    onFailureExtraAction: (() -> Unit)? = null,
    /**
     * 页面已登录时回调官方平台的会话 token（null = 未登录）。
     * 读法见 [WebSessionBridge.readTokenJs]（实测兜底链）。
     */
    onSessionToken: ((String?) -> Unit)? = null,
    /** 官方页会话桥（原生 token ↔ 页面 sessionStorage 两段式互通）；null = 不启用 */
    sessionBridge: WebSessionBridge? = null,
    /** 池化 key：固定池实例（会话/历史不随动态参数变化丢）；null = 用 url */
    poolKey: String? = null,
) {
    val context = LocalContext.current
    var canGoBack by remember { mutableStateOf(false) }
    var mainFrameFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var targetJumps by remember { mutableIntStateOf(0) }
    var lastRestartKey by remember { mutableIntStateOf(0) }
    // [0]=是否已成功跳过一次目标页（跳过后只有再落到 rejump 特征页才重跳）
    val jumpState = remember(url) { booleanArrayOf(false) }
    // 会话桥状态：[0]=上次注入的 token（变化才重注入）；注入次数上限 2（防死循环）
    val injectedToken = remember(url) { arrayOfNulls<String>(1) }
    var injectionCount by remember(url) { mutableIntStateOf(0) }
    // 桥启用且 App 有 token 时：注入流程完成前用加载指示器盖住页面
    // （登录页先落地再注入是两段式的固有顺序，盖住消除「闪登录页」观感）
    var bridgeInFlight by remember(url) { mutableStateOf(false) }
    // 反向回收去重：上次已采纳的 token（同一 token 不重复回调，避免反复刷新）
    val lastAdoptedToken = remember(url) { arrayOfNulls<String>(1) }
    // 加载超时：开始加载后 15s 仍未首次 onPageFinished → 失败卡（不无限转圈）
    var loadTimedOut by remember(url) { mutableStateOf(false) }
    // [0]=页面是否处于后台（微信支付等场景 WebView 暂停，探测读数不可信）
    val probePaused = remember(url) { booleanArrayOf(false) }
    // 重定向循环检测：同一 URL 30 秒内 onPageFinished ≥2 次 = 循环 → 失败卡
    val finishCounts = remember(url) { HashMap<String, Int>() }
    // 加载进度（100 = 完成）；白屏探测：SPA 挂载点存在但内容为空
    var loadProgress by remember { mutableIntStateOf(0) }
    var blankDetected by remember { mutableStateOf(false) }

    // 白屏探测（两层）：
    // A. SPA（#app）挂载点存在但 innerHTML 为空 = 只渲染壳；
    // B. 兜底泛化：body 文本 <10 字符且无任何可交互元素（input/button/a 全零）
    //    ——覆盖无 #app 结构的页面（电费等子应用页），任何结构都逃不过这层。
    // 返回 '1' = 有内容；'0' = 白屏；'2' = 结构不适用（传统多页站，不判定）。
    val probeBlankJs = """
        (function(){
            var app = document.querySelector('#app');
            if (app && (!app.innerHTML || app.innerHTML.trim().length === 0)) return '0';
            var txt = (document.body && document.body.innerText || '').trim();
            if (txt.length < 10) {
                // 等待类文案不算白屏：官方「支付结果 · 请稍候...」等轮询页文字少但合法
                if (/请稍候|加载中|loading|等待|正在/i.test(txt)) return '1';
                // 有可交互元素或有视觉元素（img/svg 图标）也不算白屏
                var n = document.querySelectorAll('input,button,a,img,svg').length;
                if (n === 0) return '0';
            }
            return '1';
        })()
    """.trimIndent()

    /**
     * 白屏探测（轮询版）：onPageFinished 后 1.2s 起，每 1.5s 一次，直到 15s 停止。
     * 判定口径收紧（chunk-vendors 1.3MB，Vue mount 慢——旧版两次定点探测在
     * mount 前误判且永不再测，失败卡永久停留）：
     * - 任一次「有内容」→ 立即撤卡（blankDetected=false），之后不再置位；
     * - 连续两次「空」**且** onProgressChanged 已达 100 → 才置 blankDetected=true；
     * - 进度未满 100 一律不算空白（还在加载）。
     */
    fun scheduleBlankProbe(view: WebView) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var emptyStreak = 0
        var settled = false // 已探测到内容，收工
        val startAt = System.currentTimeMillis()
        val probe = object : Runnable {
            override fun run() {
                if (settled || view.url == null) return
                if (probePaused[0]) { // 后台暂停中：读数不可信，推迟再试
                    handler.postDelayed(this, 1500L)
                    return
                }
                if (System.currentTimeMillis() - startAt > 15_000L) return
                view.evaluateJavascript(probeBlankJs) { result ->
                    when (result?.trim()?.removeSurrounding("\"")) {
                        "1" -> {
                            settled = true
                            blankDetected = false
                            return@evaluateJavascript
                        }
                        "0" -> {
                            emptyStreak++
                            // 连续两次空 且 加载进度已满 → 判白屏
                            if (emptyStreak >= 2 && loadProgress >= 100) {
                                blankDetected = true
                            } else {
                                handler.postDelayed(this, 1500L)
                            }
                        }
                        else -> handler.postDelayed(this, 1500L) // '2' 结构不适用，继续等
                    }
                }
            }
        }
        handler.postDelayed(probe, 1200L)
    }

    /**
     * 读页面 storage 里的 token 并回调宿主（三通道共用）：
     * ① doUpdateVisitedHistory——SPA 路由切换（pushState）必触发，**主通道**
     *    （官方页登录成功是前端路由切换，onPageFinished 不会再触发，只作兜底）；
     * ② onPageFinished——整页加载兜底；
     * ③ 退出卸载前（DisposableEffect onDispose）——保证「登录完退回来数据表一定通」。
     * 触发条件：host 含 yktwx2.huel.edu.cn 即读（不排除 /plat/login）；
     * 读不到静默跳过，无副作用可高频执行；与上次相同不重复回调。
     */
    fun readAndAdoptToken(view: WebView) {
        val bridge = sessionBridge ?: return
        view.evaluateJavascript(bridge.readTokenJs) { r ->
            val token = r?.trim()?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() && it != "null" }
            if (token != null && token != lastAdoptedToken[0]) {
                lastAdoptedToken[0] = token
                onSessionToken?.invoke(token)
            }
        }
    }

    /** CardDebug 取证：路由切换时 dump storage（值脱敏：前6字符+长度，遵守日志红线） */
    fun cardDebugDumpStorage(view: WebView) {
        view.evaluateJavascript(
            """
            (function(){
                function m(v){ if(v==null) return null; v=''+v;
                    return v.length>40 ? v.slice(0,6)+'…('+v.length+'ch)' : v; }
                var o={u:location.pathname};
                try{ for(var i=0;i<localStorage.length;i++){
                    var k=localStorage.key(i); o['ls:'+k]=m(localStorage.getItem(k)); } }catch(e){}
                try{ for(var i=0;i<sessionStorage.length;i++){
                    var k=sessionStorage.key(i); o['ss:'+k]=m(sessionStorage.getItem(k)); } }catch(e){}
                return JSON.stringify(o);
            })()
            """.trimIndent(),
        ) { r -> android.util.Log.i("CardDebug", "路由 storage: ${r ?: "null"}") }
    }

    val webView = remember(url) {
        EmbeddedWebViewPool.obtain(context, poolKey ?: url).apply {
            webViewClient = object : WebViewClient() {
                /**
                 * 拦截规则：http/https 交给 WebView 正常加载；其他 scheme
                 * （weixin:// / alipays:// 等支付拉起链接）→ 阻止 WebView 加载
                 * （否则 ERR_UNKNOWN_URL_SCHEME 错误页），交系统处理拉起对应 App；
                 * 拉起失败（未安装）toast 提示，不出错误页。
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url.toString()
                    val scheme = request.url.scheme.orEmpty().lowercase()
                    if (scheme == "http" || scheme == "https") return false

                    // 微信 H5 支付（weixin://wap/pay?prepayid=...）：恢复拉起——
                    // 这是官方充值页的微信支付收银台（非小程序），交系统后弹
                    // 微信/微信分身选择器属正常（多副本手机的标准行为），选定即付款。
                    if (scheme == "weixin" && url.contains("wap/pay")) {
                        android.util.Log.i("CardDebug", "微信H5支付拉起: $url")
                        context.safeToast("正在拉起微信支付…")
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    request.url,
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            android.util.Log.i("CardDebug", "微信支付拉起失败: $url")
                        }
                        return true
                    }

                    // 其余微信系链接（wechatMina:// 小程序、openThirdApp、open.weixin.qq.com）：
                    // App 内无微信环境确实打不开——toast 提示 + CardDebug 记录，不弹选择器
                    if (scheme.startsWith("weixin") || scheme.startsWith("wechat") ||
                        url.contains("open.weixin.qq.com")
                    ) {
                        android.util.Log.i("CardDebug", "微信系链接拦截: scheme=$scheme url=$url")
                        context.safeToast("该应用需在微信中打开")
                        return true
                    }

                    // 白名单拉起：支付宝支付 / 电话 / 邮件（明确有对应处理器的场景）
                    if (scheme == "alipays" || scheme == "alipay" ||
                        scheme == "tel" || scheme == "mailto"
                    ) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    request.url,
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            android.util.Log.i("CardDebug", "外部 scheme 拉起失败: $url")
                        }
                        return true
                    }

                    // 其余未知 scheme：只记日志，不弹选择器不加载
                    android.util.Log.i("CardDebug", "未知 scheme 拦截: scheme=$scheme url=$url")
                    return true
                }

                override fun onPageFinished(view: WebView, newUrl: String?) {
                    canGoBack = view.canGoBack()
                    CookieManager.getInstance().flush()
                    loadTimedOut = false // 首次（或再次）到达 onPageFinished：解除超时判定
                    onPageFinished?.invoke(view.title, newUrl)
                    scheduleBlankProbe(view)
                    // 重定向循环检测：同 URL 在本页面生命周期内 onPageFinished 达 3 次
                    // = 循环（阈值取 3 防 SPA 自身正常刷新误判）→ 显示失败卡
                    if (newUrl != null) {
                        finishCounts[newUrl] = (finishCounts[newUrl] ?: 0) + 1
                        if (finishCounts[newUrl] ?: 0 >= 3) {
                            mainFrameFailed = true
                        }
                        android.util.Log.i(
                            "CardDebug",
                            "onPageFinished: $newUrl (第${finishCounts[newUrl]}次)",
                        )
                    }

                    // 会话桥（WebSessionBridge）：
                    // ① 正向注入——落在登录页且 App 有 token（与上次注入的不同，上限 2 次）：
                    //    写入 sessionStorage（官方 Vuex 初始化从这里恢复会话）→ 跳 homeUrl，
                    //    SPA 重载后进入已登录态（App 登录一次 → 官方页免登）。
                    // ② 反向回收——落在业务页：按实测兜底链读页面 token 回调宿主
                    //    （官方页登录一次 → 数据表免登）；读不到 = 页面会话失效。
                    sessionBridge?.let { bridge ->
                        if (newUrl?.contains("/plat/login") == true) {
                            val token = bridge.tokenProvider()
                            if (token != null && injectedToken[0] != token && injectionCount < 2) {
                                injectedToken[0] = token
                                injectionCount++
                                bridgeInFlight = true
                                view.evaluateJavascript(bridge.writeTokenJs(token)) {
                                    // 电费等子应用：注入后跳指定目标页；默认跳官方主页
                                    view.loadUrl(bridge.injectedTargetUrl ?: bridge.homeUrl)
                                }
                            }
                        } else if (newUrl?.contains("yktwx2.huel.edu.cn") == true) {
                            // 跳出登录页（注入成功后落地业务页）→ 撤掉遮罩
                            bridgeInFlight = false
                        }
                        // 反向回收（整页加载兜底通道；SPA 路由主通道在 doUpdateVisitedHistory）
                        if (newUrl?.contains("yktwx2.huel.edu.cn") == true) {
                            readAndAdoptToken(view)
                        }
                    }
                    // 登录后定向跳转：
                    // - 登录页本身 → 重置跳转状态（等待登录链完成）；
                    // - 首次落地到目标平台 → 立即跳目标页；
                    // - 跳过后仍落在个人中心等特征页 → 再跳一次（上限 2 次）。
                    val target = targetUrlAfterLogin
                    if (target != null && newUrl != null && targetJumps < 2) {
                        val isLoginUrl = newUrl.contains("/cas/login", ignoreCase = true) ||
                            newUrl.contains("/clogin/login", ignoreCase = true)
                        when {
                            isLoginUrl -> jumpState[0] = false
                            !jumpState[0] &&
                                landingUrlMarkers.any { newUrl.contains(it, ignoreCase = true) } -> {
                                jumpState[0] = true
                                targetJumps++
                                view.loadUrl(target)
                            }
                            rejumpUrlMarkers.any { newUrl.contains(it, ignoreCase = true) } -> {
                                targetJumps++
                                view.loadUrl(target)
                            }
                        }
                    }
                    super.onPageFinished(view, newUrl)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    canGoBack = view.canGoBack()
                    CookieManager.getInstance().flush()
                    // SPA 路由切换（pushState/replaceState）必触发——token 回收主通道 +
                    // CardDebug 取证（确认登录成功时 URL 变化与 token 键名）
                    if (url?.contains("yktwx2.huel.edu.cn") == true && sessionBridge != null) {
                        readAndAdoptToken(view)
                        cardDebugDumpStorage(view)
                    }
                    super.doUpdateVisitedHistory(view, url, isReload)
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
            // 进度条：SPA 分包加载慢，进度反馈能避免"假死"观感；
            // 关键进度值 + console 报错落 CardDebug（转圈取证通道）
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadProgress = newProgress
                    if (newProgress == 10 || newProgress == 50 || newProgress == 100) {
                        android.util.Log.i("CardDebug", "进度 $newProgress% url=${view.url}")
                    }
                    super.onProgressChanged(view, newProgress)
                }

                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    val msg = consoleMessage?.message()
                    if (msg != null && (consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                            consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING)
                    ) {
                        android.util.Log.i(
                            "CardDebug",
                            "console[${consoleMessage.messageLevel()}]: ${msg.take(160)} @${consoleMessage.lineNumber()}",
                        )
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        }
    }

    // 加载超时兜底：15 秒内没有任何一次 onPageFinished → 停止转圈显示失败卡
    // （资源挂起/内网不可达等场景，任何路径都不会无限转圈）
    androidx.compose.runtime.LaunchedEffect(url, reloadKey, externalRestartKey) {
        loadTimedOut = false
        kotlinx.coroutines.delay(15_000L)
        if (loadProgress < 100 && !mainFrameFailed && !blankDetected) {
            loadTimedOut = true
            android.util.Log.i("CardDebug", "加载超时（15s 未达 onPageFinished）: $url")
        }
    }

    // 首次进入加载；切 Tab 回来复用已有页面，不强制刷新；
    // reloadKey>0 = 重试当前页；externalRestartKey 递增 = 重走初始（登录）链路
    androidx.compose.runtime.LaunchedEffect(url, reloadKey, externalRestartKey) {
        when {
            externalRestartKey > 0 && externalRestartKey != lastRestartKey -> {
                lastRestartKey = externalRestartKey
                targetJumps = 0
                mainFrameFailed = false
                webView.loadUrl(url)
            }
            reloadKey > 0 -> {
                mainFrameFailed = false
                webView.reload()
            }
            webView.url == null -> webView.loadUrl(url)
        }
    }

    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else onBack?.invoke()
    }

    // 生命周期：后台（微信支付拉起等）暂停探测；回前台撤掉误判的失败卡并重新探测
    // ——支付场景实锤：WebView 暂停时 evaluateJavascript 读到假"空"被误判白屏，
    //   用户付款返回后失败卡已盖上（16:15 日志：payment/result 正常但卡片误弹）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, url) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> probePaused[0] = true
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    probePaused[0] = false
                    blankDetected = false
                    loadTimedOut = false
                    scheduleBlankProbe(webView)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 只解除视图树挂载；不 destroy、不清数据，Cookie 顺路落盘；
    // 卸载前读最后一次 token（用户点返回退出的兜底通道）
    DisposableEffect(url) {
        onDispose {
            CookieManager.getInstance().flush()
            readAndAdoptToken(webView)
            (webView.parent as? ViewGroup)?.removeView(webView)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    onBack?.let { back ->
                        GlassIconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                    Column(
                        Modifier.weight(1f).padding(
                            horizontal = if (onBack == null) 16.dp else 8.dp,
                            vertical = 6.dp,
                        ),
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        subtitle?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Box(Modifier.weight(1f)) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                )

                // 桥注入进行中：遮住登录页（防闪现），显示轻量加载态
                if (bridgeInFlight) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.CircularProgressIndicator()
                            Text(
                                "正在进入…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 加载进度条（SPA 分包慢时给反馈，100% 后消失）
                if (loadProgress in 1..99) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                when {
                    mainFrameFailed || loadTimedOut -> {
                        // 自定义友好错误卡：不展示系统裸错误页
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            GlassCard(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(20.dp).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(failureTitle, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        failureHint + "如需访问电费请连接校园网后重试。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        GlassButton(
                                            onClick = {
                                                mainFrameFailed = false
                                                blankDetected = false
                                                reloadKey++
                                            },
                                            style = GlassButtonStyle.Prominent,
                                        ) { Text("重试") }
                                        if (failureExtraActionLabel != null && onFailureExtraAction != null) {
                                            GlassButton(onClick = onFailureExtraAction) {
                                                Text(failureExtraActionLabel)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    blankDetected -> {
                        // 白屏：官方 SPA 只渲染了装饰没渲染内容（JS 异常/资源失败）
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            GlassCard(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(20.dp).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text("页面加载失败", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "官方页面没有正常渲染。可重试，或用系统浏览器打开。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        GlassButton(
                                            onClick = {
                                                blankDetected = false
                                                reloadKey++
                                            },
                                            style = GlassButtonStyle.Prominent,
                                        ) { Text("重试") }
                                        GlassButton(onClick = {
                                            val target = webView.url ?: url
                                            runCatching {
                                                context.startActivity(
                                                    android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(target),
                                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                                )
                                            }
                                        }) { Text("用浏览器打开") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Dock 是悬浮覆盖层，WebView 也不能被它永久压住内容
            Spacer(Modifier.height(DOCK_CLEARANCE))
        }
    }
}

/**
 * 官方页会话桥：App 原生 token ↔ 官方 H5 sessionStorage 双向互通。
 *
 * 实测依据（2026-10-29，CardDebug dump + app.8060e4dc.js 静态分析）：
 * - 官方 Vuex state 初始化（@57220）：
 *   `token = sessionStorage.getItem("access_token")`、`token_type = sessionStorage.getItem("token_type")`
 *   ——SPA 每次页面加载都从 sessionStorage 恢复会话；
 * - Login mutation（@59628）写入同键名；正常会话 token_type = "bearer"（getTokenAfter）；
 * - localStorage 无 token（全是配置），无 cookie。
 *
 * 正向（App → 页面）：登录页落地后 [writeTokenJs] 写入 → 跳 [homeUrl]，SPA 重载恢复登录态；
 * 反向（页面 → App）：[readTokenJs] 兜底链读回 token（官方页登录一次 → 数据表免登）。
 */
class WebSessionBridge(
    /** App 持有的官方平台 token；null = 未登录（页面走自己的登录表单） */
    val tokenProvider: () -> String?,
    /** 把 token 写入页面 sessionStorage 的 JS（evaluateJavascript 执行） */
    val writeTokenJs: (String) -> String,
    /** 从页面存储读 token 的 JS（返回空串/null = 页面未登录；实测兜底链） */
    val readTokenJs: String,
    /** 注入完成后跳转的官方主页（SPA 重载时从 sessionStorage 恢复会话） */
    val homeUrl: String,
    /** 子应用目标页（电费等）：注入后优先跳这里（token 已入 sessionStorage，
     *  子应用 SPA 自带 synjones-auth 头请求，不缺上下文）；null = 跳 homeUrl */
    val injectedTargetUrl: String? = null,
)

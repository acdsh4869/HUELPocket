package com.huelpocket.app.ui.web

import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Tab 级 WebView 复用池。
 *
 * 登录态跟随 Activity 生命周期保留：切 Tab 只把 WebView 从视图树取下，
 * 不 destroy，也不清 Cookie / localStorage / sessionStorage。
 *
 * 白屏排查（2026-10-29）后的设置清单：SPA（Vue2 + Vant）依赖
 * JS + domStorage + database 全开；官方站按 UA 分发页面，必须去掉 WebView
 * 特征 "; wv" 伪装手机 Chrome；debug 包开远程调试（PC chrome://inspect 看报错）。
 */
internal object EmbeddedWebViewPool {

    private val webViews = linkedMapOf<String, WebView>()

    fun obtain(activityContext: Context, url: String): WebView {
        webViews[url]?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            (existing.context as? MutableContextWrapper)?.baseContext = activityContext
            return existing
        }

        val contextWrapper = MutableContextWrapper(activityContext)
        return WebView(contextWrapper).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 官方站按 UA 分发：去掉 WebView 特征 "; wv"，等价手机 Chrome
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            isVerticalScrollBarEnabled = true

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = false
            }
            webChromeClient = WebChromeClient()

            // debug 包开 WebView 远程调试：PC chrome://inspect 可连，看 Console/Network
            if (com.huelpocket.app.BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            webViews[url] = this
        }
    }
}

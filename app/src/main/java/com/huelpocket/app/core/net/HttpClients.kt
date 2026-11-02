package com.huelpocket.app.core.net

import com.huelpocket.app.BuildConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OkHttp 客户端工厂。
 *
 * 关键点：**每个上游系统一个独立 client**。教务与图书馆都依赖 cookie / 令牌，
 * 共用一个 client 会让两套会话互相污染。
 *
 * 全部使用 followRedirects = false 的变体由调用方按需选择：
 * 一卡通拿 tid 必须自己读 Location 头。
 */
object HttpClients {

    private const val UA_DEFAULT = "okhttp/3.12.1"

    /** 请求头复刻：学校服务对 UA / Accept 敏感，缺了可能被拒或被当成爬虫 */
    private class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val builder = request.newBuilder()
            // 只补默认值，不覆盖调用方显式设置的 UA
            // （统一认证的登录/MFA 请求要用 SWSuperApp/1.1.1()，取公钥要用 okhttp/3.12.1）
            if (request.header("User-Agent") == null) {
                builder.header("User-Agent", UA_DEFAULT)
            }
            if (request.header("Accept") == null) {
                builder.header("Accept", "application/json, text/plain, */*")
            }
            return chain.proceed(builder.build())
        }
    }

    /**
     * 每主机最小请求间隔。
     *
     * 有实证支撑：旧项目 1 分钟轮询 15 分钟，16 条读数完全相同 —— 高频轮询既无意义
     * 又增加被风控识别的风险。这里从架构上兜住下限。
     */
    private class RateLimitInterceptor(private val minIntervalMs: Long) : Interceptor {
        private val lastCallAt = ConcurrentHashMap<String, Long>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val host = chain.request().url.host
            synchronized(lastCallAt) {
                val now = System.currentTimeMillis()
                val last = lastCallAt[host] ?: 0L
                val wait = minIntervalMs - (now - last)
                if (wait > 0) Thread.sleep(wait)
                lastCallAt[host] = System.currentTimeMillis()
            }
            return chain.proceed(chain.request())
        }
    }

    /** 只打印方法 + URL（**不含请求头与响应体**，避免把 token 写进日志） */
    private class SafeLogInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = try {
                chain.proceed(request)
            } catch (e: Exception) {
                android.util.Log.w("HUELPocket", "${request.method} ${request.url.encodedPath} 失败: ${e.javaClass.simpleName}")
                throw e
            }
            // ⚠️ 用 Log.i 而不是 Log.d：vivo 等国产 ROM 默认丢弃应用的 DEBUG 级日志，
            // Log.d 在真机上完全不可见（2026-10-28 实测：真机登录后 logcat 无任何请求日志）。
            android.util.Log.i(
                "HUELPocket",
                "${request.method} ${request.url.encodedPath} → ${response.code} (${response.body?.contentLength() ?: -1}B)",
            )
            return response
        }
    }

    private fun builder(minIntervalMs: Long) = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // HUEL 教务系统需要禁用系统代理（等价于 Python 的 trust_env=False）
        .proxy(java.net.Proxy.NO_PROXY)
        .addInterceptor(HeaderInterceptor())
        .addInterceptor(RateLimitInterceptor(minIntervalMs))
        .apply { if (BuildConfig.DEBUG) addInterceptor(SafeLogInterceptor()) }

    /**
     * 正方教务：需要禁用系统代理。
     * 登录成功返回 302，由 ZhengFangClient 判定并用 HTTPS 请求复核会话；不能自动跟随，
     * 否则服务端跳到 HTTP 地址时会被 Android 的 cleartext 限制拦截，误报为网络错误。
     */
    val zhengfang: OkHttpClient by lazy {
        builder(minIntervalMs = 300)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** 教务 EAS：兼容旧接口 */
    val eas: OkHttpClient by lazy {
        builder(minIntervalMs = 300).followRedirects(false).build()
    }

    /**
     * 一卡通（新中新慧新E校）会话 Cookie 池。
     *
     * ⚠️ 必须是**合并式**：keyboard uuid 与 token 校验绑定服务端会话，
     * redirect 入口 / keyboard / oauth/token 三次请求各自下发不同的 Set-Cookie
     * （如 JSESSIONID、租户 tid 等）。旧实现 `store[host] = cookies` 整体覆盖，
     * 后一次响应只有 1 条 cookie 时会把前面攒的会话全部冲掉，
     * 表现为 keyboard uuid 校验失败并伪装成「用户名或密码错误」。
     *
     * 合并规则：按 (name, domain, path) 去重，新值覆盖旧值；其余保留。
     * loadForRequest 过滤已过期 cookie（expiring cookie 不清理会在会话中段突然失效）。
     * 日志只打 cookie **名字**（Log.i：vivo 等 ROM 丢弃 DEBUG 级），绝不打值。
     */
    class CardCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
            val hostStore = store.getOrPut(url.host) { ConcurrentHashMap() }
            cookies.forEach { cookie ->
                hostStore[cookieKey(cookie)] = cookie
            }
            android.util.Log.i(
                "HUELPocket",
                "CardCookie ← ${url.host} [${cookies.joinToString { it.name }}] 存量=${hostStore.keys}",
            )
        }

        override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
            val hostStore = store[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            return hostStore.values.filter { it.expiresAt > now }
        }

        private fun cookieKey(cookie: Cookie): String =
            "${cookie.name}|${cookie.domain}|${cookie.path}"
    }

    /** 一卡通两个实例共用的会话 Cookie 池（redirect 跟随与 JSON API 必须同一份会话） */
    private val cardCookieJar = CardCookieJar()

    /**
     * 一卡通（新中新慧新E校）：纯 JSON API，synjones-auth 头鉴权。
     * followRedirects=false：键盘/令牌请求都是直连 200，禁跟随避免意外跳转污染会话。
     */
    val card: OkHttpClient by lazy {
        builder(minIntervalMs = 300).followRedirects(false)
            .cookieJar(cardCookieJar)
            .build()
    }

    /**
     * 一卡通登录链入口专用：`/berserker-base/redirect?type=login` 会 302 跳转若干跳
     * 才落地登录页，期间逐跳下发会话 Cookie。官方 H5 由浏览器自动跟随，
     * App 用这个 followRedirects=true 的实例等价复刻（与 [card] 共用 [cardCookieJar]，
     * 跳转攒下的 Cookie 对后续 keyboard/token 请求可见）。
     */
    val cardRedirect: OkHttpClient by lazy {
        builder(minIntervalMs = 300).followRedirects(true).followSslRedirects(true)
            .cookieJar(cardCookieJar)
            .build()
    }
}

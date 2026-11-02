package com.huelpocket.app.data.auth

import com.huelpocket.app.core.AppError
import com.huelpocket.app.core.crypto.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * 河南财经政法大学正方教务系统客户端（新版 jwglxt）。
 *
 * 协议严格对齐《教务接口实测.md》：
 *  1. GET  /jwglxt/xtgl/login_slogin.html?time=<ms>  → JSESSIONID + csrftoken
 *  2. GET  /jwglxt/xtgl/login_getPublicKey.html?time=<ms> → {modulus, exponent}（base64）
 *  3. [可选] GET /jwglxt/kaptcha?time=<ms> → 验证码图片
 *  4. POST /jwglxt/xtgl/login_slogin.html?time=<ms> → 登录
 *
 * HUEL 特有差异：
 *  - 课表接口换路径：xskbcx_cxXsgrkb.html（不是通用的 xskbcx_cxXsKb.html）
 *  - 学期编码：3/12/16（不是通用的 3/12/21）
 *  - RSA 公钥是 base64 编码，不是 hex
 *  - 需要禁用系统代理（trust_env=False 等价）
 */
class ZhengFangClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    @Serializable
    data class PublicKeyResponse(
        val modulus: String,
        val exponent: String,
    )

    data class LoginPageInfo(
        val csrfToken: String,
        val needCaptcha: Boolean,
        val needEncrypt: Boolean,
    )

    data class LoginResult(
        val success: Boolean,
        val message: String? = null,
        val needCaptcha: Boolean = false,
    )

    private var publicKey: RSAPublicKey? = null
    private var jsessionId: String? = null

    /** 当前会话的 JSESSIONID（GET 登录页时建立），登录成功后由 AuthRepository 持久化 */
    val currentSessionId: String? get() = jsessionId

    /**
     * 第一步：获取登录页，提取 csrftoken 和标志位
     */
    suspend fun fetchLoginPage(): LoginPageInfo = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/xtgl/login_slogin.html?time=${System.currentTimeMillis()}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .get()
            .build()

        val response = execute(request)
        val html = response.body?.string().orEmpty()

        // 提取 JSESSIONID
        response.headers("Set-Cookie").forEach { cookie ->
            if (cookie.startsWith("JSESSIONID=")) {
                jsessionId = cookie.substringBefore(";").substringAfter("JSESSIONID=")
            }
        }
        // HUEL 的 RSA 公钥随会话变化（2026-10-28 两个独立会话实测不同）。
        // GET 登录页已经建立新会话，必须丢弃旧会话公钥，否则后续登录会用旧公钥加密，
        // 服务端无法在当前会话解密，只返回误导性的「用户名或密码不正确」。
        publicKey = null

        // 提取 csrftoken
        val csrfToken = Regex("""id="csrftoken"[^>]*value="([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: throw AppError.Parse("无法从登录页提取 csrftoken")

        // 提取标志位。
        // ⚠️ 服务端实际输出是无引号 + 空格格式：value= 0 / value= 1（实测 2026-10-28 抓包确认），
        // 写 value="1" 带引号的正则永远匹配不到 → 一旦服务端要求验证码（连续失败后 dlsfbxyzm=1）
        // App 也检测不到，会陷入无限「密码不正确」循环。
        val needCaptcha = Regex("""name="dlsfbxyzm"[^>]*value=\s*"?1""").containsMatchIn(html)
        val needEncrypt = Regex("""name="mmsfjm"[^>]*value=\s*"?1""").containsMatchIn(html)
        android.util.Log.i(TAG, "登录页: csrftoken len=${csrfToken.length}, needCaptcha=$needCaptcha, needEncrypt=$needEncrypt")

        LoginPageInfo(
            csrfToken = csrfToken,
            needCaptcha = needCaptcha,
            needEncrypt = needEncrypt,
        )
    }

    /**
     * 第二步：获取 RSA 公钥
     */
    private suspend fun ensurePublicKey(): RSAPublicKey = publicKey ?: withContext(Dispatchers.IO) {
        val url = "$BASE_URL/xtgl/login_getPublicKey.html?time=${System.currentTimeMillis()}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Cookie", "JSESSIONID=$jsessionId")
            .get()
            .build()

        val body = execute(request).body?.string().orEmpty()
        val keyResponse = try {
            json.decodeFromString<PublicKeyResponse>(body)
        } catch (e: Exception) {
            throw AppError.Parse("RSA 公钥响应解析失败", e)
        }

        // HUEL 的公钥是 base64 编码，需要解码后构造 RSA 公钥
        val modulusBytes = Base64.getDecoder().decode(keyResponse.modulus)
        val exponentBytes = Base64.getDecoder().decode(keyResponse.exponent)

        val modulus = BigInteger(1, modulusBytes)
        val exponent = BigInteger(1, exponentBytes)

        val keySpec = RSAPublicKeySpec(modulus, exponent)
        val keyFactory = KeyFactory.getInstance("RSA")
        val key = keyFactory.generatePublic(keySpec) as RSAPublicKey

        publicKey = key
        key
    }

    /**
     * 第三步：获取验证码图片
     */
    suspend fun fetchCaptcha(): ByteArray = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/kaptcha?time=${System.currentTimeMillis()}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Cookie", "JSESSIONID=$jsessionId")
            .get()
            .build()

        execute(request).body?.bytes()
            ?: throw AppError.Network("获取验证码失败")
    }

    /**
     * 第四步：登录
     */
    suspend fun login(
        account: String,
        password: String,
        csrfToken: String,
        captcha: String? = null,
    ): LoginResult = withContext(Dispatchers.IO) {
        val key = ensurePublicKey()

        // RSA 加密密码
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encryptedPassword = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val mm = Base64.getEncoder().encodeToString(encryptedPassword)

        val url = "$BASE_URL/xtgl/login_slogin.html?time=${System.currentTimeMillis()}"
        // 必须用 FormBody 做 URL 编码：mm 是 base64，含 '+'；
        // 手工拼串会让 '+' 被服务端解码成空格，RSA 密文损坏 → 报「用户名或密码不正确」
        val formBody = FormBody.Builder()
            .add("csrftoken", csrfToken)
            .add("language", "zh_CN")
            .add("ydType", "")
            .add("yhm", account)
            .add("mm", mm)
            .add("mm", mm) // 页面有两个同名 mm 输入，与浏览器保持一致
            .apply { if (captcha != null) add("yzm", captcha) }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Cookie", "JSESSIONID=$jsessionId")
            .header("Referer", "$BASE_URL/xtgl/login_slogin.html")
            .post(formBody)
            .build()

        // 与浏览器一致：POST 成功返回 302 后继续跟随到主框架页。
        // 这不是放宽成功判定 —— 最终仍按「最终页面不是登录页」判定；
        // 不跟随的话，服务端可能在跳转链中轮换/激活会话，后续请求会被踢回登录页。
        val response = followRedirectsAfterPost(execute(request))
        response.use { resp ->
            val html = resp.body?.string().orEmpty()
            val looksLoginPage = html.contains("id=\"csrftoken\"")
            val success = resp.code == 200 && !looksLoginPage

            // 检查是否需要验证码（无引号格式，见 fetchLoginPage 注释）
            val needCaptcha = Regex("""name="dlsfbxyzm"[^>]*value=\s*"?1""").containsMatchIn(html)

            // 提取错误信息。
            // ⚠️ 服务端的 #tips 内部嵌了 <span> 图标标签（实测抓包）：
            //   <p id="tips" ...><span class="glyphicon ..."></span>用户名或密码不正确，请重新输入！</p>
            // [^<]+ 无法跨越嵌套标签，必须用惰性匹配整个 <p>…</p> 再剥掉内部标签，
            // 否则服务端真实文案（如锁定提示）永远取不到，只会显示兜底文案。
            val errorMsg = Regex("""<p[^>]*id="tips"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            android.util.Log.i(
                TAG,
                "登录结果: HTTP ${resp.code}, success=$success, needCaptcha=$needCaptcha, " +
                    "mm密文len=${mm.length}, tips=${errorMsg ?: "(无)"}",
            )

            LoginResult(
                success = success,
                message = errorMsg,
                needCaptcha = needCaptcha,
            )
        }
    }

    /**
     * 验证会话是否有效。
     *
     * 复核接口对未登录会话会 302 回登录页；登录有效时最终返回菜单页。
     * 这里手动跟随跳转（客户端全局 followRedirects=false，避免登录 302 被 Android
     * 的 cleartext 限制拦截），并把服务端给的 http Location 升级为 https。
     * 只要跳回登录页或最终页面没有菜单特征，一律判失败 —— 不放宽校验。
     */
    suspend fun verifySession(): Boolean = withContext(Dispatchers.IO) {
        try {
            var url = "$BASE_URL/xtgl/index_initMenu.html".toHttpUrl()

            repeat(5) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Cookie", "JSESSIONID=$jsessionId")
                    .get()
                    .build()

                val response = execute(request)
                response.use { resp ->
                    if (resp.isRedirect) {
                        val location = resp.header("Location")
                        if (location.isNullOrBlank()) return@withContext false

                        val next = url.resolve(location)
                            ?: return@withContext false
                        // 被踢回登录页 = 会话未生效
                        if (next.encodedPath.contains("login_slogin")) {
                            android.util.Log.i(TAG, "会话复核: 重定向回登录页，会话无效")
                            return@withContext false
                        }
                        // 学校侧常返回 http Location；Android 禁止明文，升级为 https 同一主机
                        val safeNext = if (next.scheme == "http" && next.host == url.host) {
                            next.newBuilder().scheme("https").build()
                        } else {
                            next
                        }
                        android.util.Log.i(TAG, "会话复核跳转: ${safeNext.encodedPath}")
                        url = safeNext
                        return@use
                    }

                    val html = resp.body?.string().orEmpty()
                    val looksLoginPage = html.contains("id=\"csrftoken\"")
                    val valid = resp.code == 200 && !looksLoginPage &&
                        (html.contains("clickMenu") || html.contains("menuName"))
                    android.util.Log.i(
                        TAG,
                        "会话复核: HTTP ${resp.code}, valid=$valid, len=${html.length}",
                    )
                    return@withContext valid
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun execute(request: Request): okhttp3.Response = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().also { captureJsessionId(it) }
        } catch (e: IOException) {
            throw AppError.Network("无法连接教务系统，请检查网络", e)
        }
    }

    /** 服务端可能在登录跳转链中轮换会话 Cookie；每次响应都原样接收（不打印值） */
    private fun captureJsessionId(response: okhttp3.Response) {
        response.headers("Set-Cookie").forEach { cookie ->
            if (cookie.startsWith("JSESSIONID=", ignoreCase = true)) {
                val value = cookie.substringBefore(";").substringAfter("=", "").trim()
                if (value.isNotBlank()) jsessionId = value
            }
        }
    }

    /**
     * 浏览器行为等价：登录 POST 返回 302 后继续 GET 到主框架页。
     * 全局 client 禁用了自动跟随（避免 Android cleartext 限制），
     * 这里手动跟随，并把同主机的 http Location 升级为 https。
     */
    private suspend fun followRedirectsAfterPost(initial: okhttp3.Response): okhttp3.Response {
        var response = initial
        repeat(5) {
            if (!response.isRedirect) return response

            val location = response.header("Location")
                ?: return response
            val currentUrl = response.request.url
            val next = currentUrl.resolve(location)
                ?: return response
            // 同主机的 http Location 升级为 https，避免 Android 明文限制
            val safeNext = if (next.scheme == "http" && next.host == currentUrl.host) {
                next.newBuilder().scheme("https").build()
            } else {
                next
            }
            android.util.Log.i(TAG, "登录跳转: ${safeNext.encodedPath}")

            response.close()
            response = execute(
                Request.Builder()
                    .url(safeNext)
                    .header("User-Agent", UA)
                    .header("Cookie", "JSESSIONID=$jsessionId")
                    .header("Referer", "$BASE_URL/xtgl/login_slogin.html")
                    .get()
                    .build(),
            )
        }
        return response
    }

    companion object {
        private const val TAG = "HUELPocket"
        private const val BASE_URL = "https://xk.huel.edu.cn/jwglxt"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

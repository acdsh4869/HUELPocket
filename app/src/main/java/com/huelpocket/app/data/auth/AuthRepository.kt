package com.huelpocket.app.data.auth

import com.huelpocket.app.core.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 认证中枢：统一管理正方教务系统的会话生命周期。
 *
 * ⚠️ **不持久化密码**：JSESSIONID 过期后需要用户重新登录一次。
 * 登录失败 3 次会锁定 3 分钟（HUEL 特有）。
 */
class AuthRepository(
    private val store: TokenStore,
    private val zfClient: ZhengFangClient,
) {

    sealed interface LoginStep {
        data class Success(val account: String) : LoginStep
        data class NeedCaptcha(val captchaImage: ByteArray) : LoginStep
        data class Error(val message: String) : LoginStep
    }

    data class TokenStatus(
        val loggedIn: Boolean,
        val lastLoginAt: Long,
        val account: String?,
    )

    // ── 会话管理 ──────────────────────────────────────────────────────

    /** 返回当前是否已登录 */
    fun isLoggedIn(): Boolean = !store.zfSessionId.isNullOrEmpty()

    /** 取当前账号 */
    fun currentAccount(): String? = store.zfAccount

    /** 诊断用：只描述会话状态 */
    fun sessionShape(): String {
        val sessionId = store.zfSessionId ?: return "无会话"
        return "JSESSIONID 长度=${sessionId.length}，账号=${store.zfAccount ?: "未知"}"
    }

    // ── 登录编排 ──────────────────────────────────────────────────────

    /**
     * 开始登录流程
     * 1. 获取登录页（csrftoken + 标志位）
     * 2. 如果需要验证码，返回验证码图片
     * 3. 否则直接尝试登录
     */
    /**
     * 验证码流程共用的 csrftoken：与验证码图片、JSESSIONID 同属一个会话。
     *
     * ⚠️ 之前 loginWithCaptcha 每次都重新 fetchLoginPage —— 那会**新开一个 JSESSIONID 会话**，
     * 而用户看到的验证码图片绑定的是 beginLogin 时的旧会话 → 提交的验证码在新会话里必然无效。
     * 必须复用同一会话的 token，只在内存丢失时（进程被杀）才重新拉（那时验证码也要重新看）。
     */
    private var pendingCsrfToken: String? = null

    suspend fun beginLogin(account: String, password: String): LoginStep = withContext(Dispatchers.IO) {
        try {
            val pageInfo = zfClient.fetchLoginPage()
            pendingCsrfToken = pageInfo.csrfToken

            if (pageInfo.needCaptcha) {
                val captchaImage = zfClient.fetchCaptcha()
                return@withContext LoginStep.NeedCaptcha(captchaImage)
            }

            val result = zfClient.login(account, password, pageInfo.csrfToken)
            handleLoginResult(result, account)
        } catch (e: AppError) {
            LoginStep.Error(e.message ?: "登录失败")
        } catch (e: Exception) {
            LoginStep.Error("网络错误：${e.message}")
        }
    }

    /**
     * 使用验证码登录：复用取验证码图片时的同一会话与 csrftoken（见 pendingCsrfToken 注释）
     */
    suspend fun loginWithCaptcha(
        account: String,
        password: String,
        captcha: String,
    ): LoginStep = withContext(Dispatchers.IO) {
        try {
            val token = pendingCsrfToken
                ?: zfClient.fetchLoginPage().also { pendingCsrfToken = it.csrfToken }.csrfToken
            val result = zfClient.login(account, password, token, captcha)
            handleLoginResult(result, account)
        } catch (e: AppError) {
            LoginStep.Error(e.message ?: "登录失败")
        } catch (e: Exception) {
            LoginStep.Error("网络错误：${e.message}")
        }
    }

    private suspend fun handleLoginResult(result: ZhengFangClient.LoginResult, account: String): LoginStep {
        return when {
            result.success -> {
                // 会话复核（实测文档第六节推荐）：HTML 判定通过后再用一个需登录接口确认，
                // 防止把非常规的 200 响应误判成登录成功。这不是放宽，是收紧。
                if (!zfClient.verifySession()) {
                    return LoginStep.Error("登录会话未生效（复核失败），请重试")
                }
                // 会话 Cookie 必须持久化，否则 isLoggedIn() 永远为 false、课表请求也拿不到会话
                store.zfSessionId = zfClient.currentSessionId
                store.zfAccount = account
                store.zfLastLoginAt = System.currentTimeMillis()
                store.lastLoginAt = System.currentTimeMillis()
                LoginStep.Success(account)
            }
            result.needCaptcha -> {
                // 服务端要求验证码（连续失败后 dlsfbxyzm=1）：拉取验证码图片让用户输入，
                // 而不是踢回「请重新登录」—— 否则用户会无限循环在账密失败上。
                val captcha = runCatching { zfClient.fetchCaptcha() }.getOrNull()
                if (captcha != null) {
                    LoginStep.NeedCaptcha(captcha)
                } else {
                    LoginStep.Error(result.message ?: "需要验证码，但获取失败，请稍后重试")
                }
            }
            else -> {
                LoginStep.Error(result.message ?: "用户名或密码不正确")
            }
        }
    }

    /**
     * 验证当前会话是否有效
     */
    suspend fun verifySession(): Boolean = withContext(Dispatchers.IO) {
        zfClient.verifySession()
    }

    fun logout() {
        store.clearAll()
    }

    fun status(): TokenStatus = TokenStatus(
        loggedIn = isLoggedIn(),
        lastLoginAt = store.zfLastLoginAt,
        account = store.zfAccount,
    )
}

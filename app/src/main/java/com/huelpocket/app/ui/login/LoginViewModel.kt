package com.huelpocket.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huelpocket.app.core.AppGraph
import com.huelpocket.app.core.LoginInput
import com.huelpocket.app.core.toUserMessage
import com.huelpocket.app.data.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginPhase { Credentials, NeedCaptcha, Done }

data class LoginUiState(
    val account: String = "",
    val password: String = "",
    val captcha: String = "",
    val captchaImage: ByteArray? = null,
    val phase: LoginPhase = LoginPhase.Credentials,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** 密码的输入体检结果：非 null 时禁止提交，并把原因显示在密码框下面 */
    val inputIssue: LoginInput.Issue? = null,
)

class LoginViewModel : ViewModel() {

    private val auth: AuthRepository = AppGraph.authRepository

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** 登录过程中需要复用账密，只存在内存里，不落盘 */
    private var pendingAccount: String = ""
    private var pendingPassword: String = ""

    fun onAccountChange(v: String) = _state.update { it.copy(account = v.trim(), error = null) }

    fun onPasswordChange(v: String) = _state.update {
        it.copy(password = v, error = null, inputIssue = LoginInput.inspect(v))
    }

    fun onCaptchaChange(v: String) = _state.update { it.copy(captcha = v, error = null) }

    /**
     * 用户确认后把全角字符转成半角。
     */
    fun fixFullWidth() {
        val fixed = LoginInput.toHalfWidth(_state.value.password)
        _state.update { it.copy(password = fixed, error = null, inputIssue = LoginInput.inspect(fixed)) }
    }

    /**
     * 提交账密登录
     */
    fun submitCredentials() {
        val s = _state.value
        if (s.account.isEmpty() || s.password.isEmpty()) {
            _state.update { it.copy(error = "请填写学号和密码") }
            return
        }
        // 输入体检
        LoginInput.inspect(s.password)?.let { issue ->
            _state.update { it.copy(inputIssue = issue, error = issue.message) }
            return
        }
        LoginInput.inspect(s.account)?.let { issue ->
            _state.update { it.copy(error = "学号里有异常字符：${issue.message}") }
            return
        }

        pendingAccount = s.account
        pendingPassword = s.password
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                when (val step = auth.beginLogin(pendingAccount, pendingPassword)) {
                    is AuthRepository.LoginStep.NeedCaptcha -> {
                        _state.update {
                            it.copy(
                                busy = false,
                                phase = LoginPhase.NeedCaptcha,
                                captchaImage = step.captchaImage,
                                notice = "请输入验证码",
                            )
                        }
                    }
                    is AuthRepository.LoginStep.Success -> {
                        AppGraph.refreshCredentialVault.save(pendingAccount, pendingPassword)
                        finishLogin()
                    }
                    is AuthRepository.LoginStep.Error -> {
                        _state.update { it.copy(busy = false, error = step.message) }
                    }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, error = e.toUserMessage()) }
            }
        }
    }

    /**
     * 提交验证码登录
     */
    fun submitCaptcha() {
        val s = _state.value
        if (s.captcha.isEmpty()) {
            _state.update { it.copy(error = "请输入验证码") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                when (val step = auth.loginWithCaptcha(pendingAccount, pendingPassword, s.captcha)) {
                    is AuthRepository.LoginStep.Success -> {
                        AppGraph.refreshCredentialVault.save(pendingAccount, pendingPassword)
                        finishLogin()
                    }
                    is AuthRepository.LoginStep.NeedCaptcha -> {
                        // 验证码错误或过期，刷新验证码
                        _state.update {
                            it.copy(
                                busy = false,
                                captchaImage = step.captchaImage,
                                captcha = "",
                                error = "验证码错误，请重新输入",
                            )
                        }
                    }
                    is AuthRepository.LoginStep.Error -> {
                        _state.update {
                            it.copy(
                                busy = false,
                                captcha = "",
                                error = step.message,
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, error = e.toUserMessage(), captcha = "") }
            }
        }
    }

    /**
     * 刷新验证码
     */
    fun refreshCaptcha() {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                // 重新获取登录页会刷新验证码
                when (val step = auth.beginLogin(pendingAccount, pendingPassword)) {
                    is AuthRepository.LoginStep.NeedCaptcha -> {
                        _state.update {
                            it.copy(
                                busy = false,
                                captchaImage = step.captchaImage,
                                captcha = "",
                            )
                        }
                    }
                    else -> {
                        _state.update { it.copy(busy = false) }
                    }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, error = e.toUserMessage()) }
            }
        }
    }

    private fun finishLogin() {
        if (auth.isLoggedIn()) {
            _state.update { it.copy(busy = false, phase = LoginPhase.Done) }
        } else {
            _state.update {
                it.copy(
                    busy = false,
                    phase = LoginPhase.Credentials,
                    error = "登录失败，请重试",
                )
            }
        }
    }
}

package com.huelpocket.app.data.ecard

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 一卡通（新中新）本机加密凭证 + 会话令牌。
 *
 * 与教务 [com.huelpocket.app.data.auth.RefreshCredentialVault] 同一标准：
 * EncryptedSharedPreferences + Tink（AES256-GCM 主密钥 / SIV 键加密），
 * 只留在本机，不进备份、日志或任何网络上传。
 *
 * 保存项：
 * - account / password：静默续登用（用户登录成功后保存，"手动重新登录"可覆盖/清除）；
 * - accessToken / loginAt：当前会话令牌（`bearer <token>` 前缀在客户端拼，这里存裸 token）。
 */
class CardCredentialVault(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    data class Credentials(val account: String, val password: String)

    data class Session(val accessToken: String, val loginAt: Long, val expiresAt: Long) {
        /** 是否已过期或将在 [marginMs] 内过期（默认提前 5 分钟静默重登） */
        fun nearExpiry(marginMs: Long = 5 * 60_000L): Boolean =
            System.currentTimeMillis() >= expiresAt - marginMs
    }

    fun saveCredentials(account: String, password: String) {
        if (account.isBlank() || password.isBlank()) return
        prefs.edit()
            .putString(KEY_ACCOUNT, account)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun readCredentials(): Credentials? {
        val account = prefs.getString(KEY_ACCOUNT, null).orEmpty()
        val password = prefs.getString(KEY_PASSWORD, null).orEmpty()
        return if (account.isBlank() || password.isBlank()) {
            null
        } else {
            Credentials(account, password)
        }
    }

    fun saveSession(accessToken: String, expiresInSeconds: Long = 0L) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_TOKEN, accessToken)
            .putLong(KEY_LOGIN_AT, now)
            .putLong(KEY_EXPIRES_AT, if (expiresInSeconds > 0) now + expiresInSeconds * 1000 else 0L)
            .apply()
    }

    fun readSession(): Session? {
        val token = prefs.getString(KEY_TOKEN, null).orEmpty()
        if (token.isBlank()) return null
        val loginAt = prefs.getLong(KEY_LOGIN_AT, 0L)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        // 无 expires 记录时按 2 小时保守估计（新中中常见 2h~12h，宁短勿长）
        return Session(token, loginAt, if (expiresAt > 0) expiresAt else loginAt + 2 * 3600_000L)
    }

    /** 清令牌（保留凭证，供静默续登） */
    fun clearSession() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN_AT).apply()
    }

    /** 全部清除（用户主动退出 / 手动重新登录） */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        private const val FILE_NAME = "card_credentials"
        private const val KEY_ACCOUNT = "card_account"
        private const val KEY_PASSWORD = "card_password"
        private const val KEY_TOKEN = "card_access_token"
        private const val KEY_LOGIN_AT = "card_login_at"
        private const val KEY_EXPIRES_AT = "card_expires_at"
    }
}

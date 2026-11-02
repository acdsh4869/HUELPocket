package com.huelpocket.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 静默续登用的本机加密凭证。
 *
 * 用户成功登录后保存；302 会话失效时 ScheduleViewModel 读取并重登一次。
 * 凭证只留在本机加密文件里，不进入备份、日志或第三方服务。
 */
class RefreshCredentialVault(context: Context) {

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

    fun save(account: String, password: String) {
        if (account.isBlank() || password.isBlank()) return
        prefs.edit()
            .putString(KEY_ACCOUNT, account)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun read(): Pair<String, String>? {
        val account = prefs.getString(KEY_ACCOUNT, null).orEmpty()
        val password = prefs.getString(KEY_PASSWORD, null).orEmpty()
        return if (account.isBlank() || password.isBlank()) null else account to password
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        private const val FILE_NAME = "refresh_credentials"
        private const val KEY_ACCOUNT = "zf_account"
        private const val KEY_PASSWORD = "zf_password"
    }
}

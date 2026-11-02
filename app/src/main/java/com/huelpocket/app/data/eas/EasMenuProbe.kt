package com.huelpocket.app.data.eas

import com.huelpocket.app.core.AppError
import com.huelpocket.app.data.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 教务菜单侦察器（只读）。
 *
 * 任务五模块 C 的第一步：HUEL 的正方路径与通用版有差异（课表路径已被证实换过），
 * 成绩 / 考试的真实路径**不猜**——用当前会话拉 `index_initMenu.html`，
 * 把 HTML 里全部 `clickMenu(gnmkdm, path, name)` 解析出来，写 logcat 供核对，
 * 并在诊断页展示。后续成绩 / 考试数据接口以这份证据为准落地。
 */
class EasMenuProbe(
    private val client: OkHttpClient,
    private val tokenStore: TokenStore,
) {

    data class MenuItem(
        val gnmkdm: String,
        val path: String,
        val name: String,
    )

    suspend fun fetchMenu(): List<MenuItem> = withContext(Dispatchers.IO) {
        val sessionId = tokenStore.zfSessionId.orEmpty()
        val url = "https://xk.huel.edu.cn/jwglxt/xtgl/index_initMenu.html"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Cookie", "JSESSIONID=$sessionId")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val html = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AppError.Server(response.code, "菜单侦察失败（HTTP ${response.code}）")
            }
            if (html.contains("id=\"csrftoken\"")) {
                throw AppError.ReLoginRequired("教务会话已失效，请重新登录后重试")
            }

            val pattern = Regex("""clickMenu\(\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*\)""")
            val items = pattern.findAll(html).map { match ->
                MenuItem(
                    gnmkdm = match.groupValues[1],
                    path = match.groupValues[2],
                    name = match.groupValues[3],
                )
            }.toList()

            // 证据落 logcat：脱敏（菜单不含个人信息）
            items.forEach { item ->
                android.util.Log.i(TAG, "菜单侦察: ${item.gnmkdm} | ${item.path} | ${item.name}")
            }
            android.util.Log.i(TAG, "菜单侦察: 共 ${items.size} 项")
            items
        }
    }

    private companion object {
        const val TAG = "HUELPocket"
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

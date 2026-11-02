package com.huelpocket.app.data.ecard

import com.huelpocket.app.core.AppError
import com.huelpocket.app.core.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request

/**
 * HUEL 一卡通（新中新「慧新E校」）直连客户端（只读查询）。
 *
 * **方案（2026-10-29 简化）**：登录协议整条删除（fetchKeyboard/login/键盘映射）——
 * App 不实现登录、不保存密码；会话 token 由官方 WebView 页面登录后回收
 * （adoptWebViewToken），本类只做带 token 的只读查询。
 *
 * ## 历史登录协议考古（已删除，供追溯）
 *
 * 数据源：/plat/login 页面引用 app.8060e4dc.js + 懒加载分包 login.fd61fb35.js、
 * chunk-2d0f0054.f0699443.js（安全键盘组件）；GET /berserker-app/frontInfo?synAccessSource=h5
 * 实抓 HUEL 登录配置 getFrontConfig.loginType = [{key:"snoNew", encryption:"keyboard",
 * openCaptcha:0}, {key:"id", ...}]。
 *
 * ### 安全键盘映射（登录失败的真正根因，2026-10-29 PC 实抓确认）
 *
 * keyboard 响应里每组图片数组与映射串一一对应，**映射串不是明文字符集**：
 * - `numberKeyboardImage[i]` 显示数字 `i`（10 张，PC 解码图片确认），
 *   点它实际提交 `numberKeyboard[i]`（如 "L)60*Hj^f&" 这样的乱序串）；
 * - `lowerLetterKeyboardImage[i]` 显示 QWERTY 布局第 i 位小写字母
 *   （q w e r t y u / i o p a s d f / g h j k l z x / c v b n m，拼图 OCR 确认），
 *   提交 `lowerLetterKeyboard[i]`；大写同理（upperLetter*）；
 * - `symbolKeyboardImage[i]` 显示固定符号序列
 *   `* \ - [ ] { } / ! , < > ? ~ & @ # . : + | 反引号 % ' $ ; ^ " _`（29 张拼图 OCR 确认），
 *   提交 `symbolKeyboard[i]`。
 *
 * 键盘组件（chunk-2d0f0054 click()）每点一键 `$emit("input", 映射字符, uuid)`，
 * 最终 login.fd61fb35.js submitLogin：`password = 映射串拼接 + "$1$" + keyboardUuid`。
 * **提交的绝不是用户明文**——服务端用 uuid 反查映射还原真实密码再校验。
 * 旧实现提交「明文+$1$+uuid」，无论 device_token/键盘 type 怎么改都必然报
 * 「用户名或密码错误」。keyboard 响应自带的 `password` 字段
 * （= numberKeyboard + "$1$" + uuid）就是官方留的格式样例，佐证此结论。
 *
 * ### 其他字段（均实测/有出处）
 * - 键盘请求：`GET berserker-secure/keyboard?type=Standard&order=0`
 *   （chunk-2d0f0054 getKeyboard：types=="0"→Number 否则 Standard；登录页
 *   keyboardOptions={types:"1",order:0}，HUEL loginType 未配 keyboardType → 默认 "1"）；
 * - `logintype = loginType.key = "snoNew"`；`loginFrom = agentType = "h5"`；
 * - `device_token = "h5"`（login.js data() 初始值；getToken() 的 uuidType/appImei/openid
 *   三分支在纯浏览器 H5 全不命中——uuidType 仅官方 App 容器经 URL 注入）；
 * - `grant_type="password"`、`scope="all"`；snoNew 的 username 原文提交；
 * - 会话不依赖 Cookie：PC 实测全程服务端不下发 Set-Cookie，键盘会话纯靠 uuid 关联
 *   （CardCookieJar 合并式保留，无害且防未来服务端改回 cookie 会话）。
 *
 * ## 鉴权与余额
 * - 业务请求带自定义头 `synjones-auth: bearer <access_token>`（不是 Authorization）；
 * - 余额公式（HUEL type=1）：balance = (db_balance + unsettle_amount) / 100；
 * - 无 refresh_token 机制：token 过期返回 401，需用保存的凭证静默重登。
 */
class SynjonesClient(
    private val client: okhttp3.OkHttpClient = HttpClients.card,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    private val baseUrl = "https://yktwx2.huel.edu.cn"

    private val browserUa = "Mozilla/5.0 (Linux; Android 14; V2307A) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 卡片信息（queryCard 解析结果） */
    data class CardInfo(
        val name: String,
        val account: String,
        /**
         * 账户余额（元）。**官方口径 = elec_accamt/100**：
         * 官方充值页「账户余额」实测 ¥0.33 与 elec_accamt=33 分吻合（2026-10-29 真机日志）；
         * HUEL 的 db_balance/unsettle_amount（卡基账户）恒为 0，资金全在电子账户。
         */
        val balanceYuan: Double,
        val expiredDate: String?,
        val lost: Boolean,
    )

    /** 旧版 queryCard 返回型（保留给 UI 兼容层 toGeneric 折叠） */
    sealed class CardResult {
        data class Success(val info: CardInfo) : CardResult()
        data object Unauthorized : CardResult()
        data class Failed(val message: String) : CardResult()
    }

    /** 通用带鉴权 GET：401/鉴权失败折叠为 [CardResult2.Unauthorized]，解析交给 [parse] */
    private suspend fun <T> authedGet(
        request: Request,
        parse: (kotlinx.serialization.json.JsonObject) -> T,
    ): CardResult2<T> = withContext(Dispatchers.IO) {
        runCatching { authedGetInner(request, parse) }.getOrElse {
            // 网络层异常（SSL 握手失败 / 超时 / 断网）统一折叠为业务失败，绝不冒泡到主线程
            android.util.Log.i("HUELPocket", "一卡通网络异常: ${it.javaClass.simpleName} ${it.message}")
            CardResult2.Failed("网络异常，请检查网络后重试")
        }
    }

    private suspend fun <T> authedGetInner(
        request: Request,
        parse: (kotlinx.serialization.json.JsonObject) -> T,
    ): CardResult2<T> = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401 || body.contains("鉴权失败")) {
                return@withContext CardResult2.Unauthorized
            }
            if (!response.isSuccessful) {
                return@withContext CardResult2.Failed("查询失败（HTTP ${response.code}）")
            }
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return@withContext CardResult2.Failed("响应解析失败")
            val value = runCatching { parse(root) }.getOrElse {
                return@withContext CardResult2.Failed("数据解析失败：${it.message}")
            }
            CardResult2.Success(value)
        }
    }

    /** 业务结果：与登录无关的通用三态 */
    sealed interface CardResult2<out T> {
        data class Success<T>(val value: T) : CardResult2<T>
        data object Unauthorized : CardResult2<Nothing>
        data class Failed(val message: String) : CardResult2<Nothing>
    }

    /** 查卡片列表（余额）；401 / 鉴权失败文案统一折叠为 [CardResult.Unauthorized] */
    suspend fun queryCard(accessToken: String): CardResult = withContext(Dispatchers.IO) {
        runCatching { queryCardInner(accessToken) }.getOrElse {
            android.util.Log.i("HUELPocket", "一卡通网络异常: ${it.javaClass.simpleName} ${it.message}")
            CardResult.Failed("网络异常，请检查网络后重试")
        }
    }

    private suspend fun queryCardInner(accessToken: String): CardResult = withContext(Dispatchers.IO) {
        val tQueryStart = System.currentTimeMillis()
        val request = Request.Builder()
            // 出处：axios 拦截器 get 请求 params 注入 synAccessSource（官方 H5 每个请求都带）
            .url("$baseUrl/berserker-app/ykt/tsm/queryCard?synAccessSource=h5")
            .header("synjones-auth", "bearer $accessToken")
            .header("synAccessSource", "h5")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401 || body.contains("鉴权失败")) {
                return@withContext CardResult.Unauthorized
            }
            if (!response.isSuccessful) {
                return@withContext CardResult.Failed("查询失败（HTTP ${response.code}）")
            }

            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return@withContext CardResult.Failed("响应解析失败")
            val cards = runCatching {
                root["data"]?.jsonObject?.get("card")?.jsonArray
            }.getOrNull()
            if (cards == null || cards.isEmpty()) {
                val message = root["message"]?.jsonPrimitive?.content ?: "未找到校园卡信息"
                return@withContext CardResult.Failed(message)
            }

            // 诊断日志：只打数值字段（余额类/lostflag/expdate）与卡数量，
            // 不打 name/account（logcat 全局可读，避免泄露个人信息）
            cards.forEachIndexed { idx, element ->
                runCatching {
                    val c = element.jsonObject
                    android.util.Log.i(
                        "HUELPocket",
                        "queryCard 卡片[$idx/${cards.size}] db_balance=${c["db_balance"]} " +
                            "unsettle_amount=${c["unsettle_amount"]} elec_accamt=${c["elec_accamt"]} " +
                            "debitamt=${c["debitamt"]} lostflag=${c["lostflag"]} expdate=${c["expdate"]}",
                    )
                }
            }

            android.util.Log.i(
                "HUELPocket",
                "一卡通耗时: queryCard=${System.currentTimeMillis() - tQueryStart}ms 响应体=${root.toString().length}B",
            )
            val card = cards[0].jsonObject
            val info = runCatching {
                fun longOf(key: String): Long = card[key]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: card[key]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong() ?: 0L

                val dbBalance = longOf("db_balance")
                val unsettle = longOf("unsettle_amount")
                val elecAcc = longOf("elec_accamt")
                CardInfo(
                    name = card["name"]?.jsonPrimitive?.content.orEmpty(),
                    account = card["account"]?.jsonPrimitive?.content.orEmpty(),
                    balanceYuan = elecAcc / 100.0,
                    expiredDate = card["expdate"]?.jsonPrimitive?.content,
                    lost = longOf("lostflag") != 0L,
                )
            }.getOrElse { return@withContext CardResult.Failed("卡片数据解析失败") }
            CardResult.Success(info)
        }
    }

    private companion object {
        // 新中新慧新E校产品线通用客户端凭证（任务三文档 2.3，公开硬编码值）
        const val CLIENT_CREDENTIALS_BASIC =
            "Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0"

    }
}

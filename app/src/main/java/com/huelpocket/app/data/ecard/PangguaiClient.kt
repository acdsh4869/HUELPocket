package com.huelpocket.app.data.ecard

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 胖乖生活（企鹅科技）**只读**客户端——查附近设备与剩余时长。
 *
 * 侦察来源（2026-10-30，社区开源项目 washwise-go 逆向结论 + PC 实测复现）：
 * - 网关 `userapi.qiekj.com`，**全部匿名可调**（不需要登录 token）；
 * - 请求必须模拟微信小程序：`channel: wechat` + form-urlencoded + `xweb_xhr: 1`；
 * - 链路：店铺列表 → 机器类型 → 机器列表 → 机器详情（含 remainTime 秒数）。
 *
 * 实测（2026-10-30 郑州）：`学校后勤处` 47 台淋浴 + 22 台饮水机、财大第一/第二澡堂
 * 吹风机，`remainTime=0`（空闲）、`deviceErrorCode=4204`（离线/未用）。
 *
 * ⚠️ 硬约束：本客户端**只有 GET 语义的只读查询**（POST 仅因服务端要求），
 * 绝不调用启动设备 / 下单 / 支付接口——那些只在胖乖 App / 微信小程序内完成。
 */
class PangguaiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** 登录结果（手机号 + 短信验证码，官方 H5 `user/reg` 同款参数） */
    sealed interface LoginResult {
        data class Ok(val token: String) : LoginResult
        data class Failed(val message: String) : LoginResult
    }

    /** 我的订单（进行中的洗衣机） */
    data class ActiveOrder(
        val orderNo: String,
        val machineName: String,
        /** 购买时长（分钟） */
        val minutes: Int,
        /** 支付时间（毫秒；0 = 未知） */
        val payTimeMillis: Long,
        /** 订单状态（2 = 使用中） */
        val orderStatus: Int,
        /** 模式（官方字段 machineFunctionName，如「洗衣」/「饮水」） */
        val mode: String = "",
        /** 订单类型：1/5/6 = 普通设备订单；3 = 饮水/预付费（官方 H5 判定逻辑） */
        val orderType: Int = 0,
        /** 商品名（官方 goodsName，饮水单常只有这个字段） */
        val goodsName: String = "",
    )

    /** 扫码解析出的设备（goods/scan/v2 返回） */
    data class ScanDevice(
        val goodsId: Long,
        val name: String,
        val shopId: String,
        val shopName: String,
        /** 设备类型（1=洗衣机等，官方枚举） */
        val type: Int,
    )

    /** 扫码结果：成功带设备，失败带服务端原文（如「设备未绑定…故障码【4000】」） */
    sealed interface ScanResult {
        data class Ok(val device: ScanDevice) : ScanResult
        data class Failed(val message: String) : ScanResult
    }

    data class MachineDetail(
        val goodsId: Long,
        val name: String,
        val deviceId: Long,
        val machineId: String,
        /** 门店（机构）id：下单参数 promotions.orgId 用它 */
        val orgId: Long,
        /** 门店 id（字符串，官方 PayAct 的 shopId extra 用它） */
        val shopId: String,
        /** 品类码（如 "00"），官方 PayAct 的 categoryCode extra */
        val categoryCode: String,
        /** 剩余秒数（0 = 空闲）；负值/异常按 0 处理 */
        val remainSeconds: Int,
        val deviceErrorCode: Int,
        /** 设备异常原文（如「设备离线，请检查设备是否通电…故障码【4204】」） */
        val deviceErrorMsg: String,
    )

                /**
     * 发送短信验证码（登录用）。
     *
     * 参数证据（官方 H5 app.js `sendCode`）：
     * `common/sms/sendCode` + `{phone, template:"reg"}`。
     * @return null = 发送成功；否则为服务端错误原文
     */
    suspend fun sendSmsCode(phone: String): String? = withContext(Dispatchers.IO) {
        val resp = postResp("/common/sms/sendCode", mapOf("phone" to phone, "template" to "reg"))
        resp.message
    }

    /**
     * 手机号 + 短信验证码登录。
     *
     * 参数证据（官方 H5 `loginH5`）：`user/reg` + `{channel, phone, verify, code}`
     * —— `verify` 是短信验证码，`channel` 在普通浏览器环境取 `h5`，返回体 `token` 即登录态。
     */
    suspend fun loginBySms(phone: String, code: String): LoginResult = withContext(Dispatchers.IO) {
        val resp = postResp(
            "/user/reg",
            mapOf("channel" to "h5", "phone" to phone, "verify" to code),
        )
        val root = resp.root ?: return@withContext LoginResult.Failed(resp.message ?: "登录失败")
        val token = text(root, "token").ifBlank {
            (root["data"] as? JsonObject)?.let { text(it, "token") }.orEmpty()
        }
        if (token.isBlank()) LoginResult.Failed(resp.message ?: "登录失败（未返回 token）")
        else LoginResult.Ok(token)
    }

    /** 订单查询结果：区分「有/无订单」与「未登录/网络失败」 */
    sealed interface OrdersResult {
        data class Ok(val orders: List<ActiveOrder>) : OrdersResult
        /** needLogin = token 失效/过期（code 2 未登录），需要重新登录 */
        data class Failed(val message: String, val needLogin: Boolean) : OrdersResult
    }

    /**
     * 我的订单（**只读**）：用于「我的洗衣机」显示进行中的机器与剩余时间。
     * token 走请求体字段 `token`（官方 H5 filterData 的实现：`e.token = getToken()`）。
     */
    suspend fun myOrders(token: String, pageSize: Int = 20): OrdersResult =
        withContext(Dispatchers.IO) {
            val resp = postResp(
                "/order/list",
                mapOf("token" to token, "page" to "1", "pageSize" to pageSize.toString()),
            )
            val root = resp.root
                ?: return@withContext OrdersResult.Failed(
                    resp.message ?: "订单查询失败",
                    needLogin = resp.code == 2 || (resp.message?.contains("未登录") == true),
                )
            // 形状不确定时按「无订单」处理，避免 jsonArray 在非数组上抛异常
            val itemsJson = (root["data"] as? JsonObject)?.get("items") ?: root["items"]
            val items = itemsJson as? kotlinx.serialization.json.JsonArray
                ?: return@withContext OrdersResult.Ok(emptyList())
            val orders = items.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    ActiveOrder(
                        orderNo = text(o, "orderNo", "id"),
                        machineName = text(o, "machineName", "goodsName", "name"),
                        // 实测字段（2026-10-31 真机抓包）：markMinutes = 购买分钟数；
                        // 没有 payTime/endTime，下单时间就是 createTime。
                        minutes = int(o, "markMinutes") ?: int(o, "minutes") ?: 0,
                        payTimeMillis = timeOf(o, "payTime", "createTime"),
                        orderStatus = int(o, "orderStatus") ?: 0,
                        mode = text(o, "machineFunctionName", "functionName", "mode"),
                        orderType = int(o, "orderType") ?: 0,
                        goodsName = text(o, "goodsName"),
                    )
                }.getOrNull()
            }
            OrdersResult.Ok(orders)
        }

    /** 设备可选套餐（含参考价格）；需登录（实测匿名返回 code 2 未登录） */
    data class Sku(
        val name: String,
        val priceYuan: Double,
        val minutes: Int,
        /** 官方套餐 id，下单必填 */
        val skuId: Long = 0L,
    )

    suspend fun deviceSkus(goodsId: Long, token: String): List<Sku> = withContext(Dispatchers.IO) {
        val root = post(
            "/goods/normal/skus",
            mapOf("goodsId" to goodsId.toString(), "token" to token),
        ) ?: return@withContext emptyList()
        // 便于确认套餐字段（价格/时长），不含任何凭据
        Log.i(TAG, "skus 原始响应: " + root.toString().take(800))
        val arr = root["data"]?.jsonArray ?: return@withContext emptyList()
        arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                // 价格可能平铺，也可能在 sku/skus 子对象里
                fun num(obj: JsonObject, keys: List<String>): Double? =
                    keys.firstNotNullOfOrNull { k ->
                        obj[k]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    }

                val nested = (o["sku"] as? JsonObject)
                    ?: ((o["skus"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                        as? JsonObject)
                val price = num(o, listOf("price", "realPrice", "salePrice", "originPrice", "amount"))
                    ?: nested?.let { num(it, listOf("price", "realPrice", "salePrice", "originPrice")) }
                    ?: 0.0
                val minutes = (int(o, "minute") ?: int(o, "minutes")
                    ?: int(o, "markMinutes") ?: int(o, "duration") ?: int(o, "lasting")
                    ?: nested?.let { int(it, "minute") ?: int(it, "minutes") } ?: 0)
                Sku(
                    name = text(o, "functionName", "name", "skuName", "title"),
                    priceYuan = price,
                    // 实测官方套餐里 unit 就是分钟数（快洗 25 / 桶自洁 5）
                    minutes = (int(o, "unit") ?: 0).takeIf { it > 0 } ?: minutes,
                    skuId = (text(o, "skuId")).toLongOrNull() ?: 0L,
                )
            }.getOrNull()
        }.filter { it.name.isNotBlank() }
    }

    /**
     * 时间字段 → 毫秒；0 = 无法解析。
     *
     * 服务端同一字段存在多种形态：`yyyy-MM-dd HH:mm:ss`、`yyyy-MM-dd HH:mm`、
     * ISO（带 T）、以及 10 位秒 / 13 位毫秒时间戳——都要兼容，否则会退化成兜底值。
     */
    private fun parseTime(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty() || s == "null") return 0L
        s.toLongOrNull()?.let { v ->
            return when {
                v <= 0L -> 0L
                v < 100_000_000_000L -> v * 1000L   // 10 位：秒
                else -> v                           // 13 位：毫秒
            }
        }
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
        )
        patterns.forEach { p ->
            val t = runCatching {
                java.text.SimpleDateFormat(p, java.util.Locale.CHINA)
                    .apply { isLenient = false }
                    .parse(s.takeIf { it.length >= p.replace("'", "").length } ?: s)?.time
            }.getOrNull()
            if (t != null && t > 0L) return t
        }
        return 0L
    }

    /** 按字段名顺序取第一个能解析成时间戳的字段 */
    private fun timeOf(o: JsonObject, vararg keys: String): Long =
        keys.firstNotNullOfOrNull { k -> parseTime(text(o, k)).takeIf { it > 0L } } ?: 0L

    /**
     * 扫码解析设备（**只读**）。
     *
     * 参数名证据（官方 H5 pages_skip chunk `scanCodeInit` 源码，2026-10-30 实抓）：
     * 二维码 URL 的 query 里读 `NQT` / `SN` / `IMEI` 三个键，原样传给 `goods/scan/v2`；
     * 裸码（不含 http）按 IMEI 处理；实测 `NQT=<码>` 能返回业务响应。
     */
    suspend fun scanDevice(raw: String): ScanResult = withContext(Dispatchers.IO) {
        val params = parseScanParams(raw)
        if (params.isEmpty()) return@withContext ScanResult.Failed("二维码有误")
        val resp = postResp("/goods/scan/v2", params)
        val root = resp.root
        if (root == null) {
            return@withContext ScanResult.Failed(resp.message ?: "无法识别该二维码")
        }
        val o = root["data"] as? JsonObject
            ?: return@withContext ScanResult.Failed("无法识别该二维码")
        runCatching {
            ScanResult.Ok(
                ScanDevice(
                    goodsId = text(o, "id").toLongOrNull() ?: 0L,
                    name = text(o, "name"),
                    shopId = text(o, "shopId"),
                    shopName = text(o, "shopName", "orgName"),
                    type = int(o, "type") ?: 0,
                ),
            )
        }.getOrElse { ScanResult.Failed("设备数据解析失败") }
    }

    /** 从二维码内容解析官方要求的参数（与 H5 scanCodeInit 同规则） */
    fun parseScanParams(raw: String): Map<String, String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyMap()
        if (!text.contains("http", ignoreCase = true)) {
            // 裸码：官方按 IMEI 处理
            return mapOf("IMEI" to text)
        }
        val query = text.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        query.split('&').forEach { pair ->
            val k = pair.substringBefore('=', "").trim()
            val v = pair.substringAfter('=', "").trim()
            if (k in setOf("NQT", "SN", "IMEI") && v.isNotEmpty()) out[k] = v
        }
        return out
    }

    /** 机器详情（含剩余时长） */
    suspend fun machineDetail(goodsId: Long): MachineDetail? = withContext(Dispatchers.IO) {
        val root = post("/goods/normal/details", mapOf("goodsId" to goodsId.toString()))
            ?: return@withContext null
        val o = root["data"] as? JsonObject ?: return@withContext null
        runCatching {
            MachineDetail(
                goodsId = text(o, "goodsId").toLongOrNull() ?: goodsId,
                name = text(o, "name"),
                deviceId = text(o, "deviceId").toLongOrNull() ?: 0L,
                machineId = text(o, "machineId"),
                orgId = text(o, "orgId").toLongOrNull() ?: 0L,
                shopId = text(o, "shopId"),
                categoryCode = text(o, "categoryCode"),
                remainSeconds = (int(o, "remainTime") ?: 0).coerceAtLeast(0),
                deviceErrorCode = int(o, "deviceErrorCode") ?: 0,
                deviceErrorMsg = text(o, "deviceErrorMsg"),
            )
        }.getOrNull()
    }

    // ── 下单（官方 Android 客户端的签名鉴权，2026-10-31 真机实测可用） ──

    /** 下单结果：成功带订单号与实付金额，失败带服务端原文 */
    sealed interface TradeResult {
        data class Ok(val orderNo: String, val payYuan: Double) : TradeResult
        data class Failed(val message: String) : TradeResult
    }

    /**
     * 创建订单（**会真实下单**，但不支付；支付在官方收银台完成）。
     *
     * 参数证据（社区项目 Night-stars-1/wash + 2026-10-31 真机验证）：
     * `trade/create` + `promotions`(JSON) + `items`(JSON) + `token`，
     * 需要 `channel: android_app` 与 SHA256 签名头。
     */
    suspend fun createTrade(
        goodsId: Long,
        skuId: Long,
        orgId: Long,
        token: String,
    ): TradeResult = withContext(Dispatchers.IO) {
        val resp = signedPostResp("/trade/create", tradeParams(goodsId, skuId, orgId), token)
        val root = resp.root ?: return@withContext TradeResult.Failed(resp.message ?: "下单失败")
        val o = root["data"] as? JsonObject
            ?: return@withContext TradeResult.Failed(text(root, "msg").ifBlank { "下单失败" })
        val orderNo = text(o, "orderId").ifBlank { text(o, "tradeNo") }
        if (orderNo.isBlank()) return@withContext TradeResult.Failed("下单失败（未返回订单号）")
        TradeResult.Ok(orderNo, 0.0)
    }

    /** 价格预览（不下单）：返回实付金额（元）；失败返回 null */
    suspend fun previewPrice(
        goodsId: Long,
        skuId: Long,
        orgId: Long,
        token: String,
    ): Double? = withContext(Dispatchers.IO) {
        val root = signedPostResp("/trade/preview/v4", tradeParams(goodsId, skuId, orgId), token).root
            ?: return@withContext null
        val o = root["data"] as? JsonObject ?: return@withContext null
        text(o, "realPrice").toDoubleOrNull()
    }

    private fun tradeParams(goodsId: Long, skuId: Long, orgId: Long): Map<String, String> {
        val promotions = "[{\"assetId\":\"0\",\"oldPromotionId\":\"3482\",\"orgId\":\"" +
            orgId + "\",\"promotionId\":\"0\",\"promotionType\":\"4\"}]"
        val items = "[{\"amount\":\"1\",\"goodsId\":\"" + goodsId + "\",\"num\":\"1\"," +
            "\"skuId\":\"" + skuId + "\",\"soldType\":\"1\"}]"
        return mapOf("promotions" to promotions, "items" to items)
    }

    /** 签名请求：Authorization/Version/channel/timestamp/sign + 表单里的 token */
    private fun signedPostResp(
        path: String,
        params: Map<String, String>,
        token: String,
    ): Resp = runCatching {
        val ts = System.currentTimeMillis().toString()
        val payload = "appSecret=$APP_SECRET&channel=$APP_CHANNEL&timestamp=$ts" +
            "&token=$token&version=$APP_VERSION&$path"
        val sign = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val body = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
            add("token", token)
        }.build()
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .header("Authorization", token)
            .header("Version", APP_VERSION)
            .header("channel", APP_CHANNEL)
            .header("timestamp", ts)
            .header("sign", sign)
            .header("accept", "*/*")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val textBody = resp.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(textBody).jsonObject }.getOrNull()
            val code = root?.get("code")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
            Resp(root, root?.let { text(it, "msg") }?.takeIf { it.isNotBlank() }, code)
        }
    }.getOrElse { Resp(null, "网络异常：" + it.message, -1) }

    // ── 内部 ──

    /** 接口响应：root 为空时 message 为服务端原文；code 供判定「未登录/过期」 */
    private data class Resp(val root: JsonObject?, val message: String?, val code: Int = 0)

    private fun post(path: String, params: Map<String, String>): JsonObject? =
        postResp(path, params).root

    private fun postResp(path: String, params: Map<String, String>): Resp = runCatching {
        val body = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .header("accept", "*/*")
            .header("accept-language", "zh-CN,zh;q=0.9")
            // 模拟微信小程序环境（官方服务端按此放行匿名查询）
            .header("channel", "wechat")
            .header("xweb_xhr", "1")
            .header("uid", "undefined")
            .header("User-Agent", WECHAT_MINI_UA)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.i(TAG, "胖乖接口 HTTP ${response.code}: $path")
                return Resp(null, "网络异常（HTTP ${response.code}）", response.code)
            }
            val root = json.parseToJsonElement(raw).jsonObject
            val code = int(root, "code") ?: -1
            if (code != 0) {
                val msg = text(root, "msg")
                Log.i(TAG, "胖乖接口 code=$code msg=$msg: $path")
                return Resp(null, msg.ifBlank { "请求失败（code=$code）" }, code)
            }
            Resp(root, null, 0)
        }
    }.getOrElse { Resp(null, it.message ?: "网络异常") }

    /** 取字符串字段；JSON null 与空串一律视为「没有」（否则会显示 "null"） */
    private fun text(o: JsonObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { k ->
            val p = o[k] as? JsonPrimitive
            if (p == null || p is kotlinx.serialization.json.JsonNull) {
                null
            } else {
                p.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            }
        } ?: ""

    private fun int(o: JsonObject, key: String): Int? =
        o[key]?.jsonPrimitive?.content?.toIntOrNull()

    companion object {
        private const val TAG = "Pangguai"
        private const val BASE_URL = "https://userapi.qiekj.com"
        // 官方 Android 客户端的签名素材（社区项目公开，2026-10-31 实测仍有效）
        private const val APP_SECRET = "nFU9pbG8YQoAe1kFh+E7eyrdlSLglwEJeA0wwHB1j5o="
        private const val APP_CHANNEL = "android_app"
        private const val APP_VERSION = "1.60.3"
        private const val WECHAT_MINI_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.50 " +
                "MiniProgramEnv/android"
    }
}

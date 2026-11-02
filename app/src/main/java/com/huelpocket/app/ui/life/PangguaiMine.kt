package com.huelpocket.app.ui.life

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.huelpocket.app.data.ecard.PangguaiClient
import com.huelpocket.app.ui.components.GlassCard
import com.huelpocket.glassui.GlassButton
import com.huelpocket.glassui.GlassButtonStyle
import com.huelpocket.glassui.GlassIconButton
import com.huelpocket.glassui.GlassTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 胖乖账号令牌（本机加密存储，仅 token + 手机号，不存密码、不打日志）。
 *
 * 与一卡通 [com.huelpocket.app.data.ecard.CardCredentialVault] 同标准：
 * EncryptedSharedPreferences + Tink 主密钥（Android Keystore 保护），
 * 只留本机，不进日志/备份/网络上传。
 */
internal class PangguaiAuth(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "pangguai_auth",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun readToken(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun readPhone(): String = prefs.getString(KEY_PHONE, "").orEmpty()

    fun save(token: String, phone: String) {
        prefs.edit().putString(KEY_TOKEN, token).putString(KEY_PHONE, phone).apply()
    }

    /** 清除本地凭据（token + 手机号） */
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_PHONE).apply()
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_PHONE = "phone"
    }
}

/** 登录状态（任务要求三态） */
enum class WashLoginStatus { NotLoggedIn, LoggedIn, Expired }

/** 「我的洗衣机」一行（设备状态表） */
data class MyMachineRow(
    val machineName: String,
    /** 抓取时的剩余秒数（界面用本地时钟递减，不每秒请求）；-1 = 官方未给结束时间 */
    val remainSeconds: Int,
    /** 预计结束时间（毫秒；0 = 未知），用于排本地「洗完提醒」 */
    val endAtMillis: Long = 0L,
)

data class PangguaiMineUiState(
    val status: WashLoginStatus = WashLoginStatus.NotLoggedIn,
    val phone: String = "",
    val loading: Boolean = false,
    val sendingCode: Boolean = false,
    val codeSent: Boolean = false,
    val machines: List<MyMachineRow> = emptyList(),
    /** 数据抓取时刻（本地倒计时基准） */
    val fetchedAt: Long = 0L,
    /** 本次刷新失败 → 展示缓存并标注「数据可能延迟」 */
    val stale: Boolean = false,
    val message: String? = null,
    /** 轮询间隔（秒）：失败自动降频 30 → 60 → 120 */
    val pollSeconds: Int = 30,
    /** 本机倒计时（按模式时长自己开的，不依赖官方订单） */
    val localWashes: List<LocalWash> = emptyList(),
    /** 付款成功后的「关门」提示条；按订单号只显示一次 */
    val doorNotice: String? = null,
)

/**
 * 胖乖登录 + 我的洗衣机（**只读**）。
 *
 * 接口（证据见 [PangguaiClient] 注释）：
 * - 发码 `common/sms/sendCode`；登录 `user/reg`（channel=h5）
 * - 订单 `order/list`（token 走请求体字段）
 *
 * 安全边界：只做读取，不创建订单、不调支付、不存支付凭证；token 只在本机加密存储。
 */
class PangguaiMineViewModel : ViewModel() {

    private val client = PangguaiClient()
    private var auth: PangguaiAuth? = null
    private val _state = MutableStateFlow(PangguaiMineUiState())
    val state: StateFlow<PangguaiMineUiState> = _state.asStateFlow()

    private fun auth(context: Context): PangguaiAuth =
        auth ?: PangguaiAuth(context.applicationContext).also { auth = it }

    /** 启动：恢复登录态（有 token 即视为已登录，失效由首次查询判定） */
    fun init(context: Context) {
        // 迁移：把现存计时器的 orderNo 补写进订单台账（不重置计时器）
        WashTimerStore.migrateToLedger(context)
        dumpLedger(context)
        _state.value = _state.value.copy(localWashes = WashTimerStore.read(context))
        val a = auth(context)
        val token = a.readToken()
        val phone = a.readPhone()
        if (token == null) {
            _state.value = _state.value.copy(status = WashLoginStatus.NotLoggedIn, phone = phone)
            return
        }
        _state.value = _state.value.copy(status = WashLoginStatus.LoggedIn, phone = phone)
        refresh(context)
    }

    fun sendCode(context: Context, phone: String) {
        if (_state.value.sendingCode) return
        if (!phone.matches(Regex("^1\\d{10}$"))) {
            _state.value = _state.value.copy(message = "请输入 11 位手机号")
            return
        }
        _state.value = _state.value.copy(sendingCode = true, message = null)
        viewModelScope.launch {
            val err = client.sendSmsCode(phone)
            _state.value = _state.value.copy(
                sendingCode = false,
                codeSent = err == null,
                message = err ?: "验证码已发送",
            )
        }
    }

    fun login(context: Context, phone: String, code: String) {
        if (_state.value.loading) return
        if (!phone.matches(Regex("^1\\d{10}$"))) {
            _state.value = _state.value.copy(message = "请输入 11 位手机号")
            return
        }
        if (code.isBlank()) {
            _state.value = _state.value.copy(message = "请输入验证码")
            return
        }
        _state.value = _state.value.copy(loading = true, message = null)
        viewModelScope.launch {
            when (val r = client.loginBySms(phone, code)) {
                is PangguaiClient.LoginResult.Ok -> {
                    auth(context).save(r.token, phone)
                    _state.value = _state.value.copy(
                        loading = false,
                        status = WashLoginStatus.LoggedIn,
                        phone = phone,
                        message = null,
                    )
                    refresh(context)
                }
                is PangguaiClient.LoginResult.Failed ->
                    _state.value = _state.value.copy(loading = false, message = r.message)
            }
        }
    }

    /** 刷新进行中订单（只读）；失败保留缓存并降频 */
    fun refresh(context: Context) {
        val token = auth(context).readToken()
        if (token == null) {
            _state.value = _state.value.copy(status = WashLoginStatus.NotLoggedIn, machines = emptyList())
            return
        }
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            when (val r = client.myOrders(token)) {
                is PangguaiClient.OrdersResult.Ok -> {
                    val now = System.currentTimeMillis()
                    val washers = r.orders.filter { isRunningWasher(it) }
                    val rows = washers.map { o ->
                        val rowName = o.machineName.ifBlank { o.goodsName }
                            .ifBlank { "订单 " + o.orderNo.takeLast(4) }
                        // 结束时间 = 下单时间 + 购买分钟数（实测 order/list 只有 markMinutes/createTime，
                        // 没有 payTime/endTime）。拿不到就记 -1（未知），绝不编一个 45 分钟出来——
                        // 之前用固定 45 分钟兜底，6 分钟的桶自洁显示 45:00、刷新还从 45 重新数。
                        val endAt = if (o.payTimeMillis > 0 && o.minutes > 0) {
                            o.payTimeMillis + o.minutes * 60_000L
                        } else {
                            0L
                        }
                        MyMachineRow(
                            machineName = rowName,
                            remainSeconds = if (endAt > 0) {
                                ((endAt - now) / 1000).toInt().coerceAtLeast(0)
                            } else {
                                -1
                            },
                            endAtMillis = endAt,
                        )
                    }
                    // 官方订单本身就是「支付成功」的证据：有在跑的洗衣机订单、本机还没计时 →
                    // 自动按该模式的时长开一条（开始时间用官方下单时间，倒计时不会从 0 重来）
                    // 顺序很重要：先建 → 再权威复核 → 最后才 prune。
                    // 若 prune 先跑，到点记录被删，reconcile 无从复核，
                    // 「按官方剩余校准」和「标异常」两条路径都会失效。
                    autoStartTimers(context, washers)
                    reconcileTimers(context)
                    WashTimerStore.prune(context)
                    _state.value = _state.value.copy(localWashes = WashTimerStore.read(context))
                    // 只留真正还在跑的：endAt 已过 = 官方还没把状态改完，但机器确实洗完/结束了
                    val running = rows.filter { it.endAtMillis <= 0L || it.endAtMillis > now }
                    _state.value = _state.value.copy(
                        loading = false,
                        status = WashLoginStatus.LoggedIn,
                        machines = running,
                        fetchedAt = now,
                        stale = false,
                        message = null,
                        // 有在跑的洗衣机就 15 秒刷一次（准），空闲时 60 秒够用
                        pollSeconds = if (running.isNotEmpty()) 15 else 60,
                    )
                }
                is PangguaiClient.OrdersResult.Failed -> {
                    if (r.needLogin) {
                        // Token 过期：保留本地凭据但标记过期，提示重新登录（不静默清空用户数据）
                        _state.value = _state.value.copy(
                            loading = false,
                            status = WashLoginStatus.Expired,
                            stale = true,
                            message = "登录已过期，请重新登录",
                        )
                    } else {
                        _state.value = _state.value.copy(
                            loading = false,
                            stale = true,
                            message = r.message,
                            // 失败降频：30 → 60 → 120（上限 120 秒）
                            pollSeconds = (_state.value.pollSeconds * 2).coerceAtMost(120),
                        )
                    }
                }
            }
        }
    }

    /**
     * 用官方订单反推本地倒计时：有「进行中的洗衣机订单」= 确实支付成功了。
     *
     * 不依赖付款页面回调——不管用户是在胖乖 App 里付的、还是微信小程序里付的，
     * 只要订单出现在 order/list 里，倒计时就会按该模式的本地时长自动开始。
     */
    /**
     * 订单 → 本地计时器。
     *
     * 去重唯一判据是 **orderNo**（`WashTimerStore.hasOrder`），不再拿时间戳比较
     * —— 上一版用 `startAt` 和 `payTimeMillis` 比对，改成「首次观测时刻」后差值永远 >5 分钟，
     * 导致每次进页面/重启都重建计时器、倒计时反复回到满格。
     */
    private fun autoStartTimers(context: Context, orders: List<PangguaiClient.ActiveOrder>) {
        val now = System.currentTimeMillis()
        var changed = false
        orders.forEach { o ->
            val mode = o.mode.ifBlank { o.goodsName }
            if (mode.isBlank() || o.payTimeMillis <= 0) return@forEach
            if (now - o.payTimeMillis > 6 * 60 * 60_000L) return@forEach
            val minutes = WashPlanTimes.minutesOf(context, mode, o.minutes)
            if (minutes <= 0) return@forEach
            // 同一订单只建一次（异常单同样保留在库里，永不再建）
            if (WashTimerStore.hasOrder(context, o.orderNo)) return@forEach

            WashTimerStore.start(
                context,
                o.machineName.ifBlank { "洗衣机" },
                mode,
                minutes,
                startAt = now,
                orderNo = o.orderNo,
            )
            // 台账记 started：之后即使计时器被 prune 删除，也不会重复建
            OrderLedger.mark(context, o.orderNo, OrderLedger.STARTED)
            // 关门提醒只在「刚下单 10 分钟内」弹：首次观测最晚可能是付款后 6 小时，
            // 那时再提示「若忘了关门」毫无意义
            if (now - o.payTimeMillis < 10 * 60_000L) {
                val doorKey = "door_remind_" + o.orderNo
                val prefs = context.getSharedPreferences(DOOR_PREFS, android.content.Context.MODE_PRIVATE)
                if (!prefs.getBoolean(doorKey, false)) {
                    prefs.edit().putBoolean(doorKey, true).apply()
                    _state.value = _state.value.copy(doorNotice = "机器已启动——若忘了关门请立即关上。")
                }
            }
            changed = true
        }
        if (changed) {
            _state.value = _state.value.copy(localWashes = WashTimerStore.read(context))
        }
    }

    /**
     * 用官方 `goods/normal/details` 校准本地计时（显示层以官方为权威）。
     *
     * - `remainTime > 0`：按官方剩余时间对齐本地 startAt（轮询间隙仍由本地插值）
     * - `remainTime == 0 && code == 2` 且本地已到点：标记「设备状态异常」，该订单不再重建
     * - 机器恢复空闲：异常计时器移除；正常计时器保持「已结束」
     */
    /** 诊断：把订单台账落到外部私有目录（不含 token），供 adb 核对 */
    private fun dumpLedger(context: Context) {
        runCatching {
            val lines = OrderLedger.dump(context)
            context.getExternalFilesDir(null)?.let { dir ->
                java.io.File(dir, "handled_orders.txt").writeText(
                    "time=" + System.currentTimeMillis() + "\n" + lines.joinToString("\n"),
                )
            }
        }
    }

    /** 诊断：reconcile 的匹配/查询失败原因落盘（不含 token），adb 一次 pull 即可确认路径在跑 */
    private fun traceReconcile(context: Context, machineName: String, orderNo: String, why: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "reconcile_trace.txt")
            // 只保留最近一段，避免无限增长
            if (f.exists() && f.length() > 64 * 1024) f.delete()
            f.appendText(
                "t=" + System.currentTimeMillis() +
                    " machine=[" + machineName + "] order=" + orderNo + " " + why + "\n",
            )
        }
    }

    private suspend fun reconcileTimers(context: Context) {
        val timers = WashTimerStore.read(context)
        if (timers.isEmpty()) return
        val map = PangguaiStore.readMachineGoods(context)
        var changed = false
        // 同一台机器可能挂多条计时器（换单/换模式），details 每轮只查一次，避免重复请求
        val detailCache = HashMap<Long, PangguaiClient.MachineDetail?>()
        timers.forEach { w ->
            // 机器名两端来源不同（订单的 machineName vs 扫码页的 device.name），
            // 格式不一致就会静默 miss，导致校准/标异常/记 DONE 全都不发生——落盘留证据
            val goodsId = map[w.machineName]
            if (goodsId == null) {
                traceReconcile(context, w.machineName, w.orderNo, "map-miss keys=" + map.keys)
                return@forEach
            }
            val d = if (detailCache.containsKey(goodsId)) {
                detailCache[goodsId]
            } else {
                client.machineDetail(goodsId).also { detailCache[goodsId] = it }
            }
            if (d == null) {
                traceReconcile(context, w.machineName, w.orderNo, "detail-null goodsId=" + goodsId)
                return@forEach
            }
            val busy = d.deviceErrorCode == 2 || d.deviceErrorMsg.contains("工作中")
            when {
                d.remainSeconds > 0 -> {
                    if (!w.abnormal) WashTimerStore.calibrate(context, w.orderNo, d.remainSeconds)
                    changed = true
                }
                busy && System.currentTimeMillis() >= w.endAt() && !w.abnormal -> {
                    WashTimerStore.markAbnormal(context, w.orderNo)
                    // 台账标 abnormal：该订单永不再建（清掉计时器也不会复活）
                    OrderLedger.mark(context, w.orderNo, OrderLedger.ABNORMAL)
                    changed = true
                }
                !busy && System.currentTimeMillis() >= w.endAt() -> {
                    // 到点且机器空闲：台账记 done（保留 24h，防同单复活）
                    OrderLedger.mark(context, w.orderNo, OrderLedger.DONE)
                    if (w.abnormal) {
                        WashTimerStore.remove(context, w.id)
                        changed = true
                    }
                }
            }
        }
        if (changed) {
            _state.value = _state.value.copy(localWashes = WashTimerStore.read(context))
        }
    }

    private companion object {
        const val DOOR_PREFS = "door_remind"
    }

    /** 关掉关门提示条（只影响当前显示，不影响「每单一次」的持久记录） */
    fun dismissDoorNotice() {
        _state.value = _state.value.copy(doorNotice = null)
    }

    /** 手动结束一条本地计时 */
    fun removeLocalTimer(context: Context, id: Long) {
        WashTimerStore.remove(context, id)
        _state.value = _state.value.copy(localWashes = WashTimerStore.read(context))
    }

    /** 进行中的洗衣机订单：官方 orderType=3 是饮水/预付费单，名字带饮水/淋浴/吹风/烘干的一律排除 */
    private fun isRunningWasher(o: PangguaiClient.ActiveOrder): Boolean {
        if (o.orderStatus != 2) return false
        val label = o.machineName + o.mode + o.goodsName
        return o.orderType != 3 &&
            !label.contains("饮水") && !label.contains("开水") &&
            !label.contains("淋浴") && !label.contains("吹风") && !label.contains("烘干")
    }

    /** 退出登录：清 token，保留手机号方便下次登录 */
    fun logout(context: Context) {
        auth(context).clear()
        _state.value = PangguaiMineUiState()
    }
}

/** 剩余时长 mm:ss（≤0 显示 00:00） */
/**
 * 发送验证码按钮：**倒计时 state 只活在这个叶子里**。
 *
 * 之前 `countdown` 挂在登录页顶层，每秒减 1 会让整个登录页（两个输入框、提示文案、按钮）
 * 全部重组；现在每秒只重组这个按钮内部的 Text。
 */
@Composable
private fun SmsCodeButton(
    codeSent: Boolean,
    sending: Boolean,
    onClick: () -> Unit,
) {
    var countdown by remember { mutableStateOf(0) }
    LaunchedEffect(codeSent) {
        if (codeSent) {
            countdown = 60
            while (countdown > 0) {
                delay(1_000L)
                countdown -= 1
            }
        }
    }
    GlassButton(onClick = onClick, enabled = countdown == 0 && !sending) {
        Text(if (countdown > 0) "${countdown}s" else "发送验证码")
    }
}

internal fun formatRemainShort(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/**
 * 胖乖登录页（手机号 + 短信验证码）。
 *
 * 接口证据：官方 H5 `common/sms/sendCode`（发码）与 `user/reg`（登录）——
 * 见 [PangguaiClient.sendSmsCode] / [PangguaiClient.loginBySms] 注释。
 * 只保存 token（加密），不保存密码、不写日志。
 */
@Composable
fun PangguaiLoginScreen(
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    vm: PangguaiMineViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var phone by remember { mutableStateOf(state.phone) }
    var code by remember { mutableStateOf("") }

    LaunchedEffect(state.status) {
        if (state.status == WashLoginStatus.LoggedIn) onLoggedIn()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        GlassCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.padding(horizontal = 8.dp).weight(1f)) {
                    Text("登录胖乖生活", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "登录后可看「我的洗衣机」剩余时间（只读，不代下单/支付）",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.colors.textSecondary,
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                        label = { Text("手机号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text("验证码") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        SmsCodeButton(
                            codeSent = state.codeSent,
                            sending = state.sendingCode,
                            onClick = { vm.sendCode(context, phone) },
                        )
                    }
                    state.message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTheme.colors.textSecondary,
                        )
                    }
                    GlassButton(
                        onClick = { vm.login(context, phone, code) },
                        style = GlassButtonStyle.Prominent,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.loading) CircularProgressIndicator(modifier = Modifier.height(16.dp))
                        else Text("登录")
                    }
                }
            }

            Text(
                "验证码由胖乖官方短信下发；App 只保存登录令牌（加密存储），不保存密码，" +
                    "也不会代你下单或支付。",
                style = MaterialTheme.typography.bodySmall,
                color = GlassTheme.colors.textTertiary,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

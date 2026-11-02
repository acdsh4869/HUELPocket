package com.huelpocket.app.ui.life

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.huelpocket.app.ui.components.QrScannerScreen
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.huelpocket.app.ui.card.CardDataTable
import com.huelpocket.app.ui.card.CardViewModel
import com.huelpocket.app.ui.components.GlassCard
import com.huelpocket.app.ui.nav.DOCK_CLEARANCE
import com.huelpocket.app.ui.study.EntryRow
import com.huelpocket.app.ui.study.SectionTitle
import com.huelpocket.app.ui.web.EmbeddedWebViewScreen
import kotlinx.coroutines.launch
import com.huelpocket.app.ui.web.WebSessionBridge
import com.huelpocket.glassui.GlassButton
import com.huelpocket.glassui.GlassButtonStyle
import com.huelpocket.glassui.GlassTheme

/**
 * 一卡通官方平台入口：必须命中登录路由（裸 /plat/ 对未登录会话会白屏/重定向异常）。
 * 取证（2026-10-30 PC 对比）：裸 /plat/login 与带 loginFrom=h5 服务端响应字节级相同
 * （各 1836B、HTTP 200）——参数不是转圈元凶，保留官方原生跳转同款参数。
 */
private const val CARD_OFFICIAL_URL = "https://yktwx2.huel.edu.cn/plat/login?loginFrom=h5"

private enum class LifeView { Menu, CardWeb, PangguaiScan, PangguaiDevice, PangguaiLogin }

/**
 * 官方页会话桥（WebSessionBridge）：
 * - 正向：App 有 token 时，登录页落地 → 写 sessionStorage（官方 Vuex 初始化
 *   `token = sessionStorage.getItem("access_token")` 从这恢复会话，app.js @57220
 *   静态证据 + CardDebug 实测 dump 双确认）→ 跳 homeUrl，SPA 重载进已登录态；
 * - 反向：官方页内登录后按兜底链读回 token → adoptWebViewToken 采纳给数据表。
 * token 变化才重注入（injectedToken 比对），上限 2 次防死循环。
 */
private fun cardSessionBridge(vm: CardViewModel, targetUrl: String? = null) = WebSessionBridge(
    tokenProvider = { vm.currentToken() },
    writeTokenJs = { token ->
        // 键名实测：ss:access_token / ss:token_type（Login mutation 同款写入）
        "try{sessionStorage.setItem('access_token','$token');" +
            "sessionStorage.setItem('token_type','bearer');}catch(e){}"
    },
    readTokenJs = """
        (function(){
            var t = sessionStorage.getItem('access_token') ||
                    localStorage.getItem('access_token') || '';
            if (!t) {
                try {
                    var v = JSON.parse(localStorage.getItem('vuex') || '{}');
                    t = (v && v.token) || '';
                } catch (e) {}
            }
            return t || '';
        })()
    """.trimIndent(),
    homeUrl = "https://yktwx2.huel.edu.cn/plat/",
    injectedTargetUrl = targetUrl,
)

/**
 * 生活 tab：一卡通数据表 + 官方入口卡 + 胖乖生活（校园洗浴/洗衣缴费）。
 *
 * 一卡通形态 = 「数据表 + 官方入口」（2026-10-29 简化）：
 * - App 无登录表单：登录在官方页完成，token 回收后数据表自动同步并返回；
 * - 数据表唯一直连接口 = 余额（queryCard，只读），金额只读展示；
 *   （电费侦察链 getAllApps 已删——侦察地址是 JSON API 非页面无法直达，
 *    且为刷新耗时主成分；电费查询统一走官方页内点选）
 * - 充值/缴费/挂失一律走「官方网页版」入口卡，App 内不做任何写接口；
 * - 下拉刷新整页生效，30 秒频控在 CardViewModel 内。
 * - 官方页用 [EmbeddedWebViewScreen]（池化实例 + Cookie 持久化 + 进度条 +
 *   白屏探测兜底），页面内登录一次后免登。
 *
 * 胖乖侦察结论（2026-10-29）：独立 App（com.qiekj.user）+ 微信/支付宝小程序，无 H5
 * → 按分支实现：已装则拉起；未装提示「微信搜索小程序：胖乖生活」并支持一键复制。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeScreen() {
    val context = LocalContext.current
    var view by remember { mutableStateOf(LifeView.Menu) }
    // 「已同步 + 自动返回」只执行一次：token 回收是高频回调（路由每次切换都读），
    // 防止页面切换/dispose 过程中连环触发采纳链（重组风暴/闪退根因）
    var sessionHandled by remember { mutableStateOf(false) }
    // 最近扫码的设备码（仅本地记录，不含任何身份信息）
    var lastDeviceCode by remember { mutableStateOf(PangguaiStore.readLastCode(context)) }
    // 刚扫到的码（进设备页时优先用它查询；null = 用本地最近一次）
    var scannedRaw by remember { mutableStateOf<String?>(null) }
    val cardVm: CardViewModel = viewModel()
    val cardState by cardVm.state.collectAsStateWithLifecycle()
    // 胖乖账号（登录后看「我的洗衣机」；只读，不代下单）
    val mineVm: PangguaiMineViewModel = viewModel()

    // 系统返回键：页面内返回上一级（子页），不再直接退出 App
    androidx.activity.compose.BackHandler(enabled = view != LifeView.Menu) {
        view = LifeView.Menu
    }
    var lastDeviceAt by remember { mutableStateOf(PangguaiStore.readLastAt(context)) }
    val mineState by mineVm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { mineVm.init(context) }
    // 已登录时按 pollSeconds 轮询（30s 起，失败自动降频到 60/120s）
    androidx.compose.runtime.LaunchedEffect(mineState.status, mineState.pollSeconds) {
        if (mineState.status == WashLoginStatus.LoggedIn) {
            while (true) {
                kotlinx.coroutines.delay(mineState.pollSeconds * 1_000L)
                mineVm.refresh(context)
            }
        }
    }

    when (view) {
        LifeView.CardWeb -> {
            EmbeddedWebViewScreen(
                title = "一卡通 · 官方平台",
                subtitle = "登录一次后自动同步余额；充值/缴费/电费查询均在官方页内完成",
                url = CARD_OFFICIAL_URL,
                poolKey = "card_official",
                onBack = { view = LifeView.Menu },
                // 失败卡第二按钮：回到一卡通数据表（重试之外的去处）
                failureExtraActionLabel = "回到一卡通",
                onFailureExtraAction = { view = LifeView.Menu },
                // 只有「采纳了新且未过期的 token」才提示并返回数据表；
                // token 相同/过期（池化 WebView 首次路由就会读到旧 token）不动作，
                // 用户继续留在官方页——杜绝「一进官方页就闪回」
                onSessionToken = { token ->
                    if (token != null && !sessionHandled) {
                        val adopted = cardVm.adoptWebViewToken(token)
                        if (adopted) {
                            sessionHandled = true
                            context.safeToast("已同步登录")
                            view = LifeView.Menu
                        }
                    }
                },
                sessionBridge = cardSessionBridge(cardVm),
            )
            return
        }
        LifeView.PangguaiLogin -> {
            PangguaiLoginScreen(
                onBack = { view = LifeView.Menu },
                onLoggedIn = { view = LifeView.Menu },
                vm = mineVm,
            )
            return
        }
        LifeView.PangguaiDevice -> {
            PangguaiDeviceScreen(
                onBack = { view = LifeView.Menu },
                onScanAgain = { view = LifeView.PangguaiScan },
                onPaid = {
                    // 不切页：收银台盖在上面，用户返回时仍停在设备页；
                    // 立刻切走会让 PayAct 处于“后台未 finish”，是残留实例的来源之一
                    mineVm.refresh(context)
                },
                scannedRaw = scannedRaw,
            )
            return
        }
        LifeView.PangguaiScan -> {
            QrScannerScreen(
                title = "扫设备二维码",
                hint = "对准洗衣机上的胖乖二维码（只识别设备，不代下单）",
                onBack = { view = LifeView.Menu },
                // 只接受官方域名链接或合法设备号；其他二维码留在扫码页提示
                acceptFilter = { raw -> isOfficialScan(raw) },
                onRejected = {
                    context.safeToast("这不是胖乖设备码")
                },
                onScanned = { raw ->
                    // 本地记录（只存码，不含身份）→ 直接进设备页查剩余时长
                    PangguaiStore.saveLastCode(context, raw)
                    lastDeviceCode = raw
                    lastDeviceAt = System.currentTimeMillis()
                    scannedRaw = raw
                    view = LifeView.PangguaiDevice
                },
            )
            return
        }
        LifeView.Menu -> Unit
    }

    PullToRefreshBox(
        isRefreshing = cardState.loading,
        onRefresh = { cardVm.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                SectionTitle("一卡通", "余额只读展示，充值缴费走官方入口")

                // 数据表卡（余额/电费/卡状态）+ 官方入口卡都在这里
                CardDataTable(
                    vm = cardVm,
                    onOpenWeb = { view = LifeView.CardWeb },

                )

                SectionTitle("胖乖生活", "登录后可用：扫码使用洗衣机 · 我的洗衣机")

                // 登录状态卡（已登录 / 未登录 / Token 过期）
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (mineState.status) {
                                    WashLoginStatus.LoggedIn -> "已登录"
                                    WashLoginStatus.Expired -> "Token 已过期"
                                    WashLoginStatus.NotLoggedIn -> "未登录"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                when (mineState.status) {
                                    WashLoginStatus.LoggedIn ->
                                        "胖乖账号 " + mineState.phone.let { ph ->
                                            if (ph.length >= 7) ph.replaceRange(3, 7, "****") else ph
                                        }
                                    WashLoginStatus.Expired -> "请重新登录后查看剩余时间"
                                    WashLoginStatus.NotLoggedIn -> "登录后可看剩余时间（只读，不代下单）"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = GlassTheme.colors.textTertiary,
                            )
                        }
                        when (mineState.status) {
                            WashLoginStatus.LoggedIn -> {
                                GlassButton(onClick = { mineVm.logout(context) }) { Text("退出登录") }
                            }
                            else -> {
                                GlassButton(
                                    onClick = { view = LifeView.PangguaiLogin },
                                    style = GlassButtonStyle.Prominent,
                                ) { Text("登录胖乖") }
                            }
                        }
                    }
                }

                // 模块 2：我的洗衣机（仅登录后可见）
                if (mineState.status != WashLoginStatus.NotLoggedIn) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "我的洗衣机",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = GlassTheme.colors.textTertiary,
                                    modifier = Modifier.weight(1f),
                                )
                                if (mineState.stale) {
                                    Text(
                                        "数据可能延迟",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GlassTheme.colors.textTertiary,
                                    )
                                }
                            }

                            // 付款成功后的一次性关门提示（每单一次）
                            mineState.doorNotice?.let { notice ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        notice,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GlassTheme.colors.accent,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { mineVm.dismissDoorNotice() }) {
                                        Text("知道了")
                                    }
                                }
                            }

                            when {
                                mineState.loading && mineState.machines.isEmpty() && mineState.localWashes.isEmpty() -> {
                                    Box(
                                        Modifier.fillMaxWidth().padding(20.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator() }
                                }
                                mineState.machines.isEmpty() && mineState.localWashes.isEmpty() -> {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            "暂无使用洗衣机",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            "扫码选好模式、付款回来后，这里会按该模式的时长自动倒计时；" +
                                                "也可以点下方「扫码使用洗衣机」。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = GlassTheme.colors.textSecondary,
                                        )
                                    }
                                }
                                else -> {
                                    // 本机计时优先：模式时长是我们自己定的，比官方字段靠谱
                                    mineState.localWashes.forEach { w ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    w.machineName + " · " + w.mode,
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                                Text(
                                                    if (w.abnormal) "设备状态异常 · 疑似订单未关闭"
                                                    else "本地计时 " + w.minutes + " 分钟",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (w.abnormal) GlassTheme.colors.danger
                                                    else GlassTheme.colors.textTertiary,
                                                )
                                            }
                                            if (w.abnormal) {
                                                Text(
                                                    "待官方处理",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = GlassTheme.colors.danger,
                                                )
                                            } else {
                                                TickingRemain(w.endAt()) { remain ->
                                                    Text(
                                                        if (remain > 0) "还剩 ${formatRemainShort(remain)} 洗完"
                                                        else "已结束",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = if (remain > 0) GlassTheme.colors.accent
                                                        else GlassTheme.colors.textTertiary,
                                                    )
                                                }
                                            }
                                            TextButton(onClick = { mineVm.removeLocalTimer(context, w.id) }) {
                                                Text("结束")
                                            }
                                        }
                                    }
                                    // 官方订单里还有、且不是同一台机器的，继续显示
                                    mineState.machines
                                        .filterNot { m -> mineState.localWashes.any { it.machineName == m.machineName } }
                                        .forEach { m ->
                                        // remainSeconds < 0 = 官方没给结束时间，别硬编倒计时
                                        val unknown = m.remainSeconds < 0
                                        // 结束时刻 = 拉取时刻 + 官方剩余秒数；每秒由叶子组件自走
                                        val endAt = mineState.fetchedAt + m.remainSeconds * 1000L
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    m.machineName,
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                                if (unknown) {
                                                    Text(
                                                        "官方未给结束时间",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = GlassTheme.colors.textTertiary,
                                                    )
                                                } else {
                                                    TickingRemain(endAt) { remain ->
                                                        Text(
                                                            if (remain > 0) "正在使用" else "即将结束",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = GlassTheme.colors.textTertiary,
                                                        )
                                                    }
                                                }
                                            }
                                            if (unknown) {
                                                Text(
                                                    "使用中",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = GlassTheme.colors.textTertiary,
                                                )
                                            } else {
                                                TickingRemain(endAt) { remain ->
                                                    Text(
                                                        if (remain > 0) "还剩 ${formatRemainShort(remain)} 洗完"
                                                        else "已结束",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = if (remain > 0) GlassTheme.colors.accent
                                                        else GlassTheme.colors.textTertiary,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (mineState.status == WashLoginStatus.Expired) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                ) {
                                    GlassButton(onClick = { view = LifeView.PangguaiLogin }) {
                                        Text("重新登录")
                                    }
                                }
                            }
                        }
                    }
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        // 最近扫码设备行（纯本地记录；剩余时长需胖乖登录态，App 不越权查询）
                        if (lastDeviceCode != null) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                    .clickable {
                                        scannedRaw = null
                                        view = LifeView.PangguaiDevice
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "最近扫码设备（点开看剩余时长）",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = GlassTheme.colors.textTertiary,
                                    )
                                    Text(
                                        lastDeviceCode.orEmpty().take(48),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GlassTheme.colors.textSecondary,
                                    )
                                }
                                Text(
                                    lastDeviceAt?.let {
                                        DateTimeFormatter.ofPattern("HH:mm")
                                            .withZone(ZoneId.systemDefault())
                                            .format(Instant.ofEpochMilli(it))
                                    } ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GlassTheme.colors.textTertiary,
                                )
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(GlassTheme.colors.textTertiary.copy(alpha = 0.25f)),
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 模块 1：扫码使用洗衣机（只识别设备 + 选时长后跳转支付）
                            GlassButton(
                                onClick = { view = LifeView.PangguaiScan },
                                style = GlassButtonStyle.Prominent,
                            ) { Text("扫码使用洗衣机") }
                        }
                    }
                }

                Text(
                    "设备订单与余额请以官方平台为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTheme.colors.textTertiary,
                )

                Spacer(Modifier.height(DOCK_CLEARANCE))
            }
        }
    }
}

/** 安全 toast：Activity 销毁中/已销毁不弹（杜绝 BadTokenException），失败静默 */
internal fun Context.safeToast(text: String, duration: Int = android.widget.Toast.LENGTH_SHORT) {
    runCatching {
        val activity = this as? android.app.Activity
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) return
        android.widget.Toast.makeText(this, text, duration).show()
    }
}

/**
 * 最近扫码设备的本地记录：设备码 + 解析出的 goodsId/名称（不含任何身份信息）。
 *
 * goodsId 是查「剩余时长」的钥匙——`goods/normal/details` 匿名可用，返回 remainTime 秒数，
 * 所以「我的洗衣机」拿它做实时剩余时间，比拿订单字段估算准。
 */
internal object PangguaiStore {
    private const val PREFS = "pangguai_last"
    private const val KEY_CODE = "device_code"
    private const val KEY_AT = "scan_at"
    private const val KEY_GOODS_ID = "goods_id"
    private const val KEY_NAME = "device_name"

    /** 机器名 → goodsId 映射：计时校准要按机器查设备详情，订单里没有 goodsId */
    fun saveMachineGoods(context: Context, machineName: String, goodsId: Long) {
        if (machineName.isBlank() || goodsId <= 0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("g_" + machineName, goodsId).apply()
    }

    fun readMachineGoods(context: Context): Map<String, Long> {
        val all = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        return all.mapNotNull { (k, v) ->
            if (k.startsWith("g_") && v is Long) k.removePrefix("g_") to v else null
        }.toMap()
    }

    fun readLastCode(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CODE, null)

    fun readLastAt(context: Context): Long? {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AT, 0L)
        return at.takeIf { it > 0 }
    }

    /** 上次扫码设备的 goodsId（0 = 未知） */
    fun readGoodsId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_GOODS_ID, 0L)

    fun readName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, "").orEmpty()

    fun saveLastCode(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CODE, code.take(200))
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    /** 扫码解析成功后补写设备标识，供「我的洗衣机」查实时剩余时长 */
    fun saveDevice(context: Context, goodsId: Long, name: String) {
        if (goodsId <= 0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_GOODS_ID, goodsId)
            .putString(KEY_NAME, name.take(60))
            .apply()
    }
}


/** 扫码内容是否合法：官方域名 + 设备参数，或 6~64 位裸设备号 */
internal fun isOfficialScan(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty()) return false
    val lower = t.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        val host = runCatching { java.net.URI(t).host.orEmpty().lowercase() }.getOrDefault("")
        val officialHost = host == "qiekj.com" || host.endsWith(".qiekj.com")
        val hasDeviceParam = t.contains("NQT=", true) ||
            t.contains("SN=", true) ||
            t.contains("IMEI=", true)
        return officialHost && hasDeviceParam
    }
    return t.matches(Regex("^[A-Za-z0-9:_-]{6,64}$"))
}

/**
 * 剩余时间的**叶子组件**：每秒的 tick 只活在这里。
 *
 * 之前 `nowTick` 挂在 LifeScreen 上，每秒一次会让整屏（卡片/按钮/扫码行/列表）全部重组；
 * 现在每秒只有本组件重组，内部只画一个 Text（机器行还有一个副标题）。
 * 到点自动停表，不再空转。
 *
 * @param endAtMillis 结束时刻（epoch ms）
 * @param content 拿到当前剩余秒数后画什么（只应包含 Text）
 */
@Composable
private fun TickingRemain(
    endAtMillis: Long,
    content: @Composable (remainSeconds: Int) -> Unit,
) {
    var now by remember(endAtMillis) { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(endAtMillis) {
        while (true) {
            val left = endAtMillis - System.currentTimeMillis()
            if (left <= 0L) {
                now = System.currentTimeMillis()
                break
            }
            kotlinx.coroutines.delay(minOf(1_000L, left))
            now = System.currentTimeMillis()
        }
    }
    content(((endAtMillis - now) / 1000L).toInt().coerceAtLeast(0))
}

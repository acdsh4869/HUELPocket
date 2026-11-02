package com.huelpocket.app.ui.life

import android.content.Context
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
 * 扫码后的「选洗衣时长」窗口（WASH-02 模块 1）。
 *
 * 链路：二维码 → `goods/scan/v2`（定位设备）→ `goods/normal/details`（状态/剩余）
 *       → 登录后 `goods/normal/skus`（可选套餐：模式 / 时长 / 价格）。
 *
 * 边界：只读取套餐并让你选择；**不下单、不支付**。点「跳转支付」把设备链接 + 所选
 * 套餐写进剪贴板并拉起胖乖生活，付款与启动都在胖乖内完成。
 */
data class PangguaiDeviceUiState(
    val loading: Boolean = false,
    val scannedCode: String? = null,
    val device: PangguaiClient.ScanDevice? = null,
    val remainSeconds: Int = 0,
    val offline: Boolean = false,
    val deviceErrorMsg: String? = null,
    /** 可选套餐（需登录） */
    val skus: List<PangguaiClient.Sku> = emptyList(),
    val loggedIn: Boolean = false,
    val message: String? = null,
    val updatedAt: Long? = null,
    /** 设备所属门店（下单必填） */
    val orgId: Long = 0L,
    /** 正在下单 */
    val paying: Boolean = false,
    /** 页面内的临时提示（机器被占用等），不是查询失败 */
    val notice: String? = null,
)

class PangguaiDeviceViewModel : ViewModel() {

    private val client = PangguaiClient()
    private var auth: PangguaiAuth? = null
    private val _state = MutableStateFlow(PangguaiDeviceUiState())
    val state: StateFlow<PangguaiDeviceUiState> = _state.asStateFlow()

    private fun auth(context: Context): PangguaiAuth =
        auth ?: PangguaiAuth(context.applicationContext).also { auth = it }

    fun load(context: Context, rawOverride: String? = null) {
        val raw = rawOverride?.takeIf { it.isNotBlank() }
            ?: _state.value.scannedCode?.takeIf { it.isNotBlank() }
            ?: PangguaiStore.readLastCode(context)
        if (raw.isNullOrBlank()) return
        _state.value = _state.value.copy(scannedCode = raw, loading = true, message = null)
        query(context, raw)
    }

    fun scan(context: Context, raw: String) {
        PangguaiStore.saveLastCode(context, raw)
        _state.value = _state.value.copy(
            scannedCode = raw, loading = true, message = null,
            device = null, remainSeconds = 0, offline = false, deviceErrorMsg = null, skus = emptyList(),
        )
        query(context, raw)
    }

    fun refresh(context: Context) {
        val raw = _state.value.scannedCode ?: return
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true)
        query(context, raw)
    }

    private fun query(context: Context, raw: String) {
        viewModelScope.launch {
            val token = auth(context).readToken()
            val loggedIn = token != null
            when (val result = client.scanDevice(raw)) {
                is PangguaiClient.ScanResult.Failed -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        loggedIn = loggedIn,
                        message = result.message,
                        device = _state.value.device,
                    )
                }
                is PangguaiClient.ScanResult.Ok -> {
                    val detail = client.machineDetail(result.device.goodsId)
                    val device = if (result.device.name.isBlank() && !detail?.name.isNullOrBlank()) {
                        result.device.copy(name = detail.name)
                    } else {
                        result.device
                    }
                    val skus = if (token != null) client.deviceSkus(device.goodsId, token) else emptyList()
                    // 记住 goodsId：查实时剩余时长要用它（匿名接口，不涉及身份）
                    if (device.goodsId > 0) {
                        PangguaiStore.saveDevice(context, device.goodsId, device.name)
                        // 机器名 → goodsId 映射：计时校准要按机器查详情，订单里没有 goodsId
                        PangguaiStore.saveMachineGoods(context, device.name, device.goodsId)
                    }
                    _state.value = _state.value.copy(
                        loading = false,
                        device = device,
                        remainSeconds = detail?.remainSeconds ?: 0,
                        offline = (detail?.deviceErrorCode ?: 0) != 0,
                        deviceErrorMsg = detail?.deviceErrorMsg?.takeIf { it.isNotBlank() },
                        skus = skus,
                        loggedIn = loggedIn,
                        message = null,
                        updatedAt = System.currentTimeMillis(),
                        orgId = detail?.orgId ?: 0L,
                    )
                }
            }
        }
    }
    /**
     * 付款：预览价 → 官方下单 → 拉起胖乖自己的收银台（PayAct，带 orderNo/金额）。
     *
     * 这步只负责“创建订单”，钱在官方收银台里付（微信/支付宝/银联都在里面），
     * 所以不需要微信 appid 绑定，也不碰任何支付凭据。
     */
    /** 这台机器是否正被别人使用（官方 deviceErrorMsg/remainTime 判断） */
    fun busy(state: PangguaiDeviceUiState): Boolean =
        state.remainSeconds > 0 || state.deviceErrorMsg.orEmpty().contains("工作中")

    fun pay(context: Context, raw: String?, sku: PangguaiClient.Sku?, onOpened: (Boolean) -> Unit) {
        val device = _state.value.device
        if (busy(_state.value)) {
            _state.value = _state.value.copy(notice = "这台机器正在被别人使用，换一台空闲的")
            onOpened(false)
            return
        }
        if (device == null || sku == null || sku.skuId <= 0L) {
            _state.value = _state.value.copy(message = "套餐信息不全，重新扫码试试")
            onOpened(false)
            return
        }
        val token = auth(context).readToken()
        if (token == null) {
            _state.value = _state.value.copy(message = "请先登录胖乖账号")
            onOpened(false)
            return
        }
        if (_state.value.paying) return
        _state.value = _state.value.copy(paying = true, message = null)
        viewModelScope.launch {
            // 下单前用最新设备状态复核：避免页面停留期间别人抢先用了；顺带拿权威 orgId
            val fresh = client.machineDetail(device.goodsId)
            if (fresh != null &&
                (fresh.remainSeconds > 0 || fresh.deviceErrorMsg.orEmpty().contains("工作中"))
            ) {
                _state.value = _state.value.copy(
                    paying = false,
                    remainSeconds = fresh.remainSeconds,
                    deviceErrorMsg = fresh.deviceErrorMsg,
                    notice = "这台机器刚被占用，换一台空闲的",
                )
                onOpened(false)
                return@launch
            }
            // orgId 以最新详情为准（页面上的可能来自缓存的另一次扫码）
            val orgId = fresh?.orgId?.takeIf { it > 0 } ?: _state.value.orgId
            // 金额必须来自官方预览：收银台会核对金额，对不上就报「未知设备」
            val price = client.previewPrice(device.goodsId, sku.skuId, orgId, token)
            if (price == null) {
                _state.value = _state.value.copy(paying = false, message = "价格获取失败，稍后重试")
                onOpened(false)
                return@launch
            }
            when (val r = client.createTrade(device.goodsId, sku.skuId, orgId, token)) {
                is PangguaiClient.TradeResult.Failed -> {
                    _state.value = _state.value.copy(paying = false, message = r.message)
                    onOpened(false)
                }
                is PangguaiClient.TradeResult.Ok -> {
                    // 官方 PayAct 需要 8 个 extras 才认这笔单（少了 shopId/goodsId/categoryCode
                    // 支付请求会被判无效，服务端回 faultDesc → 弹「未知设备」）。
                    // 值全部取自刚才的实时设备详情；dTime 刻意不传（传 0 会让 PayAct 立刻 finish）。
                    val opened = openOfficialCashier(
                        context = context,
                        orderNo = r.orderNo,
                        shopId = fresh?.shopId.orEmpty(),
                        goodsId = device.goodsId.toString(),
                        categoryCode = fresh?.categoryCode.orEmpty(),
                        payMoney = price,
                        raw = raw,
                    )
                    _state.value = _state.value.copy(paying = false)
                    onOpened(opened)
                }
            }
        }
    }
}

@Composable
fun PangguaiDeviceScreen(
    onBack: () -> Unit,
    onScanAgain: () -> Unit,
    onPaid: () -> Unit,
    scannedRaw: String? = null,
    vm: PangguaiDeviceViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var selected by remember { mutableIntStateOf(0) }
    // 正在改时长的模式（null = 没开弹窗）
    var editingMode by remember { mutableStateOf<String?>(null) }
    // 付款前的「关门确认」弹窗：每次付款都弹，不做记忆
    var showDoorConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(scannedRaw) { vm.load(context, scannedRaw) }

    // 10 秒刷新设备状态（只读，匿名接口）；界面上的秒数在本地每秒递减
    // 体检 #5：轮询必须 RESUMED 感知 —— 原实现裸 CoroutineScope + while(true) 不感知生命周期，
    // HOME/锁屏后照样打胖乖接口（耗电 + 风控风险）。改 repeatOnLifecycle：STOP 即停，RESUMED 重启，
    // 离开组合时 LaunchedEffect 取消整个轮询。
    if (state.device != null) {
        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        LaunchedEffect(state.device?.goodsId, lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(10_000L)
                    vm.refresh(context)
                }
            }
        }
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
                    Text("选择洗衣时长", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "选好时长点「付款」，会弹出胖乖官方收银台",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.colors.textSecondary,
                    )
                }
                IconButton(onClick = { vm.refresh(context) }, enabled = !state.loading) {
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新",
                            tint = GlassTheme.colors.textSecondary,
                        )
                    }
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

            when {
                state.message != null -> {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("暂时查不到这台设备", style = MaterialTheme.typography.titleMedium)
                            Text(
                                state.message.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTheme.colors.textSecondary,
                            )
                            GlassButton(onClick = onScanAgain, style = GlassButtonStyle.Prominent) {
                                Text("重新扫码")
                            }
                        }
                    }
                }

                state.device == null -> {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("扫码选择洗衣时长", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "对准洗衣机上的二维码，选好时长后到胖乖里付款启动。",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTheme.colors.textSecondary,
                            )
                            GlassButton(onClick = onScanAgain, style = GlassButtonStyle.Prominent) {
                                Text("扫码")
                            }
                        }
                    }
                }

                else -> {
                    val device = state.device!!
                    // 设备 + 状态
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    device.name.ifBlank { "设备 ${device.goodsId}" },
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    "设备号 " + device.goodsId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GlassTheme.colors.textTertiary,
                                )
                            }
                            Text(
                                statusText(state),
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    state.remainSeconds > 0 -> GlassTheme.colors.accent
                                    state.offline -> GlassTheme.colors.textTertiary
                                    else -> GlassTheme.colors.textPrimary
                                },
                            )
                        }
                    }

                    // 套餐选择
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                "洗衣时长",
                                style = MaterialTheme.typography.labelMedium,
                                color = GlassTheme.colors.textTertiary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            when {
                                !state.loggedIn -> {
                                    Text(
                                        "登录胖乖后这里会显示可选时长和价格",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GlassTheme.colors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                state.skus.isEmpty() -> {
                                    Text(
                                        "官方暂未返回可选时长，请直接在胖乖内扫码选择",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GlassTheme.colors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                else -> {
                                    state.skus.forEachIndexed { index, sku ->
                                        val mins = WashPlanTimes.minutesOf(context, sku.name, sku.minutes)
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .selectable(
                                                    selected = selected == index,
                                                    onClick = { selected = index },
                                                )
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = selected == index,
                                                onClick = { selected = index },
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text(sku.name, style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    "${mins} 分钟",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = GlassTheme.colors.textSecondary,
                                                )
                                            }
                                            Text(
                                                if (sku.priceYuan > 0) "¥%.2f".format(sku.priceYuan) else "—",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = GlassTheme.colors.accent,
                                            )
                                            // 时长就是本地倒计时的依据，点这里改（独立按钮，不触发选中）
                                            TextButton(onClick = { editingMode = sku.name }) {
                                                Text("改", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    state.notice?.let { n ->
                        Text(
                            n,
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTheme.colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    // 操作：付款 + 重新扫码
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val chosen = state.skus.getOrNull(selected)
                        val machineBusy = vm.busy(state)
                        GlassButton(
                            onClick = {
                                if (!state.loggedIn) {
                                    context.safeToast("请先登录胖乖账号再付款")
                                } else {
                                    showDoorConfirm = true
                                }
                            },
                            enabled = !machineBusy && !state.paying,
                            style = GlassButtonStyle.Prominent,
                            modifier = Modifier.weight(2f),
                        ) {
                            Text(
                                if (machineBusy) "机器使用中"
                                else if (state.paying) "下单中…"
                                else if (chosen != null && chosen.priceYuan > 0) {
                                    "付款 ¥%.2f".format(chosen.priceYuan)
                                } else {
                                    "付款"
                                },
                            )
                        }
                        GlassButton(
                            onClick = onScanAgain,
                            modifier = Modifier.weight(1f),
                        ) { Text("重扫") }
                    }

                    Text(
                        "点「付款」会用官方接口下单，并弹出胖乖官方收银台（微信/支付宝/银联都在里面）；" +
                            "收银台只有 25 秒有效期，超时需重新付款。付完回来会按所选模式的时长自动倒计时。",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.colors.textTertiary,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // 付款前检查：机器付款即启动，忘了关门会白跑时间
    if (showDoorConfirm) {
        AlertDialog(
            onDismissRequest = { showDoorConfirm = false },
            title = { Text("付款前检查") },
            text = {
                Text("衣物已放入？舱门已关好？\n付款后机器将立即启动计时。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDoorConfirm = false
                    val chosen = state.skus.getOrNull(selected)
                    vm.pay(context, state.scannedCode, chosen) { opened ->
                        if (opened) onPaid()
                    }
                }) { Text("确认，去付款") }
            },
            dismissButton = {
                TextButton(onClick = { showDoorConfirm = false }) { Text("返回检查") }
            },
        )
    }

    // 改某个模式的时长（存本地，下次数倒计时就用它）
    editingMode?.let { mode ->
        var text by remember(mode) {
            mutableStateOf(WashPlanTimes.minutesOf(context, mode).toString())
        }
        AlertDialog(
            onDismissRequest = { editingMode = null },
            title = { Text("「$mode」洗多久") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(3) },
                    singleLine = true,
                    label = { Text("分钟（1~180）") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = text.toIntOrNull()
                    if (m != null && m in 1..180) {
                        WashPlanTimes.save(context, mode, m)
                        editingMode = null
                    } else {
                        context.safeToast("填 1~180 之间的分钟数")
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingMode = null }) { Text("取消") }
            },
        )
    }
}

/** 胖乖生活独立 App 包名（应用市场公开） */
private const val PANGGUAI_PACKAGE = "com.qiekj.user"

/** 复制设备码（付款页/客服核对都用得上） */
private fun copyDeviceCode(context: Context, url: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("胖乖设备", url))
    }
}

/**
 * 拉起胖乖官方收银台（PayAct，已导出），把订单号和金额带进去。
 * 实测：它会直接弹出「请在 xx 秒内支付 / 银联·支付宝·微信 / 合计 ¥x」的收银台。
 * 失败降级：复制设备码 + 打开胖乖首页（用户自己扫码付款）。
 */
private fun openOfficialCashier(
    context: Context,
    orderNo: String,
    shopId: String,
    goodsId: String,
    categoryCode: String,
    payMoney: Double,
    raw: String?,
): Boolean {
    val money = "%.2f".format(java.util.Locale.CHINA, payMoney)
    // 官方 PayAct.Companion.start() 的 8 个 extras：shopId/goodsId/orderId/orderNo
    // 都是 String，attach 是 Boolean，dTime 是 Long —— dTime 不传（实测传 0 会立刻 finish）
    val intent = android.content.Intent().apply {
        setClassName(PANGGUAI_PACKAGE, "com.qiekj.user.ui.activity.pay.PayAct")
        putExtra("shopId", shopId)
        putExtra("goodsId", goodsId)
        putExtra("orderId", orderNo)
        putExtra("orderNo", orderNo)
        putExtra("attach", false)
        putExtra("payMoney", money)
        putExtra("categoryCode", categoryCode)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    dumpCashierIntent(context, intent, orderNo)
    if (runCatching { context.startActivity(intent) }.isSuccess) return true
    deviceLinkOf(raw)?.let { copyDeviceCode(context, it) }
    return openPangguaiApp(context)
}

/** 诊断：把真正发出的 Intent 全量落盘（不含 token），排查用 */
private fun dumpCashierIntent(context: Context, intent: android.content.Intent, orderNo: String) {
    runCatching {
        val extras = intent.extras?.keySet().orEmpty().sorted().joinToString("\n") { key ->
            val v = intent.extras?.get(key)
            "  " + key + " = " + v + "  (" + (v?.javaClass?.simpleName ?: "null") + ")"
        }
        val text = buildString {
            appendLine("time=" + System.currentTimeMillis())
            appendLine("component=" + intent.component?.flattenToShortString())
            appendLine("flags=0x" + Integer.toHexString(intent.flags))
            appendLine("orderNo=" + orderNo)
            appendLine("extras:")
            appendLine(extras)
        }
        context.getExternalFilesDir(null)?.let { dir ->
            java.io.File(dir, "cashier_intent.txt").writeText(text)
        }
    }
}

/** 拉起胖乖生活 App；返回是否成功（没装就只靠复印件） */
private fun openPangguaiApp(context: Context): Boolean {
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        setPackage(PANGGUAI_PACKAGE)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val info = runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull()
        ?.activityInfo ?: return false
    return runCatching {
        context.startActivity(intent.setClassName(info.packageName, info.name))
        true
    }.getOrDefault(false)
}

/**
 * 状态文案。deviceErrorCode 非 0 不等于「离线」——实测两种语义：
 * - 4204「设备离线，请检查设备是否通电…」→ 离线；
 * - 「设备工作中，请更换设备使用」→ 使用中。
 */
private fun statusText(state: PangguaiDeviceUiState): String {
    if (state.remainSeconds > 0) {
        return "使用中 · 还剩 " + formatRemainShort(state.remainSeconds)
    }
    val msg = state.deviceErrorMsg.orEmpty()
    return when {
        msg.contains("离线") -> "离线"
        msg.contains("工作") || msg.contains("使用中") || msg.contains("运行") -> "使用中"
        state.offline -> "异常"
        else -> "空闲可用"
    }
}

/**
 * 扫码内容 → 官方设备链接。
 *
 * 统一**重建**成 `h5.qiekj.com/skip?<参数>`，不直接复用二维码里的 URL：
 * 二维码原链接可能带别的落地参数（活动页/下载页），交给 H5 就会随机跳到「下载 App」页。
 * 官方 H5 只认 NQT / SN / IMEI 三个键（与 goods/scan/v2 同规则），按原键名重建最稳。
 */
internal fun deviceLinkOf(raw: String?): String? {
    val code = raw?.trim().orEmpty()
    if (code.isEmpty()) return null
    // 裸码：官方按 IMEI 处理
    if (!code.contains("http", ignoreCase = true)) {
        return "https://h5.qiekj.com/skip?IMEI=" + java.net.URLEncoder.encode(code, "UTF-8")
    }
    val query = code.substringAfter('?', "")
    if (query.isEmpty()) return null
    val kept = query.split("&").mapNotNull { pair ->
        val k = pair.substringBefore('=', "").trim()
        val v = pair.substringAfter('=', "").trim()
        if (k in setOf("NQT", "SN", "IMEI") && v.isNotEmpty()) {
            k + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        } else {
            null
        }
    }
    if (kept.isEmpty()) return null
    return "https://h5.qiekj.com/skip?" + kept.joinToString("&")
}

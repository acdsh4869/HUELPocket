package com.huelpocket.app.ui.card

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.huelpocket.app.core.AppGraph
import com.huelpocket.app.data.ecard.SynjonesClient
import com.huelpocket.app.ui.components.GlassCard
import com.huelpocket.glassui.GlassButton
import com.huelpocket.glassui.GlassButtonStyle
import com.huelpocket.glassui.GlassTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

data class CardUiState(
    val loading: Boolean = false,
    val balanceYuan: Double? = null,
    val cardName: String = "",
    val account: String = "",
    val expiredDate: String? = null,
    val lost: Boolean = false,
    /** 最近一次**成功查询**的时刻（「更新于 HH:mm」的来源；缓存态=缓存时间） */
    val updatedAt: Long? = null,
    /** true = 当前展示的是上次缓存，接口本次未成功（标注「数据可能延迟」） */
    val fromCache: Boolean = false,
    /** true = 本次查询失败（有缓存时仍展示缓存数据） */
    val lastQueryFailed: Boolean = false,
    val message: String? = null,
    /** true = 无 token / token 失效（去官方页登录一次的引导态） */
    val needsRelogin: Boolean = false,
)

/**
 * 一卡通（新中中新平台）数据表的业务层。
 *
 * **方案（2026-10-29 简化）**：App 不实现登录协议、不保存密码——
 * 只回收官方 WebView 页面登录后的会话 token（adoptWebViewToken）。
 * 唯一直连业务接口 = 余额（queryCard，只读）+ 只读侦察大厅应用列表定位电费入口。
 * token 失效 → 清 token → 数据表出引导卡「去官方页登录一次」；无任何静默重登。
 * 查询成功落盘缓存，失败时展示缓存并标注延迟。
 */
class CardViewModel : ViewModel() {

    private val vault = AppGraph.cardCredentialVault
    private val client = SynjonesClient()

    private val _state = MutableStateFlow(CardUiState())
    val state: StateFlow<CardUiState> = _state.asStateFlow()

    init {
        // 冷启动先展示缓存（有的话），再静默联网刷新
        viewModelScope.launch {
            AppGraph.cardBalanceStore.latest()?.let { cached ->
                if (_state.value.balanceYuan == null) {
                    val info = parseCachedInfo(cached.infoJson)
                    _state.value = _state.value.copy(
                        balanceYuan = cached.yuan,
                        cardName = info?.get("name") ?: "",
                        account = info?.get("account") ?: "",
                        expiredDate = info?.get("expdate")?.takeIf { it.isNotBlank() },
                        lost = info?.get("lost") == "1",
                        updatedAt = cached.at,
                        fromCache = true,
                    )
                }
            }
        }
        refresh()
    }

    private fun parseCachedInfo(json: String?): Map<String, String>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val obj = AppGraph.json.parseToJsonElement(json)
                as? kotlinx.serialization.json.JsonObject
            obj?.mapValues { (_, v) ->
                (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            }
        }.getOrNull()
    }

    /** 带 token 执行查询；401/失效 → 清 token（数据表转为引导态），不再有任何重登 */
    private suspend fun <T> withSession(
        block: suspend (String) -> SynjonesClient.CardResult2<T>,
    ): SynjonesClient.CardResult2<T> {
        val session = vault.readSession()
            ?: return SynjonesClient.CardResult2.Failed("未登录")
        val result = block(session.accessToken)
        if (result is SynjonesClient.CardResult2.Unauthorized) {
            vault.clearSession()
        }
        return result
    }

    /**
     * 采纳官方 WebView 页面里登录得到的 token（「官方页登录一次，数据表免登」）。
     * token 为 JWT（实测 dump：ss:access_token = eyJhbG…585ch）——直接解出 exp
     * 作为过期时间，不再按 2 小时保守估计（避免提前静默重登转圈）。
     */
    /**
     * 采纳官方页 token。返回值语义（LifeScreen 据此决定是否弹「已同步」+返回）：
     * - true = 采纳了**新的且未过期**的 token（已落库）——只有这时才提示并返回数据表；
     * - false = token 为空 / 与库中相同 / 已过期——什么也不做，用户继续留在官方页。
     *   （池化 WebView 的 sessionStorage 长期保留旧 token，每次进官方页第一秒就会
     *    触发一次回收；不区分新旧就会「一进页面就闪回数据表」——缺陷1实锤根因）
     */
    fun adoptWebViewToken(token: String): Boolean {
        if (token.isBlank()) return false
        val current = vault.readSession()
        if (current?.accessToken == token) return false
        val expires = jwtExpiresInSeconds(token) ?: 0L
        if (expires <= 0) return false // 已过期的 token 不采纳
        viewModelScope.launch {
            android.util.Log.i(
                "HUELPocket",
                "官方页 token 已回收（JWT exp 距今 ${expires / 3600}h${expires % 3600 / 60}m）",
            )
            // EncryptedSharedPreferences 写入重：落 IO 线程，不占主线程
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                vault.saveSession(token, expires)
            }
            _state.value = _state.value.copy(needsRelogin = false, message = null)
            refresh()
        }
        return true
    }

    /** JWT payload 里的 exp（秒级时间戳）→ 距今秒数；解析失败返回 null */
    private fun jwtExpiresInSeconds(token: String): Long? = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return null
        val json = String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        val exp = AppGraph.json.parseToJsonElement(json)
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("exp")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }
            ?: return null
        exp - System.currentTimeMillis() / 1000L
    }.getOrNull()

    /** 当前有效 token（供官方页 URL 免登参数用）；未登录返回 null */
    fun currentToken(): String? = vault.readSession()?.accessToken

    /** 手动刷新频控（30 秒最小间隔） */
    private var lastManualRefreshAt = 0L

    /** 拉余额；随后只读侦察电费应用入口（与余额同一次刷新动作内一起更新）。401 → 静默重登一次 → 重试 */
    fun refresh() {
        if (_state.value.loading) return
        val now = System.currentTimeMillis()
        if (now - lastManualRefreshAt < 30_000L && _state.value.balanceYuan != null && !_state.value.fromCache) {
            _state.value = _state.value.copy(message = "刚刷新过，稍后再试")
            return
        }
        lastManualRefreshAt = now
        _state.value = _state.value.copy(loading = true, message = null, lastQueryFailed = false)
        viewModelScope.launch {
            val result = withSession { token -> client.queryCard(token).toGeneric() }
            when (result) {
                is SynjonesClient.CardResult2.Success -> {
                    val info = result.value
                    val at = System.currentTimeMillis()
                    _state.value = _state.value.copy(
                        loading = false,
                        balanceYuan = info.balanceYuan,
                        cardName = info.name,
                        account = info.account,
                        expiredDate = info.expiredDate,
                        lost = info.lost,
                        updatedAt = at,
                        fromCache = false,
                        lastQueryFailed = false,
                        needsRelogin = false,
                    )
                    // 成功数据落盘（余额给冷启动/桌面组件用；补充信息 JSON 供缓存态完整还原）
                    runCatching {
                        val infoJson = AppGraph.json.encodeToString(
                            mapOf(
                                "name" to info.name,
                                "account" to info.account,
                                "expdate" to (info.expiredDate ?: ""),
                                "lost" to if (info.lost) "1" else "0",
                            ),
                        )
                        AppGraph.cardBalanceStore.save(info.balanceYuan, at, infoJson)
                    }
                }
                is SynjonesClient.CardResult2.Unauthorized -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        needsRelogin = true,
                        lastQueryFailed = true,
                        message = "登录已过期，且自动续期未成功；请重新登录。",
                    )
                }
                is SynjonesClient.CardResult2.Failed -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        lastQueryFailed = true,
                        message = result.message,
                    )
                }
            }
        }
    }

    private fun SynjonesClient.CardResult.toGeneric(): SynjonesClient.CardResult2<SynjonesClient.CardInfo> =
        when (this) {
            is SynjonesClient.CardResult.Success -> SynjonesClient.CardResult2.Success(info)
            SynjonesClient.CardResult.Unauthorized -> SynjonesClient.CardResult2.Unauthorized
            is SynjonesClient.CardResult.Failed -> SynjonesClient.CardResult2.Failed(message)
        }

    /** 清除会话（设置页/引导卡用）：只清 token，不动缓存余额 */
    fun clearSession() {
        vault.clearSession()
        _state.value = _state.value.copy(needsRelogin = true)
    }
}

/** 卡状态行的取值：已挂失 > 已过期 > 正常 */
internal fun cardStatusText(expiredDate: String?, lost: Boolean): String = when {
    lost -> "已挂失"
    expiredDate?.takeIf { it.isNotBlank() }?.let { isExpiredDate(it) } == true -> "已过期"
    else -> "正常"
}

/** expdate 形如 2027-09-01 / 20270901，宽松解析到「天」级别比较 */
private fun isExpiredDate(expdate: String): Boolean = runCatching {
    val digits = expdate.filter { it.isDigit() }
    val normalized = when (digits.length) {
        8 -> "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
        else -> expdate
    }
    !LocalDate.parse(normalized.take(10)).isAfter(LocalDate.now())
}.getOrDefault(false)

/**
 * 一卡通数据表（生活 tab 内嵌）。
 *
 * 三态齐全：加载中进度；未登录卡内登录表单；错误重试；有缓存标注「数据可能延迟」。
 * 金额只读展示 —— 充值/缴费/挂失一律走下方官方入口卡。
 * 下拉刷新由宿主（生活页）的 PullToRefreshBox 承担，这里只渲染内容。
 */
@Composable
fun CardDataTable(
    vm: CardViewModel = viewModel(),
    onOpenWeb: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.balanceYuan == null) {
            // 无 token / 会话失效：引导卡（App 内无任何登录表单——登录只在官方页完成）
            if (state.loading) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            } else {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Filled.CreditCard,
                            contentDescription = null,
                            tint = GlassTheme.colors.accent,
                            modifier = Modifier.size(28.dp),
                        )
                        Text("一卡通", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.needsRelogin) "会话已过期，去官方页登录一次即可同步。"
                            else "首次使用请去官方页登录一次，之后自动同步余额。",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTheme.colors.textSecondary,
                        )
                        GlassButton(
                            onClick = onOpenWeb,
                            style = GlassButtonStyle.Prominent,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("去官方页登录") }
                    }
                }
            }
        } else {
            // 已登录（或有缓存）：数据表格（唯一直连接口 = 一卡通余额）
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    // 表头：项目 | 更新于 HH:mm + 刷新图标
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "项目",
                            style = MaterialTheme.typography.labelMedium,
                            color = GlassTheme.colors.textTertiary,
                            modifier = Modifier.weight(1f),
                        )
                        val updated = state.updatedAt?.let {
                            "更新于 " + DateTimeFormatter.ofPattern("HH:mm")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(it))
                        } ?: ""
                        Text(
                            updated,
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassTheme.colors.textTertiary,
                        )
                        IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                            if (state.loading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "刷新",
                                    tint = GlassTheme.colors.textSecondary,
                                )
                            }
                        }
                    }
                    TableDivider()
                    TableRow("一卡通余额", "¥ %.2f".format(state.balanceYuan))
                    TableDivider()
                    TableRow("卡状态", cardStatusText(state.expiredDate, state.lost))
                    TableDivider()

                    // 表脚：缓存标注 / 失败重试 / 有效期与重新登录
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (state.fromCache) {
                            Text(
                                "数据可能延迟（展示的是上次查询结果）",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTheme.colors.textTertiary,
                            )
                        }
                        if (state.needsRelogin) {
                            // 有缓存但 token 失效：提示去官方页重新登录一次
                            Text(
                                "会话已过期，余额为缓存数据；去官方页登录一次即可恢复",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTheme.colors.textSecondary,
                            )
                            TextButton(onClick = onOpenWeb) { Text("去官方页登录") }
                        } else if (state.lastQueryFailed) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    state.message ?: "查询失败",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GlassTheme.colors.textSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { vm.refresh() }) { Text("重试") }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                state.expiredDate?.takeIf { it.isNotBlank() }?.let { "有效期至 $it" } ?: " ",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTheme.colors.textTertiary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.clearSession() }) { Text("清除登录") }
                        }
                    }
                }
            }
        }

        state.message?.takeIf { !state.lastQueryFailed || state.balanceYuan == null }?.let {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    it,
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassTheme.colors.textPrimary,
                )
            }
        }

        // 官方入口卡：数据表下方唯一入口（充值/缴费/挂失全走这里）
        GlassCard(Modifier.fillMaxWidth(), onClick = onOpenWeb) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("官方网页版", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "充值、缴费、挂失等完整功能",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.colors.textSecondary,
                    )
                }
            }
        }

        Text(
            "数据来自学校一卡通平台，金额以平台为准。",
            style = MaterialTheme.typography.bodySmall,
            color = GlassTheme.colors.textTertiary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** 数据表行；onClick 非空时整行可点并带箭头（跳转类条目） */
@Composable
private fun TableRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = GlassTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (onClick != null) {
                MaterialTheme.colorScheme.primary
            } else {
                GlassTheme.colors.textPrimary
            },
        )
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = GlassTheme.colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TableDivider() {
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(GlassTheme.colors.textTertiary.copy(alpha = 0.25f)),
    )
}

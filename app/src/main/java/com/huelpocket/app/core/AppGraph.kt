package com.huelpocket.app.core

import android.content.Context
import com.huelpocket.app.data.auth.AuthRepository
import com.huelpocket.app.data.auth.RefreshCredentialVault
import com.huelpocket.app.data.auth.ZhengFangClient
import com.huelpocket.app.data.auth.TokenStore
import com.huelpocket.app.data.db.AppDatabase
import com.huelpocket.app.data.eas.ZhengFangEasClient
// HUEL 版本：校园卡功能已隐藏
import com.huelpocket.app.data.prefs.CardBalanceStore
import com.huelpocket.app.data.prefs.FocusPrefsStore
import com.huelpocket.app.data.prefs.LoginPrefsStore
import com.huelpocket.app.data.prefs.PeriodSettingsStore
import com.huelpocket.app.data.prefs.UiPrefsStore
import com.huelpocket.app.data.room.RoomCatalog
import com.huelpocket.app.data.repo.FocusRepository
import com.huelpocket.app.data.repo.ScheduleRepository
import com.huelpocket.app.core.net.HttpClients
import com.huelpocket.app.focus.PomodoroEngine
import com.huelpocket.app.focus.PomodoroNotifications
import kotlinx.serialization.json.Json

/**
 * 极简手动依赖容器。
 *
 * P1 刻意不上 Hilt：当前依赖只有 6 个，而 P1 最大的风险是认证协议能否跑通、
 * 不是依赖注入。等 P2 依赖变多、结构稳定后再引入 Hilt，改动面也很小
 * （各 Repository/Client 已经是构造器注入的写法）。
 */
object AppGraph {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 统一认证的 deviceId：**每台设备唯一**。
     *
     * 学校按它判定"是不是可信设备"（决定要不要短信验证码），统一认证「安全中心」里显示的设备名也是它。
     *
     * ⚠️ 这里曾经是一个固定字符串 `"com.huelpocket.app"` —— 那等于**所有装本 App 的人共用同一台设备**：
     * 只要有人把这个"设备"设为可信设备，别人登录时学校就会认为设备已可信、可能免掉短信验证
     * （等于把一个人的信任借给了所有人）；学校将来做"每账号限设备数/异地登录校验"时也会互相干扰。
     *
     * 现在用 SSAID（`Settings.Secure.ANDROID_ID`）：同一签名 + 同一设备 + 同一用户下**稳定**，
     * 重装/升级都不会变 —— 所以用户不会因为重装而突然变成"新设备"、被再次索要短信验证码。
     */
    val deviceId: String by lazy {
        val ssaid = runCatching {
            android.provider.Settings.Secure.getString(
                appContext.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull().orEmpty()
        // SSAID 拿不到（极罕见）时退回随机值：仍然保证"每台设备唯一"，只是重装后会变
        if (ssaid.isBlank()) {
            "huelpocket-" + java.util.UUID.randomUUID().toString().take(8)
        } else {
            "huelpocket-$ssaid"
        }
    }

    /** 给需要在非 Composable 环境里拿 Context 的地方用（比如 ViewModel 清理背景图文件） */
    fun appContextOrNull(): Context? = if (::appContext.isInitialized) appContext else null

    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true   // 学校改版加字段不应该让 App 崩
            isLenient = true
            coerceInputValues = true
            // ⚠️ 必须为 true：kotlinx.serialization 默认不序列化"等于默认值"的字段，
            // 而学校接口要求的空字符串字段（bigArea/unit/subArea）和 utilityType="electric"
            // 都恰好是默认值 —— 漏发 utilityType 会让一卡通返回一个空的 templateList，
            // 表现为"电量响应里没有 quantity 字段"，非常难猜。
            encodeDefaults = true
        }
    }

    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    val uiPrefsStore: UiPrefsStore by lazy { UiPrefsStore(appContext) }

    /** 节次作息唯一活跃数据源：设置页 / 课表 / 今天 / 小组件共用 */
    val periodSettingsStore: PeriodSettingsStore by lazy { PeriodSettingsStore(appContext) }

    /** 登录流程的跨页面状态（目前只有"上一次发验证码的时刻"，用于冷却倒计时接着算） */
    val loginPrefsStore: LoginPrefsStore by lazy { LoginPrefsStore(appContext) }

    /**
     * 校园卡余额的本地缓存。
     *
     * 桌面组件不能联网，而余额只能联网查 —— 所以 App 每次查到就存一份，组件只读这份缓存。
     * 详见 `CardBalanceStore` 的注释。
     */
    val cardBalanceStore: CardBalanceStore by lazy { CardBalanceStore(appContext) }

    /** 宿舍房间表（assets/rooms.json）：电费页的"选楼栋 → 选房间"用它 */
    val roomCatalog: RoomCatalog by lazy { RoomCatalog(appContext) }

    val zhengFangClient: ZhengFangClient by lazy {
        ZhengFangClient(HttpClients.zhengfang, json)
    }

    val zhengFangEasClient: ZhengFangEasClient by lazy {
        ZhengFangEasClient(HttpClients.zhengfang, json, tokenStore)
    }

    val db: AppDatabase by lazy { AppDatabase.get(appContext) }

    val authRepository: AuthRepository by lazy {
        AuthRepository(tokenStore, zhengFangClient)
    }

    /** 302 会话失效时的静默续登凭证；只保存在本机加密文件中 */
    val refreshCredentialVault: RefreshCredentialVault by lazy { RefreshCredentialVault(appContext) }

    val cardCredentialVault: com.huelpocket.app.data.ecard.CardCredentialVault by lazy {
        com.huelpocket.app.data.ecard.CardCredentialVault(appContext)
    }

    val scheduleRepository: ScheduleRepository by lazy {
        ScheduleRepository(db, zhengFangEasClient, authRepository, periodSettingsStore)
    }

    val focusRepository: FocusRepository by lazy { FocusRepository(db) }

    val focusPrefsStore: FocusPrefsStore by lazy { FocusPrefsStore(appContext, json) }

    val pomodoroNotifications: PomodoroNotifications by lazy { PomodoroNotifications(appContext) }

    val pomodoroEngine: PomodoroEngine by lazy {
        PomodoroEngine(
            context = appContext,
            prefs = focusPrefsStore,
            repo = focusRepository,
            notifier = pomodoroNotifications,
        )
    }

    // HUEL 版本：自检功能已隐藏
}

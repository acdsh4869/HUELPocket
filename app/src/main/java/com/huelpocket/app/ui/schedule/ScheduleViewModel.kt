package com.huelpocket.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huelpocket.app.core.AppError
import com.huelpocket.app.core.AppGraph
import com.huelpocket.app.core.HuelSemester
import com.huelpocket.app.core.toUserMessage
import com.huelpocket.app.data.auth.AuthRepository
import com.huelpocket.app.data.repo.ScheduleRepository
import com.huelpocket.app.data.repo.TodayTimetable
import com.huelpocket.app.domain.Course
import com.huelpocket.app.domain.Period
import com.huelpocket.app.domain.Plan
import com.huelpocket.app.domain.PlanSpec
import com.huelpocket.app.domain.Semester
import com.huelpocket.app.domain.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ScheduleUiState(
    val started: Boolean = false,
    val loading: Boolean = false,
    val loggedIn: Boolean = false,

    // 教学周参数
    val weekStart: LocalDate? = null,
    val weekTotal: Int? = null,

    // 缓存状态
    val dataFetchedAt: Long? = null,
    val refreshing: Boolean = false,
    val cacheNotice: String? = null,
    val cacheNoticeNeedsLogin: Boolean = false,

    // 学期与课程
    val semesters: List<Semester> = emptyList(),
    val currentSemester: Semester? = null,
    val courses: List<Course> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val periods: List<Period> = emptyList(),

    // 当前周次（浏览中的周；不一定等于今天所在周）
    val currentWeek: Int = 1,

    // 今天真实所在教学周（用于「本周」标注与「回到本周」）
    val realWeek: Int? = null,

    // 学期总周数（周切换器的右边界）
    val totalWeeks: Int = 20,

    // 当前教学周状态（开学前 / 学期内 / 假期）
    val weekStatus: HuelSemester.Status? = null,

    // 错误
    val error: String? = null,
)

class ScheduleViewModel : ViewModel() {

    private val auth: AuthRepository = AppGraph.authRepository
    private val repo: ScheduleRepository = AppGraph.scheduleRepository
    private val uiPrefs = AppGraph.uiPrefsStore
    private val periodSettings = AppGraph.periodSettingsStore

    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    /** 用户正在左右切周浏览；浏览期间禁止用「今天所在周」覆盖 currentWeek */
    private var browsingWeek = false

    init {
        _state.update { it.copy(loggedIn = auth.isLoggedIn()) }
    }

    /**
     * 启动加载：先显示缓存，再后台刷新
     */
    fun start() {
        if (_state.value.started) return
        _state.update { it.copy(started = true, loading = true) }

        viewModelScope.launch {
            try {
                // 先加载本地缓存；作息表由 DataStore 提供，离线时同样生效
                loadCachedData()

                // 如果已登录，后台刷新
                if (auth.isLoggedIn()) {
                    refreshInBackground()
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    private suspend fun loadCachedData() {
        val semesters = repo.semesters()
        val current = repo.currentSemester()
        val periods = periodSettings.periods()
        val weekStatus = com.huelpocket.app.data.repo.TodayTimetable
            .weekStatus(repo, uiPrefs, LocalDate.now())

        _state.update {
            it.copy(
                semesters = semesters,
                currentSemester = current,
                periods = periods,
                weekStatus = weekStatus,
                realWeek = weekStatus.displayWeek,
                totalWeeks = weekStatus.totalWeeks,
                // 用户正在切周浏览时不要覆盖浏览位置；否则对准今天所在周
                currentWeek = if (browsingWeek) {
                    it.currentWeek
                } else {
                    weekStatus.displayWeek ?: 1
                },
                loading = false,
            )
        }

        current?.let { semester ->
            val courses = repo.courses(semester.id)
            val sessions = repo.sessionsOfSemester(semester.id)
            val fetchedAt = repo.lastFetchedAt(ScheduleRepository.SOURCE_EAS, semester.id)

            _state.update {
                it.copy(
                    courses = courses,
                    sessions = sessions,
                    dataFetchedAt = fetchedAt,
                )
            }
        }
    }

    private suspend fun refreshInBackground(retried: Boolean = false) {
        _state.update { it.copy(refreshing = true) }
        try {
            repo.refreshSemesters()

            val current = repo.currentSemester()
            current?.let { semester ->
                repo.refreshCourseTable(semester.id)
                loadCachedData()
            }

            _state.update { it.copy(refreshing = false, cacheNotice = null) }
        } catch (e: Exception) {
            if (!retried && isSessionExpired(e)) {
                if (silentRelogin()) {
                    android.util.Log.i(TAG, "教务会话失效，已静默重登；重试课表刷新")
                    refreshInBackground(retried = true)
                    return
                }

                android.util.Log.w(TAG, "教务会话失效，静默重登失败；显示缓存课表", e)
                loadCachedData()
                _state.update {
                    it.copy(
                        refreshing = false,
                        error = null,
                        cacheNotice = "登录已过期，正在显示缓存课表；请重新登录后刷新",
                        cacheNoticeNeedsLogin = true,
                    )
                }
                return
            }

            android.util.Log.w(TAG, "课表刷新失败；显示缓存课表", e)
            _state.update {
                it.copy(
                    refreshing = false,
                    error = null,
                    cacheNotice = "刷新失败：${e.toUserMessage()}",
                )
            }
        }
    }

    private fun isSessionExpired(e: Exception): Boolean {
        if (e is AppError.ReLoginRequired) return true
        if (e !is AppError.Server) return false
        if (e.code == 302) return true
        val message = e.message.orEmpty()
        return message.contains("302") ||
            message.contains("无功能权限") ||
            message.contains("会话已过期")
    }

    /** 用本机加密保存的凭证静默重登一次；没有凭证或需要验证码时不做静默尝试 */
    private suspend fun silentRelogin(): Boolean {
        val credentials = AppGraph.refreshCredentialVault.read() ?: return false
        val (account, password) = credentials
        return runCatching {
            when (auth.beginLogin(account, password)) {
                is AuthRepository.LoginStep.Success -> true
                else -> false
            }
        }.getOrElse { false }
    }

    /** P2-2：手动刷新频控（30 秒最小间隔，防连续下拉打接口） */
    private var lastManualRefreshAt = 0L

    /**
     * 手动刷新
     */
    fun refresh() {
        if (!auth.isLoggedIn()) {
            _state.update { it.copy(error = "请先登录") }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastManualRefreshAt < 30_000L) {
            _state.update { it.copy(cacheNotice = "刚刷新过，稍后再试") }
            return
        }
        lastManualRefreshAt = now
        viewModelScope.launch {
            refreshInBackground()
        }
    }

    /**
     * 切换学期。
     *
     * 体检 #2：不能只换 courses/sessions —— weekStatus/realWeek/currentWeek 也必须
     * 按新学期重算（原实现周次还是数据库当前学期的，翻旧学期时日期全错）。
     */
    fun selectSemester(semester: Semester) {
        viewModelScope.launch {
            _state.update { it.copy(currentSemester = semester, loading = true) }
            val courses = repo.courses(semester.id)
            val sessions = repo.sessionsOfSemester(semester.id)
            val fetchedAt = repo.lastFetchedAt(ScheduleRepository.SOURCE_EAS, semester.id)
            // 与 loadCachedData 同口径，只是目标学期换成用户选中的
            val weekStatus = TodayTimetable.weekStatus(repo, uiPrefs, LocalDate.now(), semester)
            browsingWeek = false // 旧学期的浏览位置没有意义，对准新学期今天所在周
            _state.update {
                it.copy(
                    courses = courses,
                    sessions = sessions,
                    dataFetchedAt = fetchedAt,
                    weekStatus = weekStatus,
                    realWeek = weekStatus.displayWeek,
                    totalWeeks = weekStatus.totalWeeks,
                    currentWeek = weekStatus.displayWeek ?: 1,
                    loading = false,
                )
            }
        }
    }

    /** 按 ID 切换学期（课程总览页用） */
    fun selectSemesterById(semesterId: Int) {
        viewModelScope.launch {
            val semester = repo.semesters().firstOrNull { it.id == semesterId } ?: return@launch
            selectSemester(semester)
        }
    }

    /**
     * 从后台 / 登录页 / 设置页回来时调用：
     * 1. 刷新登录态；若刚完成重新登录（或此前有过期横幅），清除过期错误态并自动重拉课表；
     * 2. 重新按今天对准教学周（退出浏览模式）；
     * 3. 重读节次 Flow，设置页修改作息后返回课表立即生效。
     */
    fun realignToCurrentWeek() {
        viewModelScope.launch {
            val wasLoggedIn = _state.value.loggedIn
            val loggedIn = auth.isLoggedIn()
            val hadExpiredNotice = _state.value.cacheNoticeNeedsLogin
            browsingWeek = false
            _state.update { it.copy(loggedIn = loggedIn) }
            loadCachedData()

            if (loggedIn && (hadExpiredNotice || !wasLoggedIn)) {
                // 登录成功返回：过期横幅必须消失，并立即重新拉取课表
                _state.update {
                    it.copy(
                        cacheNotice = null,
                        cacheNoticeNeedsLogin = false,
                        error = null,
                    )
                }
                refreshInBackground()
            }
        }
    }

    /**
     * 切换周次（浏览）。范围钳制在 1..学期总周数；
     * 浏览期间 loadCachedData 不会用「今天所在周」覆盖此值。
     */
    fun selectWeek(week: Int) {
        val total = _state.value.totalWeeks.coerceAtLeast(1)
        browsingWeek = true
        _state.update { it.copy(currentWeek = week.coerceIn(1, total)) }
    }

    /** 回到今天所在的教学周 */
    fun backToCurrentWeek() {
        browsingWeek = false
        val real = _state.value.realWeek ?: 1
        _state.update { it.copy(currentWeek = real.coerceIn(1, _state.value.totalWeeks)) }
    }

    // ── 自定义日程 ──────────────────────────────────────────────────────
    suspend fun customPlans(semesterId: Int): List<Plan> = repo.customPlans(semesterId)

    suspend fun savePlan(spec: PlanSpec): String = repo.savePlan(spec)

    suspend fun deletePlan(semesterId: Int, code: String) = repo.deletePlan(semesterId, code)

    suspend fun planConflicts(semesterId: Int, spec: PlanSpec): List<Session> =
        repo.planConflicts(semesterId, spec)

    private companion object {
        const val TAG = "HUELPocket"
    }
}

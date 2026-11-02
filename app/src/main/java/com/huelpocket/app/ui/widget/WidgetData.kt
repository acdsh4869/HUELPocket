package com.huelpocket.app.ui.widget

import com.huelpocket.app.core.AppGraph
import com.huelpocket.app.data.prefs.UiPrefs
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

/**
 * 小组件一次刷新要用到的全部课表数据。
 *
 * HUEL 版本：只保留课表功能，删除电费、校园卡等郑大专属功能。
 */
data class WidgetScheduleBundle(
    /** 库里到底有没有课表数据（区分"今天没课"和"还没同步过课表"） */
    val hasSchedule: Boolean,
    /** 今天属于第几教学周；假期/学期外为 null */
    val week: Int?,
    /** 周次状态文案：第 X 周 / 距开学还有 X 天 / 假期中 */
    val weekLabel: String,
    /** 今天 / 明天（用日期拼「今天 9.29 周二」这种列头） */
    val todayDate: LocalDate,
    val tomorrowDate: LocalDate,
    /** 今天的课 */
    val today: List<WidgetCourse>,
    /** 明天的课 */
    val tomorrow: List<WidgetCourse>,
    /** 现在几点（当天第几分钟） */
    val nowMin: Int,
    /** 下一个上下课时间点（毫秒时间戳），用来定"该变的时候变一次"的闹钟 */
    val nextBoundaryMillis: Long?,
) {
    /** 4×2 课表：左列今天、右列明天 */
    fun twoDayPlan(maxRows: Int): WidgetTwoDayPlan = WidgetSchedule.twoDayPlan(
        hasSchedule = hasSchedule,
        week = week,
        weekLabelText = weekLabel,
        todayDate = todayDate,
        tomorrowDate = tomorrowDate,
        today = today,
        tomorrow = tomorrow,
        nowMin = nowMin,
        maxRows = maxRows,
    )

    /** 2×2：一节课大字（今天没了就顶明天的第一节） */
    fun nextClassPlan(previewRows: Int): WidgetNextClassPlan = WidgetSchedule.nextClassPlan(
        hasSchedule = hasSchedule,
        week = week,
        weekLabelText = weekLabel,
        today = today,
        tomorrow = tomorrow,
        nowMin = nowMin,
        previewRows = previewRows,
    )
}

/**
 * 小组件的取数。
 *
 * 两条铁律：
 *  1. **只读本地数据**（课表在 Room）。
 *  2. 判"第几周、今天有哪些课"全部走 [com.huelpocket.app.data.repo.ScheduleRepository]。
 */
object WidgetData {

    /** 读一次界面偏好（主题、深浅色、课程配色） */
    suspend fun prefs(): UiPrefs = runCatching { AppGraph.uiPrefsStore.flow.first() }
        .getOrDefault(UiPrefs())

    suspend fun loadSchedule(): WidgetScheduleBundle {
        val repo = AppGraph.scheduleRepository
        val prefsStore = AppGraph.uiPrefsStore
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val nowMillis = System.currentTimeMillis()

        val hasSchedule = com.huelpocket.app.data.repo.TodayTimetable
            .hasAnySchedule(repo, prefsStore)
        val periods = AppGraph.periodSettingsStore.periods()
        val weekStatus = com.huelpocket.app.data.repo.TodayTimetable
            .weekStatus(repo, prefsStore, today)
        val week = weekStatus.displayWeek

        val todayCourses = com.huelpocket.app.data.repo.TodayTimetable
            .sessionsOn(repo, prefsStore, today)
            .mapNotNull { WidgetSchedule.toCourse(it, periods) }
            .sortedBy { it.startMin }

        val tomorrowCourses = com.huelpocket.app.data.repo.TodayTimetable
            .sessionsOn(repo, prefsStore, tomorrow)
            .mapNotNull { WidgetSchedule.toCourse(it, periods) }
            .sortedBy { it.startMin }

        return WidgetScheduleBundle(
            hasSchedule = hasSchedule,
            week = week,
            weekLabel = weekStatus.label,
            todayDate = today,
            tomorrowDate = tomorrow,
            today = todayCourses,
            tomorrow = tomorrowCourses,
            nowMin = nowMin,
            nextBoundaryMillis = WidgetSchedule.nextBoundaryMillis(todayCourses, nowMin, nowMillis),
        )
    }
}

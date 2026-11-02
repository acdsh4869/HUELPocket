package com.huelpocket.app.data.repo

import com.huelpocket.app.core.HuelSemester
import com.huelpocket.app.core.WeekSettings
import com.huelpocket.app.core.WeekSource
import com.huelpocket.app.data.prefs.UiPrefsStore
import com.huelpocket.app.domain.Semester
import com.huelpocket.app.domain.Session
import java.time.LocalDate

/**
 * 「某一天有哪些课」的**唯一口径**。
 *
 * HUEL 版本：只有本科课表（正方教务系统）。
 * 周次基准按学期保存在 [UiPrefsStore]；没有用户设置时使用 [HuelSemester] 默认值。
 */
object TodayTimetable {

    /**
     * 某天的全部课程，按节次排序。
     */
    suspend fun sessionsOn(
        repo: ScheduleRepository,
        prefs: UiPrefsStore,
        date: LocalDate,
    ): List<Session> {
        val out = ArrayList<Session>()

        runCatching {
            val semester = repo.currentSemester() ?: return@runCatching
            val week = weekStatus(repo, prefs, date).displayWeek
            out += repo.sessionsOn(semester.id, date, week)
        }

        return out.distinctBy { it.id }.sortedBy { it.startUnit }
    }

    /**
     * 当前教学周状态。
     *
     * 这是课表页、今天页和小组件共用的口径；用户设置开学日期后三处同步生效。
     * 没有用户设置时自动填入 HUEL 默认开学日期，但保留「待确认」标记。
     *
     * [semesterOverride]（体检 #2）：传入时按该学期算周次，不查数据库当前学期——
     * 课表页切换学期后要用选中学期自己的开学日期，否则周次/日期还是上一学期的。
     */
    suspend fun weekStatus(
        repo: ScheduleRepository,
        prefs: UiPrefsStore,
        date: LocalDate,
        semesterOverride: Semester? = null,
    ): HuelSemester.Status {
        val semester = semesterOverride
            ?: runCatching { repo.currentSemester() }.getOrNull()
            ?: return HuelSemester.status(
                weekOneStart = null,
                totalWeeks = null,
                today = date,
                needsConfirmation = true,
            )

        val settings = ensureWeekSettings(prefs, semester.id, semester.startDate)
        val status = HuelSemester.status(
            weekOneStart = settings.weekOneStart,
            totalWeeks = settings.totalWeeks,
            today = date,
            needsConfirmation = !settings.confirmed,
        )
        // 体检 #8：默认开学日期是写死的（本学期口径），跨学期后会过期。
        // 默认值（未经确认）算出「假期中」时继续挂「待确认」，设置页会提示用户填新学期日期，
        // 而不是让 displayWeek=null 静默空转。
        if (settings.source == WeekSource.INFERRED && status.phase == HuelSemester.Phase.VACATION) {
            return status.copy(needsConfirmation = true)
        }
        return status
    }

    /**
     * 读取某学期周次设置；为空时填入默认值。
     *
     * 优先级（体检 #8）：用户已存设置 > 教务校历 startDate（[calendarStart]，source=CALENDAR，
     * 视为已确认）> [HuelSemester] 写死的默认值（source=INFERRED，待确认）。
     * 原实现 calendarStart 参数空转、永用默认值。
     */
    suspend fun ensureWeekSettings(
        prefs: UiPrefsStore,
        semesterId: Int,
        calendarStart: LocalDate? = null,
    ): WeekSettings {
        val saved = runCatching { prefs.weekSettings(semesterId) }
            .getOrDefault(WeekSettings())
        if (saved.weekOneStart != null) return saved

        // 教务校历给的开学日期可信：直接采纳并标记已确认
        if (calendarStart != null) {
            val fromCalendar = saved.copy(
                weekOneStart = calendarStart,
                totalWeeks = saved.totalWeeks ?: HuelSemester.DEFAULT_TOTAL_WEEKS,
                confirmed = true,
                source = WeekSource.CALENDAR,
            )
            runCatching { prefs.saveWeekSettings(semesterId, fromCalendar) }
            return fromCalendar
        }

        val filled = saved.copy(
            weekOneStart = HuelSemester.DEFAULT_WEEK_ONE_START,
            totalWeeks = saved.totalWeeks ?: HuelSemester.DEFAULT_TOTAL_WEEKS,
            confirmed = false,
            source = WeekSource.INFERRED,
        )
        runCatching { prefs.saveWeekSettings(semesterId, filled) }
        return filled
    }

    /**
     * [date] 属于第几教学周；开学前返回 1（课表先按第 1 周浏览），
     * 超过学期周数 / 没有学期数据返回 null。
     */
    suspend fun currentWeekAt(
        repo: ScheduleRepository,
        prefs: UiPrefsStore,
        date: LocalDate,
    ): Int? = weekStatus(repo, prefs, date).displayWeek

    /**
     * 有没有"任何"课表数据。
     *
     * 组件用它区分「还没同步过」和「今天没课」——这两种情况显示不同文案，不能混。
     */
    suspend fun hasAnySchedule(repo: ScheduleRepository, prefs: UiPrefsStore): Boolean {
        return runCatching { repo.currentSemester()?.let { repo.hasCourseData(it.id) } }
            .getOrNull() == true
    }
}

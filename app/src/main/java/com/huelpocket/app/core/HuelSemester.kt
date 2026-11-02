package com.huelpocket.app.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * HUEL 学期周次的默认口径。
 *
 * 开学日期是用户确认的默认值；如果某学期还没手动设置，
 * 就先用这个日期计算，并在课表页提示用户确认。
 */
object HuelSemester {

    /** 本学期第一周周一（用户确认的默认值） */
    val DEFAULT_WEEK_ONE_START: LocalDate = LocalDate.of(2026, 9, 7)

    /** 默认学期长度 */
    const val DEFAULT_TOTAL_WEEKS = 20

    enum class Phase { BEFORE_START, IN_TERM, VACATION, UNKNOWN }

    data class Status(
        val phase: Phase,
        /** 学期内显示第几周；开学前显示第 1 周，假期为 null */
        val displayWeek: Int?,
        /** 原始周次：开学前可能 ≤ 0 */
        val rawWeek: Int?,
        val daysUntilStart: Long?,
        val weekOneStart: LocalDate?,
        val totalWeeks: Int,
        /** 开学日期来自默认值且用户还没确认时为 true */
        val needsConfirmation: Boolean,
    ) {
        val label: String
            get() = when (phase) {
                Phase.BEFORE_START -> "距开学还有 ${daysUntilStart ?: 0} 天"
                Phase.IN_TERM -> "第 ${displayWeek ?: 1} 周"
                Phase.VACATION -> "假期中"
                Phase.UNKNOWN -> "未设置开学日期"
            }
    }

    fun status(
        weekOneStart: LocalDate?,
        totalWeeks: Int?,
        today: LocalDate,
        needsConfirmation: Boolean,
    ): Status {
        if (weekOneStart == null) {
            return Status(
                phase = Phase.UNKNOWN,
                displayWeek = 1,
                rawWeek = null,
                daysUntilStart = null,
                weekOneStart = null,
                totalWeeks = totalWeeks ?: DEFAULT_TOTAL_WEEKS,
                needsConfirmation = true,
            )
        }

        val raw = WeekMath.weekIndexAt(weekOneStart, today)
        val total = totalWeeks ?: DEFAULT_TOTAL_WEEKS
        return when {
            raw <= 0 -> Status(
                phase = Phase.BEFORE_START,
                displayWeek = 1,
                rawWeek = raw,
                daysUntilStart = ChronoUnit.DAYS.between(today, weekOneStart).coerceAtLeast(0),
                weekOneStart = weekOneStart,
                totalWeeks = total,
                needsConfirmation = needsConfirmation,
            )
            raw > total -> Status(
                phase = Phase.VACATION,
                displayWeek = null,
                rawWeek = raw,
                daysUntilStart = null,
                weekOneStart = weekOneStart,
                totalWeeks = total,
                needsConfirmation = needsConfirmation,
            )
            else -> Status(
                phase = Phase.IN_TERM,
                displayWeek = raw,
                rawWeek = raw,
                daysUntilStart = null,
                weekOneStart = weekOneStart,
                totalWeeks = total,
                needsConfirmation = needsConfirmation,
            )
        }
    }
}

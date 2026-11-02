package com.huelpocket.app.ui.schedule

import com.huelpocket.app.core.WeekMath
import com.huelpocket.app.core.WeekStartDay
import com.huelpocket.app.domain.Period
import com.huelpocket.app.domain.Session
import com.huelpocket.app.domain.Semester
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 课程表 → ICS 导出。
 *
 * 约束（0.15.12）：
 * - 日期优先用 `session.date`（调休真实日期）；为空时用「周次 + 星期」换算，
 *   换算只复用课表网格在用的 [WeekMath]。
 * - 时间优先用 `session.startTime/endTime`；为空时用节次表按起止节换算。
 * - 不写 VALARM：提醒交给系统日历 App 的默认设置。
 */
object CourseIcsExporter {

    data class ExportResult(
        val file: File,
        val skipped: Int,
    )

    /** build() 的结果：正文 + 跳过条数（体检 #7：原模块级 skippedCount 可变字段是隐藏耦合） */
    data class Built(
        val text: String,
        val skipped: Int,
    )

    private val ICS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val ICS_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val CN_ZONE = ZoneId.of("Asia/Shanghai")

    /** 生成并写入 cacheDir/exports，返回文件与跳过条数 */
    fun write(
        context: android.content.Context,
        semester: Semester,
        sessions: List<Session>,
        periods: List<Period>,
        weekOneStart: LocalDate?,
    ): ExportResult {
        val built = build(semester, sessions, periods, weekOneStart)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = "课程表_${safeFileName(semesterName(semester))}_" +
            LocalDate.now().format(ICS_DATE) + ".ics"
        val file = File(dir, fileName)
        if (file.exists()) file.delete()
        file.writeText(built.text, Charsets.UTF_8)
        return ExportResult(file = file, skipped = built.skipped)
    }

    fun build(
        semester: Semester,
        sessions: List<Session>,
        periods: List<Period>,
        weekOneStart: LocalDate?,
    ): Built {
        var skipped = 0
        val periodByIndex = periods.associateBy { it.indexNo }
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//HUELPocket//Course Export//CN",
            "CALSCALE:GREGORIAN",
            "X-WR-CALNAME:" + escape(semesterName(semester) + " 课程表"),
            "BEGIN:VTIMEZONE",
            "TZID:Asia/Shanghai",
            "BEGIN:STANDARD",
            "DTSTART:19700101T000000",
            "TZOFFSETFROM:+0800",
            "TZOFFSETTO:+0800",
            "TZNAME:CST",
            "END:STANDARD",
            "END:VTIMEZONE",
        )

        sessions.sortedWith(
            compareBy({ it.date ?: "" }, { it.weekIndex }, { it.weekday }, { it.startUnit }),
        ).forEach { session ->
            val date = dateOf(session, weekOneStart)
            val time = timeOf(session, periodByIndex)
            if (date == null || time == null) {
                skipped += 1
                return@forEach
            }

            val start = LocalDateTime.of(date, time.first)
            val end = LocalDateTime.of(date, time.second)
            val dtStamp = LocalDateTime.now(CN_ZONE).format(ICS_DATE_TIME)
            lines += "BEGIN:VEVENT"
            lines += "UID:huelpocket-" + session.id + "@//huelpocket.app/"
            lines += "DTSTAMP:" + dtStamp
            lines += "DTSTART;TZID=Asia/Shanghai:" + start.format(ICS_DATE_TIME)
            lines += "DTEND;TZID=Asia/Shanghai:" + end.format(ICS_DATE_TIME)
            lines += "SUMMARY:" + escape(summaryOf(session))
            locationOf(session)?.let { lines += "LOCATION:" + escape(it) }
            lines += "DESCRIPTION:" + escape(descriptionOf(session))
            lines += "END:VEVENT"
        }

        lines += "END:VCALENDAR"
        // RFC 5545 §3.1：单行不得超过 75 字节（UTF-8 计），超长折行（续行以单个空格开头）。
        // 中文课程名/教室名/教师名一行就超，不折行会被微信/QQ 预览或部分日历截断（体检 #7）。
        val text = lines.flatMap { foldLine(it) }.joinToString(separator = "\r\n") + "\r\n"
        return Built(text = text, skipped = skipped)
    }

    /** RFC 5545 折行：按 UTF-8 字节数切，续行加一个空格前缀；按码点切不劈中文/emoji */
    private const val MAX_LINE_OCTETS = 75

    private fun foldLine(line: String): List<String> {
        if (line.toByteArray(Charsets.UTF_8).size <= MAX_LINE_OCTETS) return listOf(line)
        val out = mutableListOf<String>()
        var sb = StringBuilder()
        var octets = 0
        var first = true
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val byteLen = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            val limit = if (first) MAX_LINE_OCTETS else MAX_LINE_OCTETS - 1 // 续行要留 1 字节给前导空格
            if (octets + byteLen > limit && sb.isNotEmpty()) {
                out += if (first) sb.toString() else " " + sb.toString()
                sb = StringBuilder()
                octets = 0
                first = false
            }
            sb.appendCodePoint(cp)
            octets += byteLen
            i += Character.charCount(cp)
        }
        out += if (first) sb.toString() else " " + sb.toString()
        return out
    }

    private fun summaryOf(session: Session): String =
        if (session.experiment) "[实验] " + session.courseName else session.courseName

    /** 与课表页标题一致：优先用教务学期名，避免 displayName 里学年重复 */
    private fun semesterName(semester: Semester): String =
        semester.nameZh.takeIf { it.isNotBlank() } ?: semester.displayName

    private fun locationOf(session: Session): String? =
        session.roomText?.takeIf { it.isNotBlank() }
            ?: session.campusText?.takeIf { it.isNotBlank() }

    private fun descriptionOf(session: Session): String {
        val teacher = session.teacherName?.takeIf { it.isNotBlank() } ?: "—"
        val type = when {
            session.experiment -> "实验课"
            session.source.equals("custom", ignoreCase = true) -> "自定义日程"
            else -> "教务课表"
        }
        return listOf(
            teacher,
            "第${session.weekIndex}教学周",
            type,
        ).joinToString(separator = "；")
    }

    /** 调休日期优先；普通周课只用 WeekMath 的现有换算 */
    private fun dateOf(session: Session, weekOneStart: LocalDate?): LocalDate? {
        session.date?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()?.let { return it }
        }
        val start = weekOneStart ?: return null
        return WeekMath.dateOf(
            weekOneStart = start,
            week = session.weekIndex,
            weekday = session.weekday,
            // MONDAY 是 WeekStartDay 唯一取值（体检 #11 删除了 SUNDAY 死分支）
            startDay = WeekStartDay.MONDAY,
        )
    }

    /** 直给时间优先；都缺时才走节次表 */
    private fun timeOf(
        session: Session,
        periodByIndex: Map<Int, Period>,
    ): Pair<LocalTime, LocalTime>? {
        val directStart = session.startTime?.takeIf { it.isNotBlank() }?.let(::parseHhmm)
        val directEnd = session.endTime?.takeIf { it.isNotBlank() }?.let(::parseHhmm)
        if (directStart != null && directEnd != null) return directStart to directEnd

        if (directStart != null || directEnd != null) return null
        val startPeriod = periodByIndex[session.startUnit] ?: return null
        val endPeriod = periodByIndex[session.endUnit] ?: return null
        val start = LocalTime.of(startPeriod.startTime / 60, startPeriod.startTime % 60)
        val end = LocalTime.of(endPeriod.endTime / 60, endPeriod.endTime % 60)
        return start to end
    }

    private fun parseHhmm(raw: String): LocalTime? {
        val parts = raw.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    /** ICS 文本字段转义：反斜杠 / 分号 / 逗号 / 换行 */
    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n")

    private fun safeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "-").take(80).ifBlank { "unknown" }
}

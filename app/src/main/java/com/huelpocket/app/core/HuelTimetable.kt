package com.huelpocket.app.core

/**
 * 河南财经政法大学（HUEL）统一作息表。
 *
 * 这里是课表页、今天页和小组件共用的**唯一**节次来源：
 * 界面禁止再散落硬编码时间，新增/调整作息只改这一处。
 *
 * 数据来自用户提供的「课程时间设置」截图（每节 45 分钟，课间 10 分钟）。
 */
object HuelTimetable {

    /** 单节作息；时间用当天分钟数存储，便于排序与“正在上课”判断。 */
    data class Period(
        val indexNo: Int,
        val nameZh: String,
        val startMinute: Int,
        val endMinute: Int,
        val dayPart: String,
        val segmentIndex: Int,
    )

    private fun period(
        index: Int,
        startH: Int,
        startM: Int,
        endH: Int,
        endM: Int,
        dayPart: String,
        segment: Int,
    ) = Period(
        indexNo = index,
        nameZh = "第${index}节",
        startMinute = startH * 60 + startM,
        endMinute = endH * 60 + endM,
        dayPart = dayPart,
        segmentIndex = segment,
    )

    val PERIODS: List<Period> = listOf(
        // 上午 5 节
        period(1, 8, 0, 8, 45, "上午", 1),
        period(2, 8, 55, 9, 40, "上午", 1),
        period(3, 9, 55, 10, 40, "上午", 1),
        period(4, 10, 50, 11, 35, "上午", 1),
        period(5, 11, 45, 12, 30, "上午", 1),
        // 下午 5 节
        period(6, 13, 30, 14, 15, "下午", 2),
        period(7, 14, 25, 15, 10, "下午", 2),
        period(8, 15, 25, 16, 10, "下午", 2),
        period(9, 16, 20, 17, 5, "下午", 2),
        period(10, 17, 15, 18, 0, "下午", 2),
        // 晚上 3 节
        period(11, 19, 0, 19, 45, "晚上", 3),
        period(12, 19, 50, 20, 35, "晚上", 3),
        period(13, 20, 40, 21, 25, "晚上", 3),
    )

    val PERIOD_COUNT: Int = PERIODS.size

    fun periodAt(index: Int): Period? = PERIODS.firstOrNull { it.indexNo == index }

    /** 480 -> "08:00" */
    fun clock(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** 节次的完整时间段，如 "08:00-08:45"；未知节次返回 null */
    fun timeRange(index: Int): String? {
        val p = periodAt(index) ?: return null
        return "${clock(p.startMinute)}-${clock(p.endMinute)}"
    }

    /**
     * 解析教务的节次字段。
     *
     * 优先用 `jcs`（如 `3-5`），其次 `jc`（如 `3-5节`）；
     * 兼容单节（`5` / `5节`）、逗号分隔（`1,2`）与 `第3-5节` 这类前缀。
     *
     * ⚠️ 不按当前作息表的节次数做门禁：教务返回的节次可能超出用户自定义作息表
     * （如删节后），这里若把它判成非法就会退回 (1,1)，课程全部堆到第 1 行
     * ——正是「课程与节次时间对不上」的根因。渲染层负责把超范围节次吸附到最近行。
     */
    fun parseUnits(jc: String?, jcs: String?): Pair<Int, Int> {
        val raw = sequenceOf(jcs, jc)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            .orEmpty()
        if (raw.isEmpty()) return 1 to 1

        val range = Regex("""(\d+)\s*[-–—~到至]\s*(\d+)""").find(raw)
        if (range != null) {
            val start = range.groupValues[1].toIntOrNull()
            val end = range.groupValues[2].toIntOrNull()
            if (start != null && end != null && start >= 1 && end >= start) {
                return start to end
            }
        }

        val nums = Regex("""\d+""").findAll(raw)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it >= 1 }
            .toList()
        val first = nums.firstOrNull() ?: return 1 to 1
        val last = nums.lastOrNull()?.takeIf { it >= first } ?: first
        return first to last
    }
}

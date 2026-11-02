package com.huelpocket.app.ui.schedule

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.huelpocket.app.domain.Session
import com.huelpocket.app.ui.nav.DOCK_CLEARANCE
import com.huelpocket.glassui.GlassLevel
import com.huelpocket.glassui.GlassSurface
import com.huelpocket.glassui.GlassTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val DATE_FMT = DateTimeFormatter.ofPattern("M/d", Locale.getDefault())

/** 全网格唯一行高常量：左侧时间格、课程块、分隔线、调试线全部由它推导 */
private val ROW_HEIGHT = 64.dp
private val UNIT_COL_WIDTH = 62.dp
private val DAY_COL_WIDTH = 54.dp
private val HEADER_HEIGHT = 44.dp


/**
 * 课表页：周课表网格 + 周次切换。
 *
 * HUEL 版本：简化实现。左右箭头切周，网格按星期×节次展示课程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onOpenLogin: () -> Unit,
    onOpenOverview: (Int) -> Unit,
    onOpenDisplaySettings: () -> Unit,
    embedded: Boolean = false,
    vm: ScheduleViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 首次进入自动加载
    LaunchedEffect(Unit) { vm.start() }

    // 从登录页 / 设置页 / 后台返回时，重新按今天对准教学周
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.realignToCurrentWeek()
    }

    val semester = state.currentSemester
    val sessions = state.sessions
    val periods = state.periods

    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var exportHint by remember { mutableStateOf<String?>(null) }
    val canExport = semester != null && sessions.isNotEmpty() && !state.loading

    fun exportCourseTable() {
        val targetSemester = semester
        if (targetSemester == null || sessions.isEmpty()) {
            exportHint = "当前学期暂无课表可导出"
            return
        }
        exportHint = null
        exportScope.launch {
            // 体检 #4：URI 构造与 startActivity 也可能炸（IllegalArgumentException/
            // ActivityNotFoundException），必须和 write() 在同一个 runCatching 里，
            // 否则 onFailure 的文案永远不会出现、异常直接冒到父 Job 闪退。
            runCatching {
                val result = CourseIcsExporter.write(
                    context = context,
                    semester = targetSemester,
                    sessions = sessions,
                    periods = periods,
                    weekOneStart = state.weekStatus?.weekOneStart,
                )
                val uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    result.file,
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/calendar"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "导出课程表"))
                result
            }.onSuccess { result ->
                exportHint = if (result.skipped > 0) {
                    "已生成课程表文件，跳过 ${result.skipped} 条缺日期或时间"
                } else {
                    "已生成课程表文件"
                }
            }.onFailure {
                exportHint = "导出失败：" + (it.message ?: "未知错误")
            }
        }
    }

    // 体检 #4 次级：exportHint 之前只设不清，提示条会一直挂着；4 秒后自动消失
    LaunchedEffect(exportHint) {
        if (exportHint != null) {
            delay(4_000L)
            exportHint = null
        }
    }

    val content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit = { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = DOCK_CLEARANCE),
        ) {
            // 嵌入模式（课表 Tab）的紧凑顶栏：学期选择 + 刷新 + 设置
            if (embedded) {
                var semesterMenu by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { semesterMenu = true }) {
                        Text(
                            semester?.nameZh ?: "选择学期",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = semesterMenu,
                        onDismissRequest = { semesterMenu = false },
                    ) {
                        state.semesters.forEach { semesterOption ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(semesterOption.nameZh) },
                                onClick = {
                                    semesterMenu = false
                                    vm.selectSemesterById(semesterOption.id)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = ::exportCourseTable,
                        enabled = canExport,
                    ) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "导出课程表",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = { vm.refresh() },
                        enabled = !state.refreshing,
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    IconButton(onClick = onOpenDisplaySettings) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // 缓存提示
            state.cacheNotice?.let { notice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.cacheNoticeNeedsLogin) {
                        TextButton(onClick = onOpenLogin) { Text("去登录") }
                    }
                }
            }

            exportHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // 新学期开学日期待确认
            if (state.weekStatus?.needsConfirmation == true) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "请确认本学期第一周周一，保证周次准确。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenDisplaySettings) { Text("去设置") }
                }
            }

            if (state.loading && sessions.isEmpty()) {
                // 首次加载
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (!state.loggedIn && sessions.isEmpty()) {
                // 未登录
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("登录后查看课表", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onOpenLogin) { Text("去登录") }
                    }
                }
            } else if (sessions.isEmpty()) {
                // 没有课表数据
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有课表数据", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.refresh() }) { Text("刷新") }
                    }
                }
            } else {
                // 周次切换器：显示浏览中的周；「本周」标注 + 回到本周 + 边界提示
                WeekSwitcher(
                    week = state.currentWeek,
                    realWeek = state.realWeek,
                    totalWeeks = state.totalWeeks,
                    weekPhaseLabel = state.weekStatus?.label,
                    onPrev = { vm.selectWeek(state.currentWeek - 1) },
                    onNext = { vm.selectWeek(state.currentWeek + 1) },
                    onBackToCurrent = { vm.backToCurrentWeek() },
                )

                // 课表网格
                ScheduleGrid(
                    sessions = sessions.filter { it.weekIndex == state.currentWeek },
                    periods = periods,
                    currentWeek = state.currentWeek,
                    realWeek = state.realWeek,
                    weekStartDate = state.weekStatus?.weekOneStart
                        ?.plusWeeks((state.currentWeek - 1).coerceAtLeast(0).toLong()),
                )
            }
        }
    }

    if (embedded) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            content(androidx.compose.foundation.layout.PaddingValues(0.dp))
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("课表", style = MaterialTheme.typography.titleLarge)
                            semester?.let {
                                Text(
                                    it.nameZh,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            state.weekStatus?.let {
                                Text(
                                    it.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    actions = {
                        // 导出
                        IconButton(onClick = ::exportCourseTable, enabled = canExport) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = "导出课程表",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        // 刷新
                        IconButton(onClick = { vm.refresh() }, enabled = !state.refreshing) {
                            if (state.refreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                            }
                        }
                        // 设置
                        IconButton(onClick = onOpenDisplaySettings) {
                            Icon(Icons.Filled.Tune, contentDescription = "设置")
                        }
                    },
                )
            },
        ) { padding ->
            content(padding)
        }
    }
}

/**
 * 周次切换：左右箭头 + 浏览周显示。
 *
 * - 显示的是**当前浏览的周**（不再固定显示今天所在周，修复「卡死在第 8 周」）；
 * - 浏览周 == 今天所在周时标注「本周」；
 * - 范围钳制在 1..totalWeeks，越界给一次性提示；
 * - 浏览其他周时出现「回到本周」。
 */
@Composable
private fun WeekSwitcher(
    week: Int,
    realWeek: Int?,
    totalWeeks: Int,
    weekPhaseLabel: String?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBackToCurrent: () -> Unit,
) {
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) {
            kotlinx.coroutines.delay(2000)
            hint = null
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = {
                if (week <= 1) hint = "已经是第一周" else onPrev()
            }) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = "上一周",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text(
                    text = "第 $week 周",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (realWeek != null && week == realWeek) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "本周",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            IconButton(onClick = {
                if (week >= totalWeeks) {
                    hint = "已到本学期最后一周（共 $totalWeeks 周，假期中请到设置页更新开学日期）"
                } else {
                    onNext()
                }
            }) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "下一周",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 非「本周」或假期等状态时，给一行小字说明今天真实处于哪一周
            if (realWeek != null && week != realWeek) {
                TextButton(onClick = onBackToCurrent) { Text("回到本周（第 $realWeek 周）") }
            } else if (weekPhaseLabel != null && weekPhaseLabel != "第 $week 周") {
                Text(
                    text = weekPhaseLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 课表网格（超级课程表式布局）：
 *
 * - 行高唯一来源 [ROW_HEIGHT]：左侧时间格与课程层放在**同一个**垂直滚动 Row 里，
 *   时间格逐个固定 ROW_HEIGHT，课程层总高 = 行数 × ROW_HEIGHT，按构造保证对齐；
 * - 课程块只认节次：startUnit/endUnit → 行索引，块高 = (Y-X+1) × ROW_HEIGHT，
 *   从第 X 节的行边界起绝对定位，严禁任何"按开始时间反推行号"；
 * - 行分隔线与调试线基于同一 ROW_HEIGHT 绘制，左右贯通。
 */
@Composable
private fun ScheduleGrid(
    sessions: List<Session>,
    periods: List<com.huelpocket.app.domain.Period>,
    currentWeek: Int,
    realWeek: Int?,
    weekStartDate: LocalDate?,
) {
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    // 行轴唯一来源：DataStore 节次表（默认 13 行）
    val axis = periods.sortedBy { it.indexNo }
    val rowCount = axis.size.coerceAtLeast(1)
    val gridWidth = DAY_COL_WIDTH * 7
    val gridHeight = ROW_HEIGHT * rowCount
    val today = LocalDate.now()
    val todayIndex = today.dayOfWeek.value - 1
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    /** 节次 → 行索引。只认节次号；节次被删除/超出时吸附到最近行（保证课程不丢） */
    fun rowForUnit(unit: Int): Int {
        axis.indexOfFirst { it.indexNo == unit }.takeIf { it >= 0 }?.let { return it }
        if (axis.isEmpty()) return 0
        return when {
            unit <= axis.first().indexNo -> 0
            unit >= axis.last().indexNo -> axis.lastIndex
            else -> axis.indexOfFirst { it.indexNo > unit }.coerceAtLeast(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // sticky 表头：周几 + 日期，今天高亮；横向滚动与课程区同步
        Row(
            Modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .padding(bottom = 2.dp),
        ) {
            Box(Modifier.width(UNIT_COL_WIDTH))
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(horizontalScroll),
            ) {
                WEEKDAY_NAMES.forEachIndexed { index, weekday ->
                    val date = weekStartDate?.plusDays(index.toLong())
                    // 体检 #6：「今天」高亮只在浏览周=今天真实所在周时生效，
                    // 翻到第 3 周的周一不能再高亮
                    val isToday = index == todayIndex && currentWeek == realWeek
                    Box(
                        modifier = Modifier
                            .width(DAY_COL_WIDTH)
                            .height(HEADER_HEIGHT)
                            .padding(horizontal = 1.dp)
                            .then(
                                if (isToday) {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                                        RoundedCornerShape(8.dp),
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                weekday,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isToday) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                            )
                            Text(
                                date?.let { DATE_FMT.format(it) } ?: "—",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isToday) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // 主体：左侧时间列与课程层在同一个 Row 里，共用 ROW_HEIGHT
        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(verticalScroll),
        ) {
            // 左侧时间列：节次号 / 开始 / 结束 三行竖排，整格垂直居中
            Column {
                axis.forEach { period ->
                    Box(
                        modifier = Modifier
                            .width(UNIT_COL_WIDTH)
                            .height(ROW_HEIGHT),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                period.indexNo.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                            Text(
                                period.startText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 8.sp,
                                maxLines = 1,
                            )
                            Text(
                                period.endText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 8.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // 课程层：总高固定 = 行数 × ROW_HEIGHT（不 wrapContent），课程块绝对定位
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(horizontalScroll),
            ) {
                Box(
                    Modifier
                        .width(gridWidth)
                        .height(gridHeight),
                ) {
                    // 行分隔线：与时间列同一条水平线（同一 ROW_HEIGHT 推导）
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 0.5.dp.toPx()
                        for (i in 0..rowCount) {
                            val y = ROW_HEIGHT.toPx() * i
                            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
                        }
                        for (d in 0..7) {
                            val x = DAY_COL_WIDTH.toPx() * d
                            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                        }
                    }

                    // 课程块：只认节次，从起始节次行的行边界起占 (Y-X+1) 行
                    for (day in 1..7) {
                        sessions.filter { it.weekday == day }
                            .groupBy { rowForUnit(it.startUnit) }
                            .forEach { (row, group) ->
                                val endRow = rowForUnit(group.maxOf { it.endUnit })
                                val span = (endRow - row + 1).coerceAtLeast(1)
                                CourseBlock(
                                    sessions = group.sortedBy { it.startUnit },
                                    modifier = Modifier
                                        .offset(
                                            x = DAY_COL_WIDTH * (day - 1),
                                            y = ROW_HEIGHT * row,
                                        )
                                        .size(
                                            width = DAY_COL_WIDTH,
                                            height = ROW_HEIGHT * span,
                                        )
                                        .padding(1.dp),
                                )
                            }
                    }
                }
            }
        }
    }
}

/** 课程块：灰色玻璃卡；课程名两行省略 + 教室 + 教师，文字不溢出块外 */
@Composable
private fun CourseBlock(
    sessions: List<Session>,
    modifier: Modifier,
) {
    GlassSurface(
        modifier = modifier,
        shape = GlassTheme.shapes.compact,
        level = GlassLevel.Regular,
        contentColor = GlassTheme.colors.textPrimary,
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            Modifier.padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            sessions.forEach { session ->
                Text(
                    session.courseName,
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!session.roomText.isNullOrBlank()) {
                    Text(
                        session.roomText,
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTheme.colors.textSecondary,
                        fontSize = 8.sp,
                        lineHeight = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!session.teacherName.isNullOrBlank()) {
                    Text(
                        session.teacherName,
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTheme.colors.textTertiary,
                        fontSize = 8.sp,
                        lineHeight = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

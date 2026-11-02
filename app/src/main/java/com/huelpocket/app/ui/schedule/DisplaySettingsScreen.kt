package com.huelpocket.app.ui.schedule

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.huelpocket.app.core.AppGraph
import com.huelpocket.app.core.HuelSemester
import com.huelpocket.app.core.WeekMath
import com.huelpocket.app.core.WeekSettings
import com.huelpocket.app.core.WeekSource
import com.huelpocket.app.data.repo.TodayTimetable
import com.huelpocket.app.domain.Period
import com.huelpocket.app.ui.components.GlassCard
import com.huelpocket.app.ui.widget.Widgets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WeekSettingsUi(
    val semesterId: Int? = null,
    val weekStart: LocalDate? = null,
    val totalWeeks: Int = HuelSemester.DEFAULT_TOTAL_WEEKS,
    val status: HuelSemester.Status? = null,
    val periods: List<Period> = emptyList(),
    /** P1-1 自动校准候选（null = 无待确认结果） */
    val autoCalibrateCandidate: java.time.LocalDate? = null,
)

class DisplaySettingsViewModel : ViewModel() {

    private val repo = AppGraph.scheduleRepository
    private val prefs = AppGraph.uiPrefsStore
    private val periodSettings = AppGraph.periodSettingsStore

    private val _state = MutableStateFlow(WeekSettingsUi())
    val state: StateFlow<WeekSettingsUi> = _state.asStateFlow()

    init {
        viewModelScope.launch { refreshWeek() }
        viewModelScope.launch {
            periodSettings.periodsFlow.collect { periods ->
                _state.value = _state.value.copy(periods = periods)
            }
        }
    }

    private suspend fun refreshWeek() {
        val semester = runCatching { repo.currentSemester() }.getOrNull()
        if (semester == null) {
            _state.value = _state.value.copy(semesterId = null, weekStart = null, status = null)
            return
        }
        val settings = TodayTimetable.ensureWeekSettings(
            prefs = prefs,
            semesterId = semester.id,
            calendarStart = semester.startDate,
        )
        val status = HuelSemester.status(
            weekOneStart = settings.weekOneStart,
            totalWeeks = settings.totalWeeks,
            today = LocalDate.now(),
            needsConfirmation = !settings.confirmed,
        )
        _state.value = _state.value.copy(
            semesterId = semester.id,
            weekStart = settings.weekOneStart,
            totalWeeks = settings.totalWeeks ?: HuelSemester.DEFAULT_TOTAL_WEEKS,
            status = status,
        )
    }

    fun setWeekStart(date: LocalDate) {
        val semesterId = _state.value.semesterId ?: return
        viewModelScope.launch {
            val weekStart = WeekMath.startOfWeek(date, com.huelpocket.app.core.WeekStartDay.MONDAY)
            prefs.saveWeekSettings(
                semesterId,
                WeekSettings(
                    weekOneStart = weekStart,
                    startDay = com.huelpocket.app.core.WeekStartDay.MONDAY,
                    totalWeeks = _state.value.totalWeeks,
                    confirmed = true,
                    source = WeekSource.USER,
                ),
            )
            refreshWeek()
            refreshWidgets()
        }
    }

    fun updatePeriod(period: Period) {
        viewModelScope.launch {
            val next = _state.value.periods
                .filter { it.indexNo != period.indexNo } + period
            periodSettings.savePeriods(next)
            refreshWidgets()
        }
    }

    fun addPeriod() {
        viewModelScope.launch {
            val current = _state.value.periods
            val nextIndex = (current.maxOfOrNull { it.indexNo } ?: 0) + 1
            val next = current + Period(
                indexNo = nextIndex,
                nameZh = "第${nextIndex}节",
                startTime = 8 * 60,
                endTime = 8 * 60 + 45,
                dayPart = if (nextIndex <= 5) "上午" else if (nextIndex <= 10) "下午" else "晚上",
                segmentIndex = if (nextIndex <= 5) 1 else if (nextIndex <= 10) 2 else 3,
            )
            periodSettings.savePeriods(next)
            refreshWidgets()
        }
    }

    fun deletePeriod(period: Period) {
        viewModelScope.launch {
            periodSettings.savePeriods(_state.value.periods - period)
            refreshWidgets()
        }
    }

    fun restoreDefaultPeriods() {
        viewModelScope.launch {
            periodSettings.restoreDefault()
            refreshWidgets()
        }
    }

    /** P1-1：按课表位图自动校准开学日期。
     * 依据：全部课程最早有课周 W0；今天是第 W_now 周（按当前设置）；
     * 候选第一周周一 = 本周一 - (W_now - W0) 周。确认后写 DataStore。 */
    fun autoCalibrateWeek() {
        viewModelScope.launch {
            val semester = runCatching { repo.currentSemester() }.getOrNull()
            val current = _state.value
            if (semester == null || current.weekStart == null) return@launch
            val minWeek = runCatching { repo.sessionsOfSemester(semester.id) }
                .getOrNull()?.minOfOrNull { it.weekIndex }
            val todayWeek = WeekMath.weekIndexAt(current.weekStart!!, LocalDate.now())
            if (minWeek == null || todayWeek <= 0) return@launch
            val monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val candidate = monday.minusWeeks((todayWeek - minWeek).toLong())
            _state.value = current.copy(autoCalibrateCandidate = candidate)
        }
    }

    fun dismissAutoCalibrate() {
        _state.value = _state.value.copy(autoCalibrateCandidate = null)
    }

    private suspend fun refreshWidgets() {
        AppGraph.appContextOrNull()?.let { context ->
            runCatching { Widgets.refreshAll(context) }
        }
    }
}

/**
 * 课表设置页。
 *
 * 节次时间（手风琴式紧凑列表）与教学周起点共用同一数据入口；
 * 保存后课表 / 今天 / 小组件同步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(
    onBack: () -> Unit,
    onOpenOverview: (Int) -> Unit,
    vm: DisplaySettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    // 手风琴：同时只允许一行展开（存节次号；null = 全部收起）
    var expandedPeriod by remember { mutableStateOf<Int?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    // 体检 #3：节次时间弹窗状态必须 remember 在组合内。原实现是文件顶层
    // mutableStateOf（进程级单例）：返回本页再进会自动弹出上次弹窗、持旧快照、多实例共享。
    var editingPeriod by remember { mutableStateOf<Period?>(null) }
    var editingStart by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("教学周", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "今天是 ${state.status?.label ?: "未设置开学日期"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDatePicker = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "本学期第一周周一",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                if (state.status?.needsConfirmation == true) {
                                    "使用默认日期，点击确认或修改"
                                } else {
                                    "手动设置，立即同步课表 / 今天 / 小组件"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(state.weekStart?.toString() ?: "未设置")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.semesterId != null) {
                            TextButton(onClick = { state.semesterId?.let(onOpenOverview) }) {
                                Text("课程总览")
                            }
                        }
                        TextButton(onClick = vm::autoCalibrateWeek) { Text("按课表自动校准") }
                    }
                }
            }

            // ── 节次时间：手风琴式紧凑列表 ─────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .padding(vertical = 6.dp)
                        .animateContentSize(),
                ) {
                    Text(
                        "节次时间",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Text(
                        "点击某一行展开编辑；保存后课表、今天页与小组件同步。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )

                    val sorted = state.periods.sortedBy { it.indexNo }
                    sorted.forEach { period ->
                        val expanded = expandedPeriod == period.indexNo
                        PeriodAccordionRow(
                            period = period,
                            expanded = expanded,
                            onClick = {
                                expandedPeriod = if (expanded) null else period.indexNo
                            },
                            onStartEdit = {
                                editingPeriod = period
                                editingStart = true
                            },
                            onEndEdit = {
                                editingPeriod = period
                                editingStart = false
                            },
                            onDelete = {
                                vm.deletePeriod(period)
                                if (expandedPeriod == period.indexNo) expandedPeriod = null
                            },
                        )
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = vm::addPeriod) { Text("新增节次") }
                        TextButton(onClick = { showRestoreConfirm = true }) {
                            Text("恢复默认（财大作息）")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDatePicker) {
        val initial = state.weekStart
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val picked = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            vm.setWeekStart(picked)
                        }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState, title = null)
        }
    }

    state.autoCalibrateCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { vm.dismissAutoCalibrate() },
            title = { Text("自动校准开学日期") },
            text = {
                Text(
                    "依据：全部课程最早有课周，按当前周反推。\n\n" +
                        "推算第一周周一为：$candidate\n" +
                        "确认后写入并同步课表/今天/小组件。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setWeekStart(candidate)
                    vm.dismissAutoCalibrate()
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissAutoCalibrate() }) { Text("取消") }
            },
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("恢复默认作息") },
            text = { Text("将重置全部节次为财大默认作息（13 节），自定义时间会丢失。确定继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.restoreDefaultPeriods()
                        expandedPeriod = null
                        showRestoreConfirm = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") }
            },
        )
    }

    // 节次时间选择器（由手风琴行触发）
    editingPeriod?.let { period ->
        val hour = if (editingStart) period.startTime / 60 else period.endTime / 60
        val minute = if (editingStart) period.startTime % 60 else period.endTime % 60
        val pickerState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        // 体检 #3 次级：校验失败（开始 ≥ 结束）不再无条件关窗，就地提示
        var invalidRange by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { editingPeriod = null },
            title = { Text("修改${period.nameZh}${if (editingStart) "开始" else "结束"}时间") },
            text = {
                Column {
                    TimePicker(state = pickerState)
                    if (invalidRange) {
                        Text(
                            "开始时间必须早于结束时间",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val minuteOfDay = pickerState.hour * 60 + pickerState.minute
                        val updated = if (editingStart) {
                            period.copy(startTime = minuteOfDay)
                        } else {
                            period.copy(endTime = minuteOfDay)
                        }
                        if (updated.startTime <= updated.endTime) {
                            vm.updatePeriod(updated)
                            editingPeriod = null
                        } else {
                            invalidRange = true
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingPeriod = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 节次手风琴行：
 * 收起 = 单行（节次号加粗 + 时间段右对齐），整行可点；
 * 展开 = 仅该行向下展开编辑入口 + 删除，再点收起。
 * 高度动画用 animateContentSize（外层 Column 已挂）。
 */
@Composable
private fun PeriodAccordionRow(
    period: Period,
    expanded: Boolean,
    onClick: () -> Unit,
    onStartEdit: () -> Unit,
    onEndEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val rowColor = if (expanded) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .animateContentSize()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(rowColor, MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                period.nameZh,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${period.startText} - ${period.endText}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onStartEdit) {
                    Text("开始 ${period.startText}")
                }
                TextButton(onClick = onEndEdit) {
                    Text("结束 ${period.endText}")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("删除") }
                TextButton(onClick = onClick) { Text("完成") }
            }
        }
    }
}

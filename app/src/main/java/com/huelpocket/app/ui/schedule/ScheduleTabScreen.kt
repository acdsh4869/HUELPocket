package com.huelpocket.app.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.huelpocket.app.ui.today.TodayScreen
import com.huelpocket.glassui.GlassSegmentedControl
import java.time.LocalDate

private enum class ScheduleTabView { DAY, WEEK }

/**
 * 课表 Tab：日 / 周一体化入口。
 *
 * 默认按今天是工作日还是周末选择；用户切换只是浏览偏好，不会改变当前教学周计算。
 */
@Composable
fun ScheduleTabScreen(
    onOpenLogin: () -> Unit,
    onOpenOverview: (Int) -> Unit,
    onOpenDisplaySettings: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val defaultView = if (LocalDate.now().dayOfWeek.value in 1..5) {
        ScheduleTabView.DAY
    } else {
        ScheduleTabView.WEEK
    }
    var viewIndex by rememberSaveable {
        mutableIntStateOf(defaultView.ordinal)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("课表", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        GlassSegmentedControl(
            options = listOf("日", "周"),
            selectedIndex = viewIndex,
            onSelectedChange = { viewIndex = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f)) {
            when (viewIndex) {
                ScheduleTabView.DAY.ordinal -> TodayScreen(
                    onOpenLogin = onOpenLogin,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onOpenSchedule = {},
                    onOpenFocus = onOpenFocus,
                    embedded = true,
                )
                else -> ScheduleScreen(
                    onOpenLogin = onOpenLogin,
                    onOpenOverview = onOpenOverview,
                    onOpenDisplaySettings = onOpenDisplaySettings,
                    embedded = true,
                )
            }
        }
    }
}

package com.huelpocket.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.huelpocket.app.domain.FocusStats
import com.huelpocket.app.ui.components.GlassCard
import com.huelpocket.app.ui.nav.DOCK_CLEARANCE

/**
 * 「今天」课程时间轴。
 *
 * 一期收尾后不再是独立 Tab，而是嵌入「课表」的日视图；
 * [embedded] 为 true 时去掉自己的 Scaffold 和顶栏，由课表 Tab 统一排版。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenLogin: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenFocus: (() -> Unit)? = null,
    embedded: Boolean = false,
    vm: TodayViewModel = viewModel(),
    timelineVm: TodayTimelineViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val timeline by timelineVm.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.onResume()
        timelineVm.reload()
    }

    val content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit = { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = DOCK_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (!state.loggedIn) {
                LoginPromptCard(onOpenLogin)
            }

            // 番茄钟从 Dock 移除后，入口固定在日视图顶部
            onOpenFocus?.let { openFocus ->
                FocusEntryCard(
                    focusSeconds = timeline.focusSeconds,
                    focusCount = timeline.focusCount,
                    onOpenFocus = openFocus,
                )
            }

            // 今天的时间轴：上课 / 已完成的专注
            TodayTimelineCard(
                state = timeline,
                onOpenSchedule = if (embedded) null else onOpenSchedule,
            )

            // 今天的日程：自己在课表里加的
            TodayCustomScheduleCard(
                entries = timeline.customEntries,
                onOpenSchedule = if (embedded) null else onOpenSchedule,
            )

            Spacer(Modifier.height(4.dp))
        }
    }

    if (embedded) {
        content(androidx.compose.foundation.layout.PaddingValues(0.dp))
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("今天") },
                    actions = {
                        androidx.compose.material3.IconButton(onClick = onOpenDiagnostics) {
                            Icon(Icons.Filled.MoreHoriz, contentDescription = "登录状态")
                        }
                    },
                )
            },
        ) { padding ->
            content(padding)
        }
    }
}

@Composable
private fun FocusEntryCard(
    focusSeconds: Int,
    focusCount: Int,
    onOpenFocus: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text("专注", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = if (focusSeconds > 0) {
                    "今日 ${FocusStats.humanDuration(focusSeconds)} · $focusCount 个番茄"
                } else {
                    "开始一段不受打扰的学习时间"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Button(onClick = onOpenFocus) {
                Text("开始专注")
            }
        }
    }
}

@Composable
private fun LoginPromptCard(onOpenLogin: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("未登录", style = MaterialTheme.typography.titleMedium)
            Text("登录后可以查看课表与自定义日程。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenLogin) {
                Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("登录")
            }
        }
    }
}

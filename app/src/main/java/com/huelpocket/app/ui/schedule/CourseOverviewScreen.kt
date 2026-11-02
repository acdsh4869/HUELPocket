package com.huelpocket.app.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.huelpocket.app.domain.Course

/**
 * 课程总览：该学期要上的课**每行一门**。
 *
 * HUEL 版本：简化实现，只显示课程基本信息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseOverviewScreen(
    semesterId: Int,
    onBack: () -> Unit,
    vm: ScheduleViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 自己的 VM 实例也要先加载，之后再切到指定学期
    LaunchedEffect(state.semesters, semesterId) {
        if (state.semesters.isNotEmpty() && state.currentSemester?.id != semesterId) {
            vm.selectSemesterById(semesterId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课程总览") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            val courses = state.courses.filter { it.semesterId == semesterId }
            // key 用课程自身的稳定唯一键（Course.key），增删/重排时不整体重建
            items(courses, key = { it.key }) { course ->
                CourseOverviewCard(course)
            }

            if (courses.isEmpty()) {
                item {
                    Text(
                        text = "还没有课程数据，请先在课表页刷新",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun CourseOverviewCard(course: Course) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(course.nameZh, style = MaterialTheme.typography.titleMedium)
            if (course.code.isNotBlank()) {
                Text(
                    "课程号 ${course.code}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (course.teachers.isNotEmpty()) {
                Text(
                    "教师：${course.teachers.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (course.credits > 0) {
                Text(
                    "学分 ${course.credits}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${course.sessions.size} 次上课记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

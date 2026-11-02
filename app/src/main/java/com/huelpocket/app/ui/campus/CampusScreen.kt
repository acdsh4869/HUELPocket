package com.huelpocket.app.ui.campus

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import com.huelpocket.app.ui.components.GlassCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.huelpocket.app.core.AppGraph
import com.huelpocket.app.data.update.AppRelease
import com.huelpocket.app.data.update.AppUpdate
import com.huelpocket.app.data.update.UpdateCenter
import com.huelpocket.app.ui.nav.DOCK_CLEARANCE
import kotlinx.coroutines.launch

/**
 * 「校园」= 分组入口页（空教室已按需求从「课表」移到这里）。
 *
 * 只放用户功能入口；开发期的接口自检收在「登录状态」页里，且只在 debug 包显示。
 */
/** 敏感字段打码：保留首尾各 2~4 位，中间以 **** 替代 */
private fun maskMiddle(raw: String): String {
    if (raw.length <= 6) return raw
    val head = raw.substring(0, 3)
    val tail = raw.substring(raw.length - 2)
    return "$head****$tail"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusScreen(
    onOpenLogin: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenDisplaySettings: () -> Unit = {},
) {
    var loggedIn by remember { mutableStateOf(AppGraph.authRepository.isLoggedIn()) }
    // 从登录页返回时重新读一次：这一页没有自己的 ViewModel，否则会一直显示"登录 / 切换账号"
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        loggedIn = AppGraph.authRepository.isLoggedIn()
    }

    // 个人信息卡（只读）：学号/姓名/学院/专业/年级/宿舍；手机号/证件号默认打码
    var profile by remember { mutableStateOf<com.huelpocket.app.data.eas.ZhengFangEasClient.PersonalInfo?>(null) }
    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            runCatching {
                profile = AppGraph.zhengFangEasClient.fetchPersonalInfo()
            }
        }
    }

    // ── 检测更新：状态存放在 data.update.UpdateCenter（开屏已静默查过一次）──
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember { AppUpdate.localVersion(context) }
    val remoteRelease by UpdateCenter.release.collectAsState(initial = null)
    val mutedVersion by UpdateCenter.mutedVersion.collectAsState(initial = null)
    val showDot = AppUpdate.shouldShowDot(currentVersion, remoteRelease?.version, mutedVersion)
    var showUpdateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // 悬浮 dock 是覆盖层：垫出尾部余量，末尾条目能完整滚出胶囊
                .padding(bottom = DOCK_CLEARANCE),
        ) {
            GroupTitle("账号")
            // 个人信息卡：登录后自动加载（只读 GET xsxxwh_cxCkDgxsxx.html）
            profile?.let { info ->
                if (info.xh.isNotBlank() || info.xm.isNotBlank()) {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                listOf(info.xm, info.xh).filter { it.isNotBlank() }.joinToString("  "),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val detail = buildList {
                                val college = info.yxmc.ifBlank { info.jgmc }
                                if (college.isNotBlank()) add(college)
                                if (info.zyfsmc.isNotBlank()) add(info.zyfsmc)
                                if (info.bjmc.isNotBlank()) add(info.bjmc)
                                if (info.nj.isNotBlank()) add("${info.nj} 级")
                            }
                            if (detail.isNotEmpty()) {
                                Text(
                                    detail.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            EntryCard {
                // 登录态直接写进副标题，一个入口两种状态 ——
                // 之前"已登录 / 登录状态"两个条目登录后都指向同一个页，纯属重复。
                // 已登录时别把用户丢到登录表单上（issue #2 报告者提出）。
                if (com.huelpocket.app.BuildConfig.DEBUG) {
                    // 发版卫生：诊断页入口仅 debug 包渲染
                    Entry(
                        Icons.Filled.Person,
                        "账号（诊断）",
                        if (loggedIn) "已登录 · 查看状态、退出登录" else "未登录 · 使用教务账号登录",
                        if (loggedIn) onOpenDiagnostics else onOpenLogin,
                    )
                } else if (!loggedIn) {
                    Entry(
                        Icons.Filled.Person,
                        "账号",
                        "未登录 · 使用教务账号登录",
                        onOpenLogin,
                    )
                }
                Entry(Icons.Filled.Info, "关于", "版本、致谢与非官方声明", onOpenAbout)
            }

            // HUEL 版本：隐藏郑大专属功能（教室占用、图书馆、电费、校园卡）
            // 代码保留，入口关闭
            /*
            GroupTitle("学习空间")
            EntryCard {
                Entry(Icons.Filled.MeetingRoom, "教室占用", "按楼看每间教室哪一节有课", onOpenRooms)
                Entry(Icons.Filled.EventSeat, "图书馆座位预约", "学校原版网页（需联网）", onOpenLibrary)
            }

            GroupTitle("生活服务")
            EntryCard {
                Entry(Icons.Filled.CreditCard, "校园卡", "余额、日周月年消费、流水明细", onOpenCard)
            }
            */

            GroupTitle("课表与外观")
            EntryCard {
                Entry(
                    Icons.Filled.Schedule,
                    "节次时间与开学日期",
                    "查看 13 节作息，确认教学周起点",
                    onOpenDisplaySettings,
                )
                Entry(Icons.Filled.Palette, "主题与深色模式", "灰玻璃黑白默认，其他配色可选", onOpenAppearance)
            }

            GroupTitle("系统")
            EntryCard {
                // 有新 release 时副标题直接给出结论 + 图标红点；否则留一句"手动检查"的引导。
                Entry(
                    Icons.Filled.SystemUpdate,
                    "检测更新",
                    if (showDot) {
                        "发现新版本 v${remoteRelease?.version} · 点击查看"
                    } else {
                        "当前 v$currentVersion · 点这里手动检查"
                    },
                    { showUpdateDialog = true },
                    badge = showDot,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showUpdateDialog) {
        UpdateCheckDialog(
            current = currentVersion,
            onDismiss = { showUpdateDialog = false },
            onMute = { version ->
                scope.launch { UpdateCenter.setUpdateMutedVersion(version) }
            },
        )
    }
}

@Composable
private fun GroupTitle(text: String) {
    // 与卡片的屏幕边距（16dp）对齐；今天/课表页的页边距也是 16dp，全局一个节奏
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun EntryCard(content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(content = { content() })
    }
}

@Composable
private fun Entry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    /** 「有新东西」角标：图标右上角一枚小圆点（检测更新用） */
    badge: Boolean = false,
) {
    val enabled = onClick != null
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Box {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                if (badge) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-2).dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
            }
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        },
        modifier = if (enabled) Modifier.clickable(onClick = onClick!!) else Modifier,
    )
}

/**
 * 「检测更新」弹窗：结果三个分支 —— 有新版 / 已是最新 / 网络不通。
 *
 * 权衡过不做的事：
 *  · 不在 App 内下 APK 再装包 —— 那要 REQUEST_INSTALL_PACKAGES 权限和安装确认流，先做到"跳浏览器"；
 *  · 下载直链优先用 release assets 的 .apk（GitHub CDN），没有或打不开就退 release 页。
 */
@Composable
private fun UpdateCheckDialog(
    current: String,
    onDismiss: () -> Unit,
    onMute: (String) -> Unit,
) {
    val context = LocalContext.current
    val release by UpdateCenter.release.collectAsState(initial = null)
    val phase by UpdateCenter.phase.collectAsState(initial = UpdateCenter.Phase.Idle)

    // 弹窗判断不受「不再提醒」影响：进来了就把当前最新版说完，红点是否还亮由入口条自己管。
    val hasNew = AppUpdate.shouldShowDot(current, release?.version, muted = null)

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "没有找到可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    hasNew -> "发现新版本"
                    phase == UpdateCenter.Phase.Checking -> "正在检查更新"
                    else -> "检测更新"
                },
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    hasNew && release != null -> {
                        val r = release!!
                        Text(
                            text = "当前 v$current → 最新 v${r.version}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = r.notes.takeIf { it.isNotBlank() }
                                ?: "（这次 release 没写更新说明）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "下载需要能连上 GitHub（校园网偶尔拦，用移动数据更稳）。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(120.dp))
                    }

                    phase == UpdateCenter.Phase.Checking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text("正在连接 GitHub……", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(24.dp))
                    }

                    release != null -> {
                        // 查到了但不是更新（比当前版本旧/等）
                        Text(
                            "当前已是最新版本 v$current",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(24.dp))
                    }

                    else -> {
                        Text(
                            text = if (phase == UpdateCenter.Phase.Done) {
                                "连不上 GitHub（校园网可能拦了），换移动数据再试一次。"
                            } else {
                                "点「检查更新」向 GitHub 要一次最新 release。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (hasNew && release != null) {
                TextButton(onClick = { open(release!!.downloadUrl ?: release!!.pageUrl) }) {
                    Text("去下载")
                }
            } else {
                TextButton(
                    onClick = { UpdateCenter.checkExplicit() },
                    enabled = phase != UpdateCenter.Phase.Checking,
                ) {
                    Text(if (phase == UpdateCenter.Phase.Checking) "检查中…" else "检查更新")
                }
            }
        },
        dismissButton = {
            if (hasNew && release != null) {
                Row {
                    TextButton(onClick = {
                        onMute(release!!.version)
                        onDismiss()
                    }) {
                        Text("不再提醒此版本")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
    )
}

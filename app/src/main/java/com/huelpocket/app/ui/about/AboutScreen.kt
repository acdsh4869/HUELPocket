package com.huelpocket.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.huelpocket.app.BuildConfig
import com.huelpocket.app.ui.components.GlassCard

/** ⚠️ 发布前把这里改成你自己的仓库地址 */
private const val REPO_URL = "https://github.com/acdsh4869/HUELPocket"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    // 「用户须知」入口：随时可重看首启声明全文（viewOnly，不再要求勾选）
    var showNotice by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("口袋财大 HUELPocket", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "为河南财经政法大学学生整理课表、专注与校园服务的个人工具。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AboutCard("非官方声明") {
                Bullet("本应用为个人非官方项目，与河南财经政法大学无关；由个人开发者使用 AI 工具辅助制作。")
                Bullet("数据来源于学校公开系统；教务密码仅在本机加密存储，用于会话失效时静默续登。")
                Bullet("密码不会传输给本应用以外的第三方，也不会进入日志或云备份。")
                Bullet("请使用学校账号登录；如学校系统策略变化，功能可能受影响。")
            }

            AboutCard("数据与隐私") {
                Bullet("教务登录令牌只保存在本机加密存储中。")
                Bullet("课表、专注记录等数据只存在本机数据库，没有自建服务器，也不会上传。")
                Bullet("退出登录会清除本机令牌；卸载 App 会一并清掉所有本地数据。")
                Bullet("只读查询：不做选课、退课、充值或自动抢座。")
                Bullet("所有金额、成绩与课表数据以学校官方系统为准，本应用仅做展示。")
                Bullet("版本更新检查会匿名请求 GitHub API，不含任何个人信息。")
                Bullet("诊断日志仅保存在手机本地，只有你主动连接电脑导出时才会离开设备。")
            }

            AboutCard("开源与致谢") {
                Bullet("本项目的部分实现参考了 ZZUPocket（MIT License）的开源代码，感谢原作者。")
                Bullet("感谢 baihejiangnan 在 UI 设计上提供的帮助。")
                LinkRow("本项目开源仓库（acdsh4869/HUELPocket）", REPO_URL, uriHandler)
            }

            AboutCard("用户须知") {
                TextButton(onClick = { showNotice = true }) {
                    Text("重新查看首次使用须知与免责声明")
                }
            }

            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    if (showNotice) {
        FirstRunNotice(
            viewOnly = true,
            onAcknowledge = {},
            onDismiss = { showNotice = false },
        )
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LinkRow(label: String, url: String, uriHandler: androidx.compose.ui.platform.UriHandler) {
    TextButton(onClick = { uriHandler.openUri(url) }) { Text(label) }
}

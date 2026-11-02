package com.huelpocket.app.ui.diag

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.huelpocket.app.core.AppGraph
import com.huelpocket.app.data.auth.AuthRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FULL_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

data class DiagState(
    val status: AuthRepository.TokenStatus? = null,
    val menuProbeRunning: Boolean = false,
    val menuProbeText: String? = null,
)

class DiagnosticsViewModel : ViewModel() {
    private val auth: AuthRepository = AppGraph.authRepository

    private val _state = MutableStateFlow(DiagState())
    val state: StateFlow<DiagState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(status = auth.status())
    }

    fun logout() {
        auth.logout()
        AppGraph.refreshCredentialVault.clear()
        refresh()
    }

    /** 只读侦察：拉教务菜单 HTML，列出全部 clickMenu（成绩/考试路径以证据为准，不猜） */
    fun probeMenu() {
        _state.value = _state.value.copy(menuProbeRunning = true, menuProbeText = "侦察中…")
        viewModelScope.launch {
            val probe = com.huelpocket.app.data.eas.EasMenuProbe(
                com.huelpocket.app.core.net.HttpClients.zhengfang,
                AppGraph.tokenStore,
            )
            val text = try {
                val items = probe.fetchMenu()
                buildString {
                    append("共 ${items.size} 个菜单项（已全部写入 logcat）\n\n")
                    items.forEach { append("${it.gnmkdm} · ${it.name}\n${it.path}\n\n") }
                }
            } catch (e: Exception) {
                "侦察失败：${e.message}"
            }
            _state.value = _state.value.copy(menuProbeRunning = false, menuProbeText = text)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录状态") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
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
        ) {
            // 登录状态卡片
            state.status?.let { status ->
                com.huelpocket.app.ui.components.GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (status.loggedIn) "已登录" else "未登录",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (status.account != null) {
                            Text("账号：${status.account}")
                        }
                        if (status.lastLoginAt > 0) {
                            Text("登录时间：${FULL_FMT.format(Date(status.lastLoginAt))}")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 操作按钮
            if (state.status?.loggedIn == true) {
                Button(
                    onClick = { viewModel.logout() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("退出登录")
                }
            } else {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("去登录")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 教务菜单侦察（只读）：成绩/考试路径以真实 clickMenu 证据为准
            OutlinedButton(
                onClick = { viewModel.probeMenu() },
                enabled = !state.menuProbeRunning && state.status?.loggedIn == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.menuProbeRunning) "侦察中…" else "教务菜单侦察（只读）")
            }
            state.menuProbeText?.let {
                Spacer(modifier = Modifier.height(8.dp))
                com.huelpocket.app.ui.components.GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

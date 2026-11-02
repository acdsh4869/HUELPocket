package com.huelpocket.app.ui.login

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.phase) {
        if (state.phase == LoginPhase.Done) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录") },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "使用正方教务系统登录",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "账号密码与学校教务系统一致",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 提示信息
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    text = "登录失败 3 次会锁定 3 分钟，请确保密码正确。\n" +
                        "密码仅用于教务系统登录，不会上传到任何服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }

            if (state.phase == LoginPhase.NeedCaptcha) {
                // 验证码输入界面
                Text(
                    text = "请输入验证码",
                    style = MaterialTheme.typography.titleSmall,
                )

                // 显示验证码图片
                state.captchaImage?.let { imageBytes ->
                    val bitmap = remember(imageBytes) {
                        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "验证码",
                            modifier = Modifier.size(120.dp, 48.dp),
                        )
                        IconButton(onClick = vm::refreshCaptcha) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新验证码")
                        }
                    }
                }

                OutlinedTextField(
                    value = state.captcha,
                    onValueChange = vm::onCaptchaChange,
                    label = { Text("验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = vm::submitCaptcha,
                    enabled = !state.busy && state.captcha.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("提交")
                }

                TextButton(
                    onClick = { /* 返回重新输入账密 */ },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("返回重新输入")
                }
            } else {
                // 账密输入界面
                OutlinedTextField(
                    value = state.account,
                    onValueChange = vm::onAccountChange,
                    label = { Text("学号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = vm::onPasswordChange,
                    label = { Text("教务系统密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    // ⚠️ 必须 Password 键盘类型：普通文本键盘会被 vivo 等输入法
                    // 开启「自动更正 / 首字母大写」，密码在输入时被悄悄改掉，
                    // 服务端报「用户名或密码不正确」但用户以为自己输对了（2026-10-28 真机排查）。
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                // 输入体检的提示
                state.inputIssue?.let { issue ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = issue.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        if (issue.fixableByWidening) {
                            TextButton(onClick = vm::fixFullWidth) { Text("转为半角") }
                        }
                    }
                }
                Button(
                    onClick = vm::submitCredentials,
                    enabled = !state.busy && state.inputIssue == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("登录")
                }
            }

            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("正在处理…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

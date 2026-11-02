package com.huelpocket.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 首次启动的「用户须知与免责声明」。
 *
 * - gate 模式（首启）：全屏、不可点外关闭；勾选「我已知悉」后「开始使用」才可点。
 *   flag 写 SharedPreferences（key=first_run_ack_v1），文案大改时升 v2 可让老用户重看一次。
 * - viewOnly 模式（关于页「用户须知」入口）：只看全文，「我知道了」关闭，不再要求勾选。
 *
 * 纪律：纯本地文案，不弹每次启动、不引网页。
 */
private const val ACK_KEY = "first_run_ack_v1"

object FirstRunNoticePrefs {
    fun acknowledged(context: android.content.Context): Boolean =
        context.getSharedPreferences("first_run", android.content.Context.MODE_PRIVATE)
            .getBoolean(ACK_KEY, false)

    fun setAcknowledged(context: android.content.Context) {
        context.getSharedPreferences("first_run", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(ACK_KEY, true).apply()
    }
}

@Composable
fun FirstRunNotice(
    viewOnly: Boolean,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "首次使用须知与免责声明",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NoticeSection("一、非官方声明") {
                    Text(
                        "本应用与河南财经政法大学及其任何部门、胖乖生活等第三方无隶属关系、无合作、未获授权。" +
                            "校名仅用于标识服务对象学校。本应用由个人开发者使用 AI 工具辅助制作。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NoticeSection("二、账号与安全") {
                    Text(
                        "登录需要你的统一身份认证（教务）密码。密码仅加密保存在你手机本地，" +
                            "不上传任何服务器，不进入云备份与换机迁移。请自行评估将学校密码输入第三方应用的风险。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NoticeSection("三、数据与隐私") {
                    Text(
                        "课表、专注、一卡通等数据只保存在本机；应用不含任何统计/埋点 SDK；" +
                            "唯一的外部请求是访问学校各系统接口与 GitHub 版本检查（匿名，不含个人信息）；" +
                            "诊断日志仅在你主动连接电脑导出时离开手机。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NoticeSection("四、功能边界") {
                    Text(
                        "本应用对学校系统只做只读查询，不会替你选课、退课、充值或抢座。" +
                            "学校系统改版可能导致功能随时不可用，一切信息请以学校官方渠道为准。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NoticeSection("五、支付") {
                    Text(
                        "洗衣机付款会跳转胖乖生活官方收银台完成，本应用不经手任何资金，请核对金额后再支付。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NoticeSection("六、下载渠道") {
                    Text(
                        "请只通过以下两个官方渠道下载本应用：①本项目官方开源仓库" +
                            "（github.com/acdsh/HUELPocket 的 Releases 页面）；" +
                            "②官方发布的蓝奏云网盘（具体地址以官方开源仓库内的说明为准）。" +
                            "除上述渠道外，从其他网盘、群文件、二手市场等处获取的安装包可能已被恶意篡改，" +
                            "若因此导致你的账号、密码或个人信息泄露，由篡改者与传播渠道负责，与本项目无关。" +
                            "安装前可核对版本号与更新说明是否与官方渠道一致。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NoticeSection("七、免责范围") {
                    Text(
                        "本应用按「现状」提供，不对可用性、及时性、准确性作任何保证；" +
                            "使用本应用产生的一切后果由使用者自行承担。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (viewOnly) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                ) {
                    Text("我知道了")
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { checked = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Text(
                        "我已知悉并理解上述内容",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = onAcknowledge,
                    enabled = checked,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp),
                ) {
                    Text("开始使用")
                }
            }
        }
    }
}

@Composable
private fun NoticeSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

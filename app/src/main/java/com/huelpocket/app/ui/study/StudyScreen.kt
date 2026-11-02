package com.huelpocket.app.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.huelpocket.app.ui.components.GlassCard
import com.huelpocket.app.ui.library.LibraryScreen
import com.huelpocket.app.ui.nav.DOCK_CLEARANCE
import com.huelpocket.app.ui.web.EasWebViewScreen
import com.huelpocket.glassui.GlassTheme

private data class Entry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val url: String,
)

private const val EAS_HOME_URL = "https://xk.huel.edu.cn/jwglxt/xtgl/index_initMenu.html"

/** 学习 tab：教务四卡（WebView 免登）+ 图书馆两卡（现有实现搬入），均为入口卡。 */
@Composable
fun StudyScreen() {
    // rememberSaveable：切 tab（saveState/restoreState）后 WebView 目标与图书馆状态不丢
    var easHome by rememberSaveable { mutableStateOf(false) }
    var seatView by rememberSaveable { mutableStateOf(false) }
    var scanView by rememberSaveable { mutableStateOf(false) }

    when {
        easHome -> {
            EasWebViewScreen(title = "教务系统", url = EAS_HOME_URL, onBack = { easHome = false })
            return
        }
        seatView -> {
            LibraryScreen(
                startView = com.huelpocket.app.ui.library.LibraryTabView.Appointment,
                onExit = { seatView = false },
            )
            return
        }
        scanView -> {
            LibraryScreen(
                startView = com.huelpocket.app.ui.library.LibraryTabView.Scan,
                onExit = { scanView = false },
            )
            return
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SectionTitle("教务系统", "成绩、考试、空教室、培养方案等全部功能")
        GlassCard(Modifier.fillMaxWidth(), onClick = { easHome = true }) {
            EntryRow(Icons.Filled.School, "教务系统", "官方页面内自行进入各功能")
        }

        SectionTitle("图书馆", "扫码入馆 / 预约座位")
        GlassCard(Modifier.fillMaxWidth(), onClick = { scanView = true }) {
            EntryRow(Icons.Filled.QrCodeScanner, "扫码入馆", "直达原生扫码页，无中间层")
        }
        GlassCard(Modifier.fillMaxWidth(), onClick = { seatView = true }) {
            EntryRow(Icons.Filled.EventSeat, "预约座位", "直达超星预约页，CAS 会话保持")
        }

        Spacer(Modifier.height(DOCK_CLEARANCE))
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = GlassTheme.colors.textTertiary)
    }
}

@Composable
internal fun EntryRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = GlassTheme.colors.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = GlassTheme.colors.textSecondary)
        }
    }
}

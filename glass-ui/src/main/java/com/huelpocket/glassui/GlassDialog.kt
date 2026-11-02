package com.huelpocket.glassui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.huelpocket.glassui.internal.LocalBackdropLayer
import com.huelpocket.glassui.internal.LocalGlassOverlayHost
import com.huelpocket.glassui.internal.OverlayEntry
import kotlinx.coroutines.launch

/**
 * 玻璃对话框（iOS Alert 风格）：32dp 圆角，最高层级材质，弹出时轻微缩放回弹。
 *
 * 和 Material 的 AlertDialog 一样用「条件组合」控制显示：`if (show) GlassDialog(...)`。
 *
 * 只要在 [GlassTheme] 之下、且当前界面上有 [GlassScaffold] / [GlassBackdrop]，对话框就渲染在同一窗口的
 * 最上层并模糊整个页面（调用位置不限，写在 Scaffold 外面也可以）；否则退化为普通 Dialog 窗口
 * （无背景模糊，使用更实的底色保证可读）。
 *
 * @param title 标题（一级字号）。
 * @param message 说明文字（二级字号），可选。
 * @param confirmButton 确认按钮区，通常放一个 `GlassButton(modifier = Modifier.weight(1f))`。
 * @param dismissButton 取消按钮区，可选，位于确认按钮左侧。
 * @param icon 标题上方的图标，可选。
 * @param dismissOnScrimClick 点击遮罩是否关闭。
 * @param content 标题 / 说明与按钮之间的自定义内容，可选。
 */
@Composable
public fun GlassDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissButton: (@Composable RowScope.() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    dismissOnScrimClick: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val host = LocalGlassOverlayHost.current
    val panel: @Composable () -> Unit = {
        DialogPanel(modifier, title, message, icon, content, confirmButton, dismissButton)
    }
    if (host == null || !host.hasRenderer) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(dismissOnClickOutside = dismissOnScrimClick, usePlatformDefaultWidth = false),
        ) {
            CompositionLocalProvider(LocalBackdropLayer provides null) {
                Box(Modifier.padding(horizontal = GlassSpacing.X4)) { panel() }
            }
        }
        return
    }

    val latestPanel by rememberUpdatedState(panel)
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestScrimDismiss by rememberUpdatedState(dismissOnScrimClick)
    val entry = remember(host) {
        OverlayEntry {
            DialogOverlay(
                onDismiss = { latestDismiss() },
                dismissOnScrimClick = latestScrimDismiss,
                panel = latestPanel,
            )
        }
    }
    DisposableEffect(host, entry) {
        host.add(entry)
        onDispose { host.remove(entry) }
    }
}

@Composable
private fun DialogOverlay(
    onDismiss: () -> Unit,
    dismissOnScrimClick: Boolean,
    panel: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val colors = GlassTheme.colors
    val appear = remember { Animatable(0f) }
    val scale = remember { Animatable(1.12f) }
    LaunchedEffect(Unit) {
        launch { appear.animateTo(1f, tween(durationMillis = 180)) }
        scale.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(colors.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = dismissOnScrimClick,
                onClick = onDismiss,
            )
            .padding(horizontal = GlassSpacing.X4),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                // 吞掉面板上的点击，避免穿透到遮罩
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) { panel() }
    }
}

@Composable
private fun DialogPanel(
    modifier: Modifier,
    title: String,
    message: String?,
    icon: (@Composable () -> Unit)?,
    content: (@Composable ColumnScope.() -> Unit)?,
    confirmButton: @Composable RowScope.() -> Unit,
    dismissButton: (@Composable RowScope.() -> Unit)?,
) {
    val colors = GlassTheme.colors
    val typography = GlassTheme.typography
    GlassContainer(
        modifier = modifier.widthIn(min = 280.dp, max = 360.dp),
        shape = GlassTheme.shapes.dialog,
        level = GlassLevel.Overlay,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = GlassSpacing.X3, end = GlassSpacing.X3, top = GlassSpacing.X3),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) {
                    icon()
                    Spacer(Modifier.height(GlassSpacing.X1))
                }
                Text(title, style = typography.title, color = colors.textPrimary, textAlign = TextAlign.Center)
                if (message != null) {
                    Spacer(Modifier.height(GlassSpacing.X1))
                    Text(message, style = typography.body, color = colors.textSecondary, textAlign = TextAlign.Center)
                }
            }
            if (content != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = GlassSpacing.X3, end = GlassSpacing.X3, top = GlassSpacing.X2),
                    content = content,
                )
            }
            // 按钮区内边距 16dp：对话框 32dp 圆角 − 16dp = 按钮 16dp 圆角（同心圆角）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(GlassSpacing.X2)
                    .padding(top = GlassSpacing.X1),
                horizontalArrangement = Arrangement.spacedBy(GlassSpacing.X1),
            ) {
                dismissButton?.invoke(this)
                confirmButton()
            }
        }
    }
}

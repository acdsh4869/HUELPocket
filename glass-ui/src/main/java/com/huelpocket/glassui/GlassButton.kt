package com.huelpocket.glassui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 按钮样式。 */
public enum class GlassButtonStyle {
    /** 透明玻璃 + 一级文字色。次要操作。 */
    Glass,

    /** 强调色染色玻璃 + 白字。主要操作。 */
    Prominent,

    /** 透明玻璃 + 危险色文字。退出、删除等。 */
    Destructive,
}

/** [GlassButton] 的默认尺寸参数。 */
public object GlassButtonDefaults {
    /** 最小高度 48dp（触控目标）。 */
    public val MinHeight: Dp = 48.dp

    /** 水平 24dp / 垂直 12dp。 */
    public val ContentPadding: PaddingValues = PaddingValues(horizontal = GlassSpacing.X3, vertical = 12.dp)

    /** 图标按钮直径 44dp。 */
    public val IconButtonSize: Dp = 44.dp
}

/**
 * 玻璃按钮：16dp 圆角，最小高度 48dp。按下时缩小到 95% 并提亮。
 *
 * @param style 见 [GlassButtonStyle]。
 * @param shape 默认按钮圆角（16dp）。
 */
@Composable
public fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: GlassButtonStyle = GlassButtonStyle.Glass,
    shape: Shape = GlassTheme.shapes.button,
    contentPadding: PaddingValues = GlassButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val (tint, contentColor) = when (style) {
        GlassButtonStyle.Glass -> Color.Unspecified to colors.textPrimary
        GlassButtonStyle.Prominent ->
            colors.accent.copy(alpha = if (GlassTheme.isDark) 0.78f else 0.86f) to colors.onAccent
        GlassButtonStyle.Destructive -> Color.Unspecified to colors.danger
    }
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressProgress(interaction, enabled)
    GlassContainer(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .pressScale(press, PRESSED_SCALE_BUTTON)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .defaultMinSize(minHeight = GlassButtonDefaults.MinHeight),
        shape = shape,
        level = GlassLevel.Regular,
        tint = tint,
        pressedProgress = { press.value },
        contentColor = contentColor,
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides GlassTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
        ) {
            Row(
                Modifier.padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(GlassSpacing.X1, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/**
 * 圆形玻璃图标按钮（44dp），用于顶栏操作、浮动操作等。
 */
@Composable
public fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = GlassButtonDefaults.IconButtonSize,
    level: GlassLevel = GlassLevel.Regular,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressProgress(interaction, enabled)
    GlassContainer(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .pressScale(press, PRESSED_SCALE_BUTTON)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = GlassTheme.shapes.capsule,
        level = level,
        pressedProgress = { press.value },
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

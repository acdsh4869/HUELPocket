package com.huelpocket.glassui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * iOS 风格开关（51×31dp）。「开」为系统绿，「关」为玻璃凹槽。
 */
@Composable
public fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = GlassTheme.colors
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "switch",
    )
    val track by animateColorAsState(if (checked) colors.positive else colors.well, label = "switchTrack")
    val thumbShadow = if (GlassTheme.isDark) Color.Black.copy(alpha = 0.35f) else Color(0xFF0B1220).copy(alpha = 0.18f)
    Box(
        modifier
            .size(width = 51.dp, height = 31.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange,
            )
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(track, cornerRadius = r)
                val inset = 2.dp.toPx()
                val d = size.height - inset * 2
                val x = inset + (size.width - inset * 2 - d) * progress
                val center = Offset(x + d / 2f, size.height / 2f)
                drawCircle(thumbShadow, radius = d / 2f + 0.5.dp.toPx(), center = center + Offset(0f, 1.dp.toPx()))
                drawCircle(Color.White, radius = d / 2f, center = center)
            },
    )
}

/**
 * 玻璃分段控件（胶囊形，高 36dp）。滑块在选项间移动时有与导航栏一致的液滴拉伸。
 *
 * 常用于「跟随系统 / 浅色 / 深色」这类 2~4 个互斥选项。
 */
@Composable
public fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(options.isNotEmpty()) { "GlassSegmentedControl 至少需要一个选项" }
    val colors = GlassTheme.colors
    val dark = GlassTheme.isDark
    val count = options.size
    val previous = remember { intArrayOf(selectedIndex) }
    val movingRight = selectedIndex >= previous[0]
    SideEffect { previous[0] = selectedIndex }
    val fast = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
    val slow = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)
    val left by animateFloatAsState(selectedIndex.toFloat(), if (movingRight) slow else fast, label = "segLeft")
    val right by animateFloatAsState(selectedIndex + 1f, if (movingRight) fast else slow, label = "segRight")
    val thumb = if (dark) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.92f)
    val thumbShadow = if (dark) Color.Black.copy(alpha = 0.25f) else Color(0xFF0B1220).copy(alpha = 0.10f)

    Row(
        modifier
            .height(36.dp)
            .drawBehind {
                drawRoundRect(colors.well, cornerRadius = CornerRadius(size.height / 2f))
                val inset = 3.dp.toPx()
                val slot = (size.width - inset * 2) / count
                drawThumb(
                    x0 = inset + left * slot,
                    x1 = inset + right * slot,
                    top = inset,
                    height = size.height - inset * 2,
                    fill = thumb,
                    shadow = thumbShadow,
                    rim = colors.rimLight,
                )
            }
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { this.selected = selected }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelectedChange(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = GlassTheme.typography.caption.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = GlassSpacing.X1),
                )
            }
        }
    }
}

private fun DrawScope.drawThumb(
    x0: Float,
    x1: Float,
    top: Float,
    height: Float,
    fill: Color,
    shadow: Color,
    rim: Color,
) {
    val r = CornerRadius(height / 2f)
    drawRoundRect(shadow, Offset(x0, top + 1.dp.toPx()), Size(x1 - x0, height), r)
    drawRoundRect(fill, Offset(x0, top), Size(x1 - x0, height), r)
    drawRoundRect(
        color = rim.copy(alpha = rim.alpha * 0.6f),
        topLeft = Offset(x0, top),
        size = Size(x1 - x0, height),
        cornerRadius = r,
        style = Stroke(width = 1.dp.toPx()),
    )
}

package com.huelpocket.glassui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * 玻璃卡片。
 *
 * @param onClick 非空时卡片可点击：按下时轻微缩小并提亮（无水波纹）。
 * @param shape 默认 24dp 卡片圆角；课程格子等紧凑场景用 `GlassTheme.shapes.compact`（12dp）。
 * @param tint 额外染色，需自带透明度（如课程色 25%）。
 * @param contentPadding 内边距，默认 16dp。
 */
@Composable
public fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = GlassTheme.shapes.card,
    tint: Color = Color.Unspecified,
    level: GlassLevel = GlassLevel.Regular,
    shadow: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(GlassSpacing.X2),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressProgress(interaction, enabled = onClick != null)
    GlassContainer(
        modifier = modifier
            .pressScale(press, PRESSED_SCALE_CARD)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        shape = shape,
        level = level,
        tint = tint,
        shadow = shadow,
        pressedProgress = { press.value },
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

internal const val PRESSED_SCALE_CARD = 0.97f
internal const val PRESSED_SCALE_BUTTON = 0.95f

@Composable
internal fun rememberPressProgress(interaction: MutableInteractionSource, enabled: Boolean): State<Float> {
    val pressed by interaction.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "glassPress",
    )
}

internal fun Modifier.pressScale(progress: State<Float>, pressedScale: Float): Modifier = graphicsLayer {
    val s = 1f - (1f - pressedScale) * progress.value
    scaleX = s
    scaleY = s
}

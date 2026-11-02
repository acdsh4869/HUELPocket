package com.huelpocket.glassui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import com.huelpocket.glassui.internal.BackdropLayer
import com.huelpocket.glassui.internal.LocalBackdropLayer
import com.huelpocket.glassui.internal.glassAmbientShadow
import com.huelpocket.glassui.internal.glassRim
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/**
 * 通用玻璃面：所有 Glass* 组件的底层积木，主 App 自定义玻璃块时直接用它。
 *
 * 绘制层次（自下而上）：环境阴影（仅外缘）→ 背景模糊 + 饱和度增益 → 层级底色 → [tint] 染色 → 顶部柔光 →
 * 内容 → 方向光描边（左上高光 / 右下暗边）。
 *
 * 背景模糊需要宿主：放在 [GlassScaffold] 或 [GlassBackdrop] 里才能采样到背后的壁纸 / 内容；
 * 没有宿主时退化为「半透明底色 + 描边」，不会崩溃。
 *
 * @param shape 形状，默认卡片圆角（24dp）。建议取 [GlassTheme.shapes] 中的档位。
 * @param level 玻璃层级，决定模糊强度与底色透明度。
 * @param tint 额外染色（如课程颜色），需自带透明度，例如 `Color(0xFF34C759).copy(alpha = 0.25f)`。
 * @param shadow 是否绘制轻量环境阴影。
 * @param contentColor 内部 Text / Icon 的默认颜色。
 */
@Composable
public fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = GlassTheme.shapes.card,
    level: GlassLevel = GlassLevel.Regular,
    tint: Color = Color.Unspecified,
    shadow: Boolean = true,
    contentColor: Color = GlassTheme.colors.textPrimary,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassContainer(
        modifier = modifier,
        shape = shape,
        level = level,
        tint = tint,
        shadow = shadow,
        contentColor = contentColor,
        contentAlignment = contentAlignment,
        content = content,
    )
}

@Composable
internal fun GlassContainer(
    modifier: Modifier,
    shape: Shape,
    level: GlassLevel,
    tint: Color = Color.Unspecified,
    shadow: Boolean = true,
    pressedProgress: () -> Float = { 0f },
    contentColor: Color = GlassTheme.colors.textPrimary,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    backdrop: BackdropLayer? = LocalBackdropLayer.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val material = GlassTheme.materials[level]
    val shadowModifier = if (shadow) {
        Modifier.glassAmbientShadow(shape, material.shadowColor, material.shadowRadius, material.shadowOffsetY)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(shadowModifier)
            .glassRim(shape, colors.rimLight, colors.rimShadow),
        contentAlignment = contentAlignment,
        propagateMinConstraints = propagateMinConstraints,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .glassBackdrop(backdrop, material, colors, tint, pressedProgress),
        )
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
            LocalTextStyle provides GlassTheme.typography.body,
        ) {
            content()
        }
    }
}

/**
 * 玻璃体本身（已被裁剪成形状）：模糊背景 + 底色 + 染色 + 柔光 + 按压提亮。
 */
@Composable
internal fun Modifier.glassBackdrop(
    backdrop: BackdropLayer?,
    material: GlassMaterial,
    colors: GlassColors,
    tint: Color,
    pressedProgress: () -> Float,
): Modifier {
    val mode = GlassTheme.blurMode
    val live = mode == GlassBlurMode.Live && backdrop != null

    fun DrawScope.drawOverlays(baseTint: Color) {
        drawRect(baseTint)
        if (tint.isSpecified) drawRect(tint)
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to colors.sheen,
                0.55f to colors.sheen.copy(alpha = 0f),
            ),
        )
        val pressed = pressedProgress()
        if (pressed > 0f) drawRect(colors.pressedOverlay.copy(alpha = colors.pressedOverlay.alpha * pressed))
    }

    if (live) {
        val saturation = remember(material.saturation) { saturationEffect(material.saturation) }
        val style = remember(material, colors.backdropBase) {
            HazeStyle(
                backgroundColor = colors.backdropBase,
                tints = emptyList(),
                blurRadius = material.blurRadius,
                noiseFactor = material.noise,
                fallbackTint = HazeTint(material.staticTint),
            )
        }
        return this
            .drawWithContent {
                drawContent()
                drawOverlays(material.tint)
            }
            .graphicsLayer { renderEffect = saturation }
            .hazeChild(backdrop!!.hazeState, style) { blurEnabled = true }
    }

    // 静态降级 / 无宿主
    val root = backdrop?.staticRoot
    var position by remember { mutableStateOf(Offset.Unspecified) }
    return this
        .then(
            if (root != null) {
                Modifier.onGloballyPositioned { position = it.positionInWindow() }
            } else {
                Modifier
            },
        )
        .drawBehind {
            val snapshot = root?.staticSnapshot
            val origin = root?.windowPosition ?: Offset.Unspecified
            if (snapshot != null && origin.isSpecified && position.isSpecified) {
                drawRect(colors.backdropBase)
                drawImage(
                    image = snapshot,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(snapshot.width, snapshot.height),
                    dstOffset = (origin - position).round(),
                    dstSize = root.size,
                    filterQuality = FilterQuality.Low,
                )
            }
            drawOverlays(material.staticTint)
        }
}

private fun saturationEffect(saturation: Float): androidx.compose.ui.graphics.RenderEffect =
    android.graphics.RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) }),
    ).asComposeRenderEffect()

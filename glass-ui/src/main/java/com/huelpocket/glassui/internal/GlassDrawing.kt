package com.huelpocket.glassui.internal

import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.SweepGradient
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow

/** 光源方向：左上偏上（屏幕坐标系角度，0° 指向右，顺时针）。 */
private const val LIGHT_ANGLE_DEG = 235f

internal val RimWidth: Dp = 1.dp
private val InnerGlowWidth: Dp = 3.dp

/**
 * 方向光描边：受光侧（左上）亮色高光，背光侧（右下）细暗边，两侧之间平滑过渡到透明。
 *
 * 用「以中心为原点的角度」近似边缘法线方向；对宽扁的形状先在短轴方向拉伸成正方形空间再求角度，
 * 这样胶囊的整条上沿都能被照亮，而不是只亮左端。描边画在形状内侧（裁剪后画 2 倍宽），不会被外部裁掉。
 */
internal fun Modifier.glassRim(
    shape: Shape,
    rimLight: Color,
    rimShadow: Color,
    intensity: Float = 1f,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val clip = Path().apply { addOutline(outline) }
    val rimPx = max(1f, RimWidth.toPx())
    val glowPx = InnerGlowWidth.toPx()
    val rimBrush = directionalBrush(size, rimLight, rimShadow, intensity)
    // 内侧再加一道很淡的宽光带，模拟玻璃厚度带来的折射亮边（只在受光侧）
    val glowBrush = directionalBrush(size, rimLight.copy(alpha = rimLight.alpha * 0.22f), Color.Transparent, intensity)
    onDrawWithContent {
        drawContent()
        clipPath(clip) {
            drawOutline(outline, glowBrush, style = Stroke(width = glowPx * 2f))
            drawOutline(outline, rimBrush, style = Stroke(width = rimPx * 2f))
        }
    }
}

private fun directionalBrush(size: Size, light: Color, shadow: Color, intensity: Float): Brush {
    val steps = 48
    val colors = IntArray(steps + 1)
    val positions = FloatArray(steps + 1)
    for (i in 0..steps) {
        val fraction = i / steps.toFloat()
        val angle = fraction * 360f
        var diff = abs(angle - LIGHT_ANGLE_DEG) % 360f
        if (diff > 180f) diff = 360f - diff
        val f = cos(diff / 180f * PI).toFloat()
        val color = if (f >= 0f) {
            light.copy(alpha = (light.alpha * f.pow(1.4f) * intensity).coerceIn(0f, 1f))
        } else {
            shadow.copy(alpha = (shadow.alpha * (-f).pow(1.4f) * intensity).coerceIn(0f, 1f))
        }
        colors[i] = color.toArgb()
        positions[i] = fraction
    }
    val cx = size.width / 2f
    val cy = size.height / 2f
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val shader = SweepGradient(cx, cy, colors, positions)
            // 把形状「压」成正方形空间再算角度
            val sx = if (size.width > size.height) size.width / max(1f, size.height) else 1f
            val sy = if (size.height > size.width) size.height / max(1f, size.width) else 1f
            shader.setLocalMatrix(Matrix().apply { setScale(sx, sy, cx, cy) })
            return shader
        }
    }
}

/**
 * 轻量环境阴影：只画在玻璃外缘（内部裁掉），避免半透明玻璃内部被阴影「弄脏」。
 * 统一用圆角矩形近似形状，走 GPU 解析模糊快路径。
 */
internal fun Modifier.glassAmbientShadow(
    shape: Shape,
    color: Color,
    radius: Dp,
    offsetY: Dp,
): Modifier = if (color.alpha <= 0f || radius <= 0.dp) this else drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val clipOut = Path().apply { addOutline(outline) }.asAndroidPath()
    val cornerPx = outlineCornerRadius(outline, size)
    val blurPx = radius.toPx()
    val dy = offsetY.toPx()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        maskFilter = BlurMaskFilter(blurPx / 2f, BlurMaskFilter.Blur.NORMAL)
    }
    onDrawBehind {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.clipOutPath(clipOut)
            // 阴影比玻璃略收进，避免左右两侧出现「描黑边」的感觉
            val inset = blurPx * 0.15f
            native.drawRoundRect(
                inset, dy, size.width - inset, size.height + dy - inset * 0.5f,
                cornerPx, cornerPx, paint,
            )
            native.restore()
        }
    }
}

private fun outlineCornerRadius(outline: Outline, size: Size): Float = when (outline) {
    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
    is Outline.Rectangle -> 0f
    // 平滑圆角：视觉半径约等于名义半径，取短边的一部分做近似即可（阴影本身是模糊的）
    is Outline.Generic -> minOf(size.width, size.height) * 0.22f
}

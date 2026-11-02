package com.huelpocket.glassui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 全库统一的圆角档位。嵌套时遵循「同心圆角」：内层圆角 = 外层圆角 − 内边距。
 *
 * | 档位 | 半径 | 用途 |
 * |---|---|---|
 * | [Tag] | 8dp | 标签、徽标 |
 * | [Compact] | 12dp | 课程格子、紧凑卡片 |
 * | [Button] | 16dp | 按钮、输入框、卡片内的嵌套块 |
 * | [Card] | 24dp | 卡片、分组列表 |
 * | [Dialog] | 32dp | 对话框 |
 * | 胶囊 | 高度的一半 | 悬浮导航、图标按钮、开关、分段控件 |
 */
public object GlassRadius {
    public val Tag: Dp = 8.dp
    public val Compact: Dp = 12.dp
    public val Button: Dp = 16.dp
    public val Card: Dp = 24.dp
    public val Dialog: Dp = 32.dp
}

/**
 * iOS 风格的「连续曲率」圆角矩形（squircle）。
 *
 * 普通圆角在直边与圆弧交界处曲率突变，看起来「生硬」；这里用 Figma corner smoothing 的
 * 贝塞尔近似让曲率平滑过渡。[smoothing] = 0 时退化为普通圆角；半径超过短边一半时自动变成胶囊。
 *
 * @param radius 圆角半径。传 [Dp.Infinity] 得到胶囊。
 * @param smoothing 平滑度 0..1，iOS 约为 0.6。
 */
@Immutable
public class GlassRoundedShape(
    public val radius: Dp,
    public val smoothing: Float = 0.6f,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radiusPx = if (radius == Dp.Infinity) Float.POSITIVE_INFINITY else with(density) { radius.toPx() }
        val budget = min(size.width, size.height) / 2f
        val r = min(radiusPx, budget)
        if (r <= 0f) return Outline.Rectangle(size.toRect())
        // 实际可用平滑度为 0（胶囊或半径顶满）时直接用原生圆角矩形：裁剪与阴影走硬件快速路径
        if (effectiveSmoothing(r, smoothing, budget) <= 0.001f) {
            return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(r)))
        }
        return Outline.Generic(smoothRoundRectPath(size, r, smoothing))
    }

    override fun equals(other: Any?): Boolean =
        other is GlassRoundedShape && other.radius == radius && other.smoothing == smoothing

    override fun hashCode(): Int = 31 * radius.hashCode() + smoothing.hashCode()

    override fun toString(): String = "GlassRoundedShape(radius=$radius, smoothing=$smoothing)"
}

/** 胶囊形（两端半圆）。 */
public val GlassCapsuleShape: Shape = GlassRoundedShape(Dp.Infinity)

/** 各组件默认使用的形状，取自 [GlassRadius]。 */
@Immutable
public class GlassShapes internal constructor(
    public val tag: Shape,
    public val compact: Shape,
    public val button: Shape,
    public val card: Shape,
    public val dialog: Shape,
    public val capsule: Shape,
)

internal val DefaultGlassShapes = GlassShapes(
    tag = GlassRoundedShape(GlassRadius.Tag),
    compact = GlassRoundedShape(GlassRadius.Compact),
    button = GlassRoundedShape(GlassRadius.Button),
    card = GlassRoundedShape(GlassRadius.Card),
    dialog = GlassRoundedShape(GlassRadius.Dialog),
    capsule = GlassCapsuleShape,
)

private fun Size.toRect(): Rect = Rect(0f, 0f, width, height)

private fun effectiveSmoothing(r: Float, smoothing: Float, budget: Float): Float {
    val p = (1f + smoothing) * r
    if (p <= budget) return smoothing
    return min(smoothing, budget / r - 1f).coerceAtLeast(0f)
}

private fun toRad(deg: Float): Float = (deg * Math.PI / 180.0).toFloat()

/**
 * Figma corner smoothing 算法（与 figma-squircle 相同的几何构造）：
 * 每个角 = 贝塞尔(直边→圆弧) + 圆弧 + 贝塞尔(圆弧→直边)。
 */
internal fun smoothRoundRectPath(size: Size, radius: Float, smoothing: Float): Path {
    val w = size.width
    val h = size.height
    val budget = min(w, h) / 2f
    val r = min(radius, budget)
    val s = effectiveSmoothing(r, smoothing, budget)
    val p = min((1f + s) * r, budget)

    val arcMeasure = 90f * (1f - s)
    val arcSection = sin(toRad(arcMeasure / 2f)) * r * sqrt(2f)
    val angleAlpha = (90f - arcMeasure) / 2f
    val p3ToP4 = r * tan(toRad(angleAlpha / 2f))
    val angleBeta = 45f * s
    val c = p3ToP4 * cos(toRad(angleBeta))
    val d = c * tan(toRad(angleBeta))
    val b = (p - arcSection - c - d) / 3f
    val a = 2f * b

    val path = Path()
    // 顺时针：右上 → 右下 → 左下 → 左上。每个角用 (角点, 入射方向 e1, 出射方向 e2) 描述，
    // 局部坐标 (x, y) 映射为 角点 + x·e1 + y·e2，x ∈ [-p, 0]，y ∈ [0, p]。
    val corners = arrayOf(
        floatArrayOf(w, 0f, 1f, 0f, 0f, 1f),
        floatArrayOf(w, h, 0f, 1f, -1f, 0f),
        floatArrayOf(0f, h, -1f, 0f, 0f, -1f),
        floatArrayOf(0f, 0f, 0f, -1f, 1f, 0f),
    )
    path.moveTo(p, 0f)
    corners.forEachIndexed { index, k ->
        val cx = k[0]
        val cy = k[1]
        val e1x = k[2]
        val e1y = k[3]
        val e2x = k[4]
        val e2y = k[5]
        fun ax(x: Float, y: Float) = cx + x * e1x + y * e2x
        fun ay(x: Float, y: Float) = cy + x * e1y + y * e2y

        path.lineTo(ax(-p, 0f), ay(-p, 0f))
        // 直边 → 圆弧
        val e1lx = -p + a + b + c
        val e1ly = d
        path.cubicTo(
            ax(-p + a, 0f), ay(-p + a, 0f),
            ax(-p + a + b, 0f), ay(-p + a + b, 0f),
            ax(e1lx, e1ly), ay(e1lx, e1ly),
        )
        // 圆弧（圆心在局部 (-r, r)）
        val centerX = ax(-r, r)
        val centerY = ay(-r, r)
        val startAngle = -90f + 90f * index + angleAlpha
        if (arcMeasure > 0f) {
            path.arcTo(
                rect = Rect(centerX - r, centerY - r, centerX + r, centerY + r),
                startAngleDegrees = startAngle,
                sweepAngleDegrees = arcMeasure,
                forceMoveTo = false,
            )
        }
        // 圆弧 → 直边
        val e2lx = e1lx + arcSection
        val e2ly = e1ly + arcSection
        path.cubicTo(
            ax(e2lx + d, e2ly + c), ay(e2lx + d, e2ly + c),
            ax(e2lx + d, e2ly + b + c), ay(e2lx + d, e2ly + b + c),
            ax(0f, p), ay(0f, p),
        )
    }
    path.close()
    return path
}

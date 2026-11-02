package com.huelpocket.glassui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃层级。层级越高，模糊越强、底色越实，以保证叠在复杂内容上时文字依然可读。
 */
public enum class GlassLevel {
    /** 常规：卡片、按钮、课程格子。叠在壁纸上。 */
    Regular,

    /** 系统栏：顶栏、悬浮导航胶囊。叠在壁纸 + 内容上。 */
    Chrome,

    /** 浮层：对话框。叠在整个页面上。 */
    Overlay,
}

/**
 * 某一层级玻璃的「材质」参数。
 *
 * @property blurRadius 背景模糊半径。
 * @property tint 实时模糊模式下叠在模糊层上的半透明底色。
 * @property staticTint 静态降级模式下使用的底色（比 [tint] 更实，弥补模糊缺失带来的可读性损失）。
 * @property saturation 背景饱和度增益（1 = 不变）。iOS 材质的通透感很大程度来自这一项。
 * @property noise 噪点强度（0..1），磨砂颗粒感。
 * @property shadowColor 环境阴影颜色（只在玻璃外缘可见）。
 * @property shadowRadius 环境阴影的模糊半径。
 * @property shadowOffsetY 环境阴影的纵向偏移。
 */
@Immutable
public class GlassMaterial internal constructor(
    public val blurRadius: Dp,
    public val tint: Color,
    public val staticTint: Color,
    public val saturation: Float,
    public val noise: Float,
    public val shadowColor: Color,
    public val shadowRadius: Dp,
    public val shadowOffsetY: Dp,
)

/** 三个层级的材质集合。 */
@Immutable
public class GlassMaterials internal constructor(
    public val regular: GlassMaterial,
    public val chrome: GlassMaterial,
    public val overlay: GlassMaterial,
) {
    public operator fun get(level: GlassLevel): GlassMaterial = when (level) {
        GlassLevel.Regular -> regular
        GlassLevel.Chrome -> chrome
        GlassLevel.Overlay -> overlay
    }
}

internal val LightGlassMaterials = GlassMaterials(
    regular = GlassMaterial(
        blurRadius = 24.dp,
        tint = Color(0xFFFFFFFF).copy(alpha = 0.50f),
        staticTint = Color(0xFFFFFFFF).copy(alpha = 0.62f),
        saturation = 1.8f,
        noise = 0.04f,
        shadowColor = Color(0xFF1A2B4C).copy(alpha = 0.10f),
        shadowRadius = 16.dp,
        shadowOffsetY = 4.dp,
    ),
    chrome = GlassMaterial(
        blurRadius = 32.dp,
        tint = Color(0xFFFFFFFF).copy(alpha = 0.58f),
        staticTint = Color(0xFFFFFFFF).copy(alpha = 0.72f),
        saturation = 1.8f,
        noise = 0.04f,
        shadowColor = Color(0xFF1A2B4C).copy(alpha = 0.12f),
        shadowRadius = 24.dp,
        shadowOffsetY = 8.dp,
    ),
    overlay = GlassMaterial(
        blurRadius = 40.dp,
        tint = Color(0xFFF7F8FA).copy(alpha = 0.70f),
        staticTint = Color(0xFFF7F8FA).copy(alpha = 0.86f),
        saturation = 1.6f,
        noise = 0.03f,
        shadowColor = Color(0xFF1A2B4C).copy(alpha = 0.16f),
        shadowRadius = 40.dp,
        shadowOffsetY = 16.dp,
    ),
)

internal val DarkGlassMaterials = GlassMaterials(
    regular = GlassMaterial(
        blurRadius = 28.dp,
        tint = Color(0xFF121218).copy(alpha = 0.50f),
        staticTint = Color(0xFF121218).copy(alpha = 0.66f),
        saturation = 1.5f,
        noise = 0.05f,
        shadowColor = Color(0xFF000000).copy(alpha = 0.30f),
        shadowRadius = 16.dp,
        shadowOffsetY = 4.dp,
    ),
    chrome = GlassMaterial(
        blurRadius = 36.dp,
        tint = Color(0xFF15151C).copy(alpha = 0.60f),
        staticTint = Color(0xFF15151C).copy(alpha = 0.78f),
        saturation = 1.5f,
        noise = 0.05f,
        shadowColor = Color(0xFF000000).copy(alpha = 0.36f),
        shadowRadius = 24.dp,
        shadowOffsetY = 8.dp,
    ),
    overlay = GlassMaterial(
        blurRadius = 44.dp,
        tint = Color(0xFF1C1C24).copy(alpha = 0.72f),
        staticTint = Color(0xFF1C1C24).copy(alpha = 0.88f),
        saturation = 1.4f,
        noise = 0.04f,
        shadowColor = Color(0xFF000000).copy(alpha = 0.45f),
        shadowRadius = 40.dp,
        shadowOffsetY = 16.dp,
    ),
)

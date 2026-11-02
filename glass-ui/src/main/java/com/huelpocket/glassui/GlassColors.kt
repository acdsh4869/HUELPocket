package com.huelpocket.glassui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 玻璃 UI 的语义色板。亮 / 暗两套各自独立取值（见 docs/GlassUI设计规范.md），不是互相反色得来。
 *
 * 玻璃体本身的底色（tint）按层级放在 [GlassMaterial] 里；这里只放文字、强调色、描边与高光等。
 */
@Immutable
public class GlassColors internal constructor(
    /** 一级文字：标题、正文。 */
    public val textPrimary: Color,
    /** 二级文字：副标题、地点、说明。 */
    public val textSecondary: Color,
    /** 三级文字：时间刻度、占位、禁用。 */
    public val textTertiary: Color,
    /** 强调色：选中态、主按钮。 */
    public val accent: Color,
    /** 强调色之上的文字 / 图标。 */
    public val onAccent: Color,
    /** 危险操作（退出登录、删除）。 */
    public val danger: Color,
    /** 开关「开」状态。 */
    public val positive: Color,
    /** 受光侧高光描边（左上）。 */
    public val rimLight: Color,
    /** 背光侧暗边（右下）。 */
    public val rimShadow: Color,
    /** 玻璃体顶部的柔光（自上而下渐隐）。 */
    public val sheen: Color,
    /** 按压时整体提亮的叠层。 */
    public val pressedOverlay: Color,
    /** 选中指示器 / 分段控件滑块的填充。 */
    public val selection: Color,
    /** 选中指示器的描边（浅色背景下用于补足白底与白玻璃之间的对比）。 */
    public val selectionStroke: Color,
    /** 嵌在玻璃里的凹槽（开关轨道、分段控件底槽）。 */
    public val well: Color,
    /** 分隔线。 */
    public val separator: Color,
    /** 对话框背后的压暗层。 */
    public val scrim: Color,
    /** 模糊层的不透明底色（模糊采样越界时的兜底色）。 */
    public val backdropBase: Color,
) {
    internal fun copy(accent: Color): GlassColors = GlassColors(
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textTertiary = textTertiary,
        accent = accent,
        onAccent = onAccent,
        danger = danger,
        positive = positive,
        rimLight = rimLight,
        rimShadow = rimShadow,
        sheen = sheen,
        pressedOverlay = pressedOverlay,
        selection = selection,
        selectionStroke = selectionStroke,
        well = well,
        separator = separator,
        scrim = scrim,
        backdropBase = backdropBase,
    )
}

internal val LightGlassColors = GlassColors(
    textPrimary = Color(0xFF0D0F14),
    textSecondary = Color(0xFF3A3D47),
    textTertiary = Color(0xFF646875),
    accent = Color(0xFF007AFF),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFE5342A),
    positive = Color(0xFF34C759),
    // 亮色：白玻璃上要看得见高光，受光边几乎不透明；背光边用带蓝的深色，避免脏灰
    rimLight = Color(0xF2FFFFFF),
    rimShadow = Color(0x2E0B1220),
    sheen = Color(0x59FFFFFF),
    pressedOverlay = Color(0x40FFFFFF),
    selection = Color(0x99FFFFFF),
    // 浅色：白玻璃上白描边对比不足，改用与 rimShadow 同色系的深色发丝线（28% 蓝黑）
    selectionStroke = Color(0x470B1220),
    well = Color(0x140B1220),
    separator = Color(0x170B1220),
    scrim = Color(0x330B1220),
    backdropBase = Color(0xFFEEF0F5),
)

internal val DarkGlassColors = GlassColors(
    textPrimary = Color(0xFFF5F6FA),
    textSecondary = Color(0xFFBCC0CC),
    textTertiary = Color(0xFF8C909E),
    accent = Color(0xFF0A84FF),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFFF453A),
    positive = Color(0xFF30D158),
    // 暗色：高光降到约 45%（满强度在深色上会显得像塑料白边），背光边加深到 60% 才能在暗背景里读出来
    rimLight = Color(0x73FFFFFF),
    rimShadow = Color(0x99000000),
    sheen = Color(0x1AFFFFFF),
    pressedOverlay = Color(0x1FFFFFFF),
    selection = Color(0xB3FFFFFF),
    selectionStroke = Color(0x33FFFFFF),
    well = Color(0x1FFFFFFF),
    separator = Color(0x1AFFFFFF),
    scrim = Color(0x73000000),
    backdropBase = Color(0xFF0A0A0F),
)

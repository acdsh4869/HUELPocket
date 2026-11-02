package com.huelpocket.glassui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 字体层级：标题 / 正文 / 辅助 三级，外加只给顶栏用的大标题。
 * 行高均落在 4dp 网格上；颜色不写死，由所在玻璃面通过 LocalContentColor 提供。
 */
@Immutable
public class GlassTypography internal constructor(
    /** 页面大标题（仅 GlassTopBar）：28sp / Bold。 */
    public val largeTitle: TextStyle,
    /** 一级 · 标题：卡片标题、对话框标题、课程名。17sp / SemiBold。 */
    public val title: TextStyle,
    /** 二级 · 正文：说明文字、列表项。15sp / Regular。 */
    public val body: TextStyle,
    /** 三级 · 辅助：时间、地点、标签、导航文字。12sp / Medium。 */
    public val caption: TextStyle,
)

internal val DefaultGlassTypography = GlassTypography(
    largeTitle = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    title = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    body = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
)

/**
 * 8pt 间距网格。所有外边距、内边距、间隔都应取自这里；[Half] 只用于图标与文字之间等极小间隙。
 */
public object GlassSpacing {
    public val Half: Dp = 4.dp
    public val X1: Dp = 8.dp
    public val X2: Dp = 16.dp
    public val X3: Dp = 24.dp
    public val X4: Dp = 32.dp
    public val X5: Dp = 40.dp
    public val X6: Dp = 48.dp
}

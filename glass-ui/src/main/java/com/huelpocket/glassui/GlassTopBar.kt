package com.huelpocket.glassui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.huelpocket.glassui.internal.LocalBackdropLayer
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/** 顶栏样式。 */
public enum class GlassTopBarStyle {
    /**
     * 滚动边缘（iOS 26 导航栏）：整条栏延伸到状态栏下，模糊强度自上而下渐弱，没有硬边界；
     * 操作按钮是独立的圆形玻璃。内容滚上来时柔和地「沉」进栏里。
     */
    ScrollEdge,

    /** 悬浮面板：与导航胶囊同一语言的独立玻璃卡（24dp 圆角，带方向光描边）。 */
    Floating,
}

/**
 * 玻璃顶栏。自带状态栏避让，直接放进 [GlassScaffold] 的 topBar。
 *
 * @param title 大标题。
 * @param subtitle 标题下方的辅助信息（如「第 9 周 · 10月26日 周一」）。
 * @param navigationIcon 左侧按钮，建议用 [GlassIconButton]。
 * @param actions 右侧按钮组，建议用 [GlassIconButton]，间距已处理。
 */
@Composable
public fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    style: GlassTopBarStyle = GlassTopBarStyle.ScrollEdge,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    when (style) {
        GlassTopBarStyle.ScrollEdge -> ScrollEdgeTopBar(modifier, title, subtitle, navigationIcon, actions)
        GlassTopBarStyle.Floating -> FloatingTopBar(modifier, title, subtitle, navigationIcon, actions)
    }
}

@Composable
private fun ScrollEdgeTopBar(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().scrollEdgeBackdrop())
        TopBarContent(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = GlassSpacing.X2, end = GlassSpacing.X2, top = GlassSpacing.X1, bottom = GlassSpacing.X3),
            title, subtitle, navigationIcon, actions,
        )
    }
}

@Composable
private fun FloatingTopBar(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = GlassSpacing.X2, vertical = GlassSpacing.X1),
    ) {
        GlassContainer(
            modifier = Modifier.fillMaxWidth(),
            shape = GlassTheme.shapes.card,
            level = GlassLevel.Chrome,
        ) {
            TopBarContent(
                Modifier.padding(start = GlassSpacing.X2, end = GlassSpacing.X1, top = GlassSpacing.X1, bottom = GlassSpacing.X1),
                title, subtitle, navigationIcon, actions,
            )
        }
    }
}

@Composable
private fun TopBarContent(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val typography = GlassTheme.typography
    CompositionLocalProvider(LocalContentColor provides colors.textPrimary) {
        Row(
            modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                navigationIcon()
                Spacer(Modifier.width(GlassSpacing.X1))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = typography.largeTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = typography.caption, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GlassSpacing.X1), verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}

/** 滚动边缘背景：渐弱模糊 + 渐隐底色。 */
@Composable
private fun Modifier.scrollEdgeBackdrop(): Modifier {
    val material = GlassTheme.materials.chrome
    val colors = GlassTheme.colors
    val backdrop = LocalBackdropLayer.current
    val live = GlassTheme.blurMode == GlassBlurMode.Live && backdrop != null
    val tint = if (live) material.tint else material.staticTint
    val tintBrush = remember(tint) {
        Brush.verticalGradient(0f to tint, 0.55f to tint.copy(alpha = tint.alpha * 0.85f), 1f to tint.copy(alpha = 0f))
    }
    if (!live) {
        return drawBehind { drawRect(tintBrush) }
    }
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
            drawRect(tintBrush)
        }
        .hazeChild(backdrop!!.hazeState, style) {
            blurEnabled = true
            progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f, preferPerformance = true)
        }
}

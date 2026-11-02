package com.huelpocket.glassui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底部导航的一项。
 *
 * @param label 文字（建议 2~4 个汉字）。
 * @param icon 未选中图标。
 * @param selectedIcon 选中图标，默认同 [icon]。
 */
@Immutable
public class GlassNavItem(
    public val label: String,
    public val icon: ImageVector,
    public val selectedIcon: ImageVector = icon,
)

/** [GlassNavigationBar] 的尺寸参数。 */
public object GlassNavigationBarDefaults {
    /** 胶囊高度 64dp。 */
    public val Height: Dp = 64.dp

    /** 选中指示器与胶囊边缘的间距 6dp（同心圆角）。 */
    public val IndicatorInset: Dp = 6.dp

    /** 胶囊与屏幕左右边缘的距离。 */
    public val HorizontalMargin: Dp = GlassSpacing.X3

    /** 胶囊与系统手势条之间的距离。 */
    public val BottomMargin: Dp = GlassSpacing.X1
}

/**
 * 悬浮胶囊式底部导航（iOS 26 Tab Bar 风格）。
 *
 * 注意：当前 app 实际使用的是 AppNav 内嵌的 56dp 圆形指示器 dock，本组件暂未在 app 中调用；
 * 它的 64dp 高度、带文字标签和液滴动画与线上 dock 不同，调整选中态时不要以本组件代替实测。
 *
 * 自带底部系统导航栏避让，直接放进 [GlassScaffold] 的 bottomBar 即可。选中指示器在项之间滑动时
 * 前缘快、后缘慢，呈现「液滴拉伸」的效果。
 *
 * @param items 2~5 项。
 * @param selectedIndex 当前选中下标。
 * @param onItemSelected 点击回调。
 */
@Composable
public fun GlassNavigationBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(items.isNotEmpty()) { "GlassNavigationBar 至少需要一项" }
    val colors = GlassTheme.colors
    val count = items.size
    // 液滴拉伸：朝运动方向的边用更硬的弹簧先到，另一边随后跟上
    val previous = remember { intArrayOf(selectedIndex) }
    val movingRight = selectedIndex >= previous[0]
    SideEffect { previous[0] = selectedIndex }
    val fast = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium)
    val slow = spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow)
    val left by animateFloatAsState(selectedIndex.toFloat(), if (movingRight) slow else fast, label = "navLeft")
    val right by animateFloatAsState(selectedIndex + 1f, if (movingRight) fast else slow, label = "navRight")

    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                start = GlassNavigationBarDefaults.HorizontalMargin,
                end = GlassNavigationBarDefaults.HorizontalMargin,
                bottom = GlassNavigationBarDefaults.BottomMargin,
            ),
        contentAlignment = Alignment.Center,
    ) {
        GlassContainer(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .height(GlassNavigationBarDefaults.Height),
            shape = GlassTheme.shapes.capsule,
            level = GlassLevel.Chrome,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(GlassNavigationBarDefaults.IndicatorInset)
                    .drawBehind {
                        val slot = size.width / count
                        val x0 = left * slot
                        val x1 = right * slot
                        val r = CornerRadius(size.height / 2f)
                        drawRoundRect(colors.selection, Offset(x0, 0f), Size(x1 - x0, size.height), r)
                        drawRoundRect(
                            color = colors.rimLight.copy(alpha = colors.rimLight.alpha * 0.5f),
                            topLeft = Offset(x0, 0f),
                            size = Size(x1 - x0, size.height),
                            cornerRadius = r,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    },
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    NavBarItem(
                        item = item,
                        selected = index == selectedIndex,
                        onClick = { onItemSelected(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavBarItem(
    item: GlassNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = GlassTheme.colors
    val tint by animateColorAsState(if (selected) colors.accent else colors.textSecondary, label = "navTint")
    Column(
        modifier
            .fillMaxHeight()
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (selected) item.selectedIcon else item.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.label,
            style = GlassTheme.typography.caption,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

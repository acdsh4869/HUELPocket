package com.huelpocket.glassui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import com.huelpocket.glassui.internal.rememberOverlayHost
import com.huelpocket.glassui.internal.LocalBackdropLayer
import com.huelpocket.glassui.internal.LocalGlassOverlayHost
import com.huelpocket.glassui.internal.backdropSource
import com.huelpocket.glassui.internal.rememberBackdropLayer
import kotlin.math.max

/**
 * 玻璃页面骨架：壁纸 + 内容 + 悬浮顶栏 / 底部导航 + 对话框浮层。
 *
 * 采样关系：
 * - [content] 里的玻璃（卡片、按钮）模糊 [background]；
 * - [topBar] / [bottomBar] 模糊 [background] + [content]（内容滚到栏下面时能透出来）；
 * - [GlassDialog] 模糊整个页面。
 *
 * 页面为全屏（edge-to-edge）布局：请在 Activity 中调用 `enableEdgeToEdge()`。
 *
 * @param background 壁纸层，例如渐变 / 图片。
 * @param topBar 顶栏，通常是 [GlassTopBar]。
 * @param bottomBar 底部栏，通常是 [GlassNavigationBar]。
 * @param content 内容。参数为需要避让的内边距（顶栏 / 底栏 / 系统栏高度），列表请设为 contentPadding 以便内容能滚到栏下面。
 */
@Composable
public fun GlassScaffold(
    background: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val wallpaper = rememberBackdropLayer(parent = null)
    val page = rememberBackdropLayer(parent = wallpaper)
    val screen = rememberBackdropLayer(parent = page)
    val overlayHost = rememberOverlayHost()
    val statusBars = WindowInsets.statusBars
    val navigationBars = WindowInsets.navigationBars

    CompositionLocalProvider(LocalGlassOverlayHost provides overlayHost) {
        Box(modifier.fillMaxSize()) {
            SubcomposeLayout(Modifier.fillMaxSize().backdropSource(screen, captureStatic = false)) { constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val topPlaceables = subcompose(ScaffoldSlot.Top) {
                    CompositionLocalProvider(LocalBackdropLayer provides page, content = topBar)
                }.map { it.measure(loose) }
                val bottomPlaceables = subcompose(ScaffoldSlot.Bottom) {
                    CompositionLocalProvider(LocalBackdropLayer provides page, content = bottomBar)
                }.map { it.measure(loose) }
                val topHeight = max(topPlaceables.maxOfOrNull { it.height } ?: 0, statusBars.getTop(this))
                val bottomHeight = max(bottomPlaceables.maxOfOrNull { it.height } ?: 0, navigationBars.getBottom(this))
                val padding = PaddingValues(top = topHeight.toDp(), bottom = bottomHeight.toDp())

                val bodyPlaceables = subcompose(ScaffoldSlot.Body) {
                    Box(Modifier.fillMaxSize().backdropSource(page, captureStatic = false)) {
                        Box(Modifier.matchParentSize().backdropSource(wallpaper, captureStatic = true), content = background)
                        CompositionLocalProvider(LocalBackdropLayer provides wallpaper) {
                            content(padding)
                        }
                    }
                }.map { it.measure(constraints) }

                layout(constraints.maxWidth, constraints.maxHeight) {
                    bodyPlaceables.forEach { it.place(0, 0) }
                    topPlaceables.forEach { it.place(0, 0) }
                    bottomPlaceables.forEach { it.place(0, constraints.maxHeight - it.height) }
                }
            }
            CompositionLocalProvider(LocalBackdropLayer provides screen) {
                overlayHost.Render()
            }
        }
    }
}

/**
 * 轻量宿主：只有「壁纸 + 内容」两层，适合不需要顶栏 / 导航的页面或局部区域（例如主 App 某个卡片区）。
 * [content] 中的玻璃会模糊 [background]；[GlassDialog] 会模糊整个区域。
 */
@Composable
public fun GlassBackdrop(
    background: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val wallpaper = rememberBackdropLayer(parent = null)
    val screen = rememberBackdropLayer(parent = wallpaper)
    val overlayHost = rememberOverlayHost()
    CompositionLocalProvider(LocalGlassOverlayHost provides overlayHost) {
        Box(modifier, propagateMinConstraints = true) {
            Box(Modifier.backdropSource(screen, captureStatic = false), propagateMinConstraints = true) {
                Box(Modifier.matchParentSize().backdropSource(wallpaper, captureStatic = true), content = background)
                CompositionLocalProvider(LocalBackdropLayer provides wallpaper) {
                    Box(content = content)
                }
            }
            CompositionLocalProvider(LocalBackdropLayer provides screen) {
                overlayHost.Render()
            }
        }
    }
}

private enum class ScaffoldSlot { Top, Bottom, Body }

package com.huelpocket.glassui.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.huelpocket.glassui.GlassBlurMode
import com.huelpocket.glassui.GlassTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一层「可被玻璃采样」的背景。
 *
 * 实时模式下就是一个 haze 源；静态模式下记录自身在窗口中的位置，并（仅根层）持有一张降采样 + 模糊后的快照。
 * 上层（导航栏 / 对话框）在静态模式下统一回落到根层快照：[staticRoot]。
 */
@Stable
internal class BackdropLayer(parent: BackdropLayer?) {
    val hazeState = HazeState()
    val staticRoot: BackdropLayer = parent?.staticRoot ?: this

    var windowPosition by mutableStateOf(Offset.Unspecified)
    var size by mutableStateOf(IntSize.Zero)

    /** 静态模糊快照（已降采样），只在 [staticRoot] 上有值。 */
    var staticSnapshot by mutableStateOf<ImageBitmap?>(null)
}

/** 当前内容所在的可采样背景；为 null 表示没有宿主（GlassScaffold / GlassBackdrop），玻璃只画半透明底色。 */
internal val LocalBackdropLayer = staticCompositionLocalOf<BackdropLayer?> { null }

/**
 * 把节点标记为一层背景源。
 *
 * @param captureStatic 静态模式下是否截图（只有最底层的壁纸需要）。
 */
@Composable
internal fun Modifier.backdropSource(layer: BackdropLayer, captureStatic: Boolean): Modifier {
    val mode = GlassTheme.blurMode
    val tracked = this.onGloballyPositioned {
        layer.windowPosition = it.positionInWindow()
        layer.size = it.size
    }
    if (mode == GlassBlurMode.Live) {
        return tracked.haze(layer.hazeState)
    }
    if (!captureStatic) return tracked

    val graphicsLayer = rememberGraphicsLayer()
    val material = GlassTheme.materials.regular
    val dark = GlassTheme.isDark
    val blurPx = with(LocalDensity.current) { material.blurRadius.toPx() }
    val size = layer.size
    LaunchedEffect(layer, size, dark, blurPx, material.saturation) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        // 等内容真正画出来（两帧）再截图
        withFrameNanos { }
        withFrameNanos { }
        val snapshot = graphicsLayer.toImageBitmap()
        layer.staticSnapshot = withContext(Dispatchers.Default) {
            StaticBlur.process(snapshot, blurPx, material.saturation)
        }
    }
    return tracked.drawWithContent {
        graphicsLayer.record { this@drawWithContent.drawContent() }
        drawLayer(graphicsLayer)
    }
}

@Composable
internal fun rememberBackdropLayer(parent: BackdropLayer?): BackdropLayer = remember(parent) { BackdropLayer(parent) }

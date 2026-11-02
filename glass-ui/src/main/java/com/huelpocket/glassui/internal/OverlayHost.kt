package com.huelpocket.glassui.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 同窗口浮层宿主：把 GlassDialog 渲染在宿主最上层，而不是另开一个 Dialog 窗口。
 * 这样对话框玻璃才能模糊到它背后的整个页面（跨窗口无法采样）。
 *
 * 宿主由 GlassTheme 持有，GlassScaffold / GlassBackdrop 注册为「渲染者」（最后挂上的生效）。
 * 因此 GlassDialog 写在 GlassTheme 之下任何位置都可以，不必非得写在 GlassScaffold 的 content 里。
 */
@Stable
internal class GlassOverlayHost {
    private val entries = mutableStateListOf<OverlayEntry>()
    private val renderers = mutableStateListOf<Any>()

    /** 是否有 GlassScaffold / GlassBackdrop 能渲染浮层；没有时 GlassDialog 退化为普通 Dialog 窗口。 */
    val hasRenderer: Boolean get() = renderers.isNotEmpty()

    fun add(entry: OverlayEntry) {
        entries.add(entry)
    }

    fun remove(entry: OverlayEntry) {
        entries.remove(entry)
    }

    /** 由宿主调用：注册为渲染者，且只有最后注册的那个真正绘制，避免嵌套宿主重复渲染。 */
    @Composable
    fun Render() {
        val token = remember { Any() }
        DisposableEffect(this, token) {
            renderers.add(token)
            onDispose { renderers.remove(token) }
        }
        if (renderers.lastOrNull() !== token) return
        entries.forEach { entry ->
            key(entry) { entry.content() }
        }
    }
}

internal class OverlayEntry(val content: @Composable () -> Unit)

internal val LocalGlassOverlayHost = staticCompositionLocalOf<GlassOverlayHost?> { null }

/** 取 GlassTheme 提供的宿主；没有 GlassTheme 时就地创建一个。 */
@Composable
internal fun rememberOverlayHost(): GlassOverlayHost =
    LocalGlassOverlayHost.current ?: remember { GlassOverlayHost() }

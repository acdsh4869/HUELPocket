package com.huelpocket.glassui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalContext
import com.huelpocket.glassui.internal.GlassOverlayHost
import com.huelpocket.glassui.internal.LocalGlassOverlayHost

/** 明暗模式。 */
public enum class GlassThemeMode {
    /** 跟随系统。 */
    System,

    /** 锁定浅色。 */
    Light,

    /** 锁定深色。 */
    Dark,
}

/** 背景模糊策略（降级开关）。 */
public enum class GlassBlurMode {
    /**
     * 自动：Android 12L（API 32）及以上、且不是低内存设备时用实时模糊，否则用静态降级。
     * Android 12（API 31）上 RenderEffect 与 GraphicsLayer 组合存在已知问题，haze 官方在该版本默认关闭模糊。
     */
    Auto,

    /** 强制实时模糊（haze / RenderEffect）。玻璃随内容滚动实时采样。 */
    Live,

    /**
     * 强制静态降级：背景只截图并做一次 CPU 模糊，玻璃面按自身位置贴这张静态模糊图，再盖一层更实的半透明底色。
     * 几乎不占 GPU，适合低端机 / 省电模式。
     */
    Static,
}

/**
 * 液态玻璃主题。为所有 Glass* 组件提供设计 token。
 *
 * 不会修改 MaterialTheme，可以放在主 App 已有的 MaterialTheme 内外任意位置。
 *
 * @param themeMode 跟随系统，或手动锁定浅色 / 深色。
 * @param blurMode 背景模糊策略，见 [GlassBlurMode]。
 * @param accentColor 覆盖强调色；默认使用 iOS 系统蓝（浅色 #007AFF / 深色 #0A84FF）。
 */
@Composable
public fun GlassTheme(
    themeMode: GlassThemeMode = GlassThemeMode.System,
    blurMode: GlassBlurMode = GlassBlurMode.Auto,
    accentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        GlassThemeMode.System -> isSystemInDarkTheme()
        GlassThemeMode.Light -> false
        GlassThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val resolvedBlur = remember(blurMode, context) { resolveBlurMode(blurMode, context) }
    val colors = remember(dark, accentColor) {
        val base = if (dark) DarkGlassColors else LightGlassColors
        if (accentColor.isSpecified) base.copy(accent = accentColor) else base
    }
    val overlayHost = LocalGlassOverlayHost.current ?: remember { GlassOverlayHost() }
    CompositionLocalProvider(
        LocalGlassOverlayHost provides overlayHost,
        LocalGlassColors provides colors,
        LocalGlassMaterials provides if (dark) DarkGlassMaterials else LightGlassMaterials,
        LocalGlassIsDark provides dark,
        LocalGlassBlurMode provides resolvedBlur,
        content = content,
    )
}

/** 在 Composable 中读取当前玻璃主题 token。 */
public object GlassTheme {
    public val colors: GlassColors
        @Composable @ReadOnlyComposable get() = LocalGlassColors.current

    public val materials: GlassMaterials
        @Composable @ReadOnlyComposable get() = LocalGlassMaterials.current

    public val shapes: GlassShapes
        @Composable @ReadOnlyComposable get() = DefaultGlassShapes

    public val typography: GlassTypography
        @Composable @ReadOnlyComposable get() = DefaultGlassTypography

    /** 当前是否为深色。 */
    public val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalGlassIsDark.current

    /** 实际生效的模糊策略（[GlassBlurMode.Auto] 已解析为 Live 或 Static）。 */
    public val blurMode: GlassBlurMode
        @Composable @ReadOnlyComposable get() = LocalGlassBlurMode.current
}

internal val LocalGlassColors = staticCompositionLocalOf { LightGlassColors }
internal val LocalGlassMaterials = staticCompositionLocalOf { LightGlassMaterials }
internal val LocalGlassIsDark = staticCompositionLocalOf { false }
internal val LocalGlassBlurMode = staticCompositionLocalOf { GlassBlurMode.Static }

internal fun resolveBlurMode(mode: GlassBlurMode, context: Context): GlassBlurMode = when (mode) {
    GlassBlurMode.Live, GlassBlurMode.Static -> mode
    GlassBlurMode.Auto -> if (Build.VERSION.SDK_INT >= 32 && !isLowEndDevice(context)) {
        GlassBlurMode.Live
    } else {
        GlassBlurMode.Static
    }
}

private fun isLowEndDevice(context: Context): Boolean {
    val am = context.getSystemService(ActivityManager::class.java) ?: return false
    if (am.isLowRamDevice) return true
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return info.totalMem in 1 until LOW_END_TOTAL_MEMORY
}

private const val LOW_END_TOTAL_MEMORY = 3L * 1024 * 1024 * 1024

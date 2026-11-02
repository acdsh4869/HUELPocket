package com.huelpocket.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.huelpocket.app.data.prefs.DarkMode
import com.huelpocket.app.data.prefs.ThemePreset
import com.huelpocket.glassui.GlassTheme
import com.huelpocket.glassui.GlassThemeMode

/**
 * 全应用主题。
 *
 * [ThemePreset.GLASS] 是默认的「灰玻璃」黑白主题：亮色白底、暗色黑底，
 * 强调色只保留一个中性色；其余彩色预设仅作为「外观」里的可选项。
 *
 * 深浅色由 [darkMode] 决定（跟随系统 / 强制浅 / 强制深）。
 */
@Composable
fun HUELPocketTheme(
    darkMode: DarkMode = DarkMode.SYSTEM,
    preset: ThemePreset = ThemePreset.GLASS,
    content: @Composable () -> Unit,
) {
    val dark = when (darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val colorScheme = rememberAppColorScheme(preset = preset, dark = dark)

    MaterialTheme(
        colorScheme = colorScheme,
        content = {
            GlassTheme(
                themeMode = when (darkMode) {
                    DarkMode.SYSTEM -> GlassThemeMode.System
                    DarkMode.LIGHT -> GlassThemeMode.Light
                    DarkMode.DARK -> GlassThemeMode.Dark
                },
                accentColor = if (preset == ThemePreset.GLASS) {
                    if (dark) Color(0xFFEAEAEA) else Color(0xFF1A1A1A)
                } else {
                    Color.Unspecified
                },
            ) {
                content()
            }
        },
    )
}

/**
 * 按预设算出配色。
 *
 * 单独抽出来是给「外观」页做实时预览用的 —— 预览某套配色时，当前主题还不是它。
 */
@Composable
fun rememberAppColorScheme(
    preset: ThemePreset,
    dark: Boolean,
    variant: SchemeVariant = SchemeVariant.TONAL_SPOT,
): ColorScheme {
    val context = LocalContext.current
    return remember(preset, dark, variant) {
        when (preset) {
            ThemePreset.GLASS -> neutralGlassColorScheme(dark)
            ThemePreset.DYNAMIC ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            else -> {
                val seed = preset.seedArgb
                    ?: if (dark) 0xFFEAEAEA.toInt() else 0xFF1A1A1A.toInt()
                MaterialYouScheme.fromSeed(seedArgb = seed, dark = dark, variant = variant)
            }
        }
    }
}

/** 灰玻璃默认主题：无色相，只用黑 / 白 / 灰。 */
private fun neutralGlassColorScheme(dark: Boolean): ColorScheme {
    val primary = if (dark) Color(0xFFEAEAEA) else Color(0xFF1A1A1A)
    val onPrimary = if (dark) Color(0xFF101013) else Color(0xFFFFFFFF)
    val container = if (dark) Color(0xFF1C1C21) else Color(0xFFF2F3F5)
    val onContainer = if (dark) Color(0xFFEAEAEA) else Color(0xFF1A1A1A)
    val background = if (dark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val onBackground = if (dark) Color(0xFFF5F6FA) else Color(0xFF0D0F14)
    val surfaceVariant = if (dark) Color(0xFF15151C) else Color(0xFFF2F3F5)
    val onSurfaceVariant = if (dark) Color(0xFFBCC0CC) else Color(0xFF3A3D47)
    val outline = if (dark) Color(0xFF8C909E) else Color(0xFF646875)
    val outlineVariant = if (dark) Color(0xFF2A2A31) else Color(0xFFE2E3E7)
    val containerLow = if (dark) Color(0xFF0F0F14) else Color(0xFFFBFCFD)
    val containerMid = if (dark) Color(0xFF15151C) else Color(0xFFF7F8FA)
    val containerHigh = if (dark) Color(0xFF1C1C21) else Color(0xFFF1F2F4)

    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = Color(0xFFBCC0CC),
            onSecondary = Color(0xFF101013),
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            tertiary = Color(0xFF8C909E),
            onTertiary = Color(0xFF101013),
            tertiaryContainer = container,
            onTertiaryContainer = onContainer,
            background = background,
            onBackground = onBackground,
            surface = background,
            onSurface = onBackground,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            surfaceContainerLowest = Color(0xFF0A0A0F),
            surfaceContainerLow = containerLow,
            surfaceContainer = containerMid,
            surfaceContainerHigh = containerHigh,
            surfaceContainerHighest = Color(0xFF24242B),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = Color(0xFF3A3D47),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            tertiary = Color(0xFF646875),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = container,
            onTertiaryContainer = onContainer,
            background = background,
            onBackground = onBackground,
            surface = background,
            onSurface = onBackground,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = containerLow,
            surfaceContainer = containerMid,
            surfaceContainerHigh = containerHigh,
            surfaceContainerHighest = Color(0xFFEAECEF),
        )
    }
}

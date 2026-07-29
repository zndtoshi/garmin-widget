package com.zndtoshi.garminwidget.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

/**
 * Centralized configuration-app palette aligned with the premium widget look.
 * Forced dark for the config activity regardless of system light/dark.
 */
object AppColors {
    val Background = Color(0xFF0B121A)
    val Surface = Color(0xFF121C28)
    val SurfaceElevated = Color(0xFF1A2736)
    val OnBackground = Color(0xFFE8EEF2)
    val OnSurface = Color(0xFFE8EEF2)
    val OnSurfaceMuted = Color(0xFFB0BEC5)
    val Cyan = Color(0xFF5BD8E6)
    val Purple = Color(0xFFB158D8)
    val Outline = Color(0xFF3A4F63)
    val OutlineFocused = Color(0xFF5BD8E6)
    val Error = Color(0xFFFF8A80)
    val OnPrimary = Color(0xFF041018)
    val DisabledContainer = Color(0xFF243040)
    val DisabledContent = Color(0xFF7A8B99)
}

/** Configuration app always uses the dark theme, independent of system setting. */
fun isForcedDarkAppTheme(): Boolean = true

fun appWindowBackgroundArgb(): Int = AppColors.Background.toArgb()

fun appStatusBarArgb(): Int = AppColors.Background.toArgb()

fun appNavigationBarArgb(): Int = AppColors.Background.toArgb()

/** Light (pale) system-bar icons on a dark bar. */
fun statusBarUsesLightIcons(): Boolean = true

fun navigationBarUsesLightIcons(): Boolean = true

internal fun relativeLuminance(color: Color): Float {
    fun channel(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    }
    val r = channel(color.red)
    val g = channel(color.green)
    val b = channel(color.blue)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/** WCAG contrast ratio between two opaque colors. */
internal fun contrastRatio(foreground: Color, background: Color): Float {
    val l1 = relativeLuminance(foreground)
    val l2 = relativeLuminance(background)
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05f) / (darker + 0.05f)
}

internal fun hasReadableContrast(foreground: Color, background: Color, minimum: Float = 4.5f): Boolean =
    contrastRatio(foreground, background) >= minimum

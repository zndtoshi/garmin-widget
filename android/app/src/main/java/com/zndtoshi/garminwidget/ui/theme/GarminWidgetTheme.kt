package com.zndtoshi.garminwidget.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Cyan,
    onPrimary = AppColors.OnPrimary,
    primaryContainer = AppColors.SurfaceElevated,
    onPrimaryContainer = AppColors.OnSurface,
    secondary = AppColors.Purple,
    onSecondary = AppColors.OnBackground,
    secondaryContainer = AppColors.SurfaceElevated,
    onSecondaryContainer = AppColors.OnSurface,
    tertiary = AppColors.Cyan,
    onTertiary = AppColors.OnPrimary,
    background = AppColors.Background,
    onBackground = AppColors.OnBackground,
    surface = AppColors.Surface,
    onSurface = AppColors.OnSurface,
    surfaceVariant = AppColors.SurfaceElevated,
    onSurfaceVariant = AppColors.OnSurfaceMuted,
    outline = AppColors.Outline,
    outlineVariant = AppColors.Outline,
    error = AppColors.Error,
    onError = AppColors.OnPrimary,
    inverseSurface = AppColors.OnSurface,
    inverseOnSurface = AppColors.Background,
    inversePrimary = AppColors.Cyan,
    scrim = AppColors.Background.copy(alpha = 0.72f),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Forced-dark Material theme for the configuration activity.
 * Ignores system light/dark so the app stays charcoal even when the phone is in light mode.
 */
@Composable
fun GarminWidgetTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Always dark for the config app, regardless of [darkTheme] / system setting.
    MaterialTheme(
        colorScheme = DarkColorScheme,
        shapes = AppShapes,
        content = content,
    )
}

fun appColorSchemeIsDark(): Boolean = true

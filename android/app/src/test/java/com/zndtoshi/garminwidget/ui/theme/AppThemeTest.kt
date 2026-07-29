package com.zndtoshi.garminwidget.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    @Test
    fun `configuration app forces dark theme helpers`() {
        assertTrue(isForcedDarkAppTheme())
        assertTrue(appColorSchemeIsDark())
        assertTrue(statusBarUsesLightIcons())
        assertTrue(navigationBarUsesLightIcons())
        assertEquals(AppColors.Background.toArgb(), appWindowBackgroundArgb())
        assertEquals(appWindowBackgroundArgb(), appStatusBarArgb())
        assertEquals(appWindowBackgroundArgb(), appNavigationBarArgb())
    }

    @Test
    fun `dark palette keeps readable text contrast`() {
        assertTrue(hasReadableContrast(AppColors.OnBackground, AppColors.Background))
        assertTrue(hasReadableContrast(AppColors.OnSurface, AppColors.Surface))
        assertTrue(hasReadableContrast(AppColors.OnSurfaceMuted, AppColors.Background, minimum = 3.0f))
        assertTrue(hasReadableContrast(AppColors.Cyan, AppColors.Background, minimum = 3.0f))
        assertTrue(hasReadableContrast(AppColors.OnPrimary, AppColors.Cyan))
        assertTrue(relativeLuminance(AppColors.Background) < 0.08f)
        assertTrue(relativeLuminance(AppColors.OnBackground) > 0.7f)
        assertTrue(relativeLuminance(AppColors.SurfaceElevated) > relativeLuminance(AppColors.Background))
        assertTrue(relativeLuminance(AppColors.Surface) > relativeLuminance(AppColors.Background))
    }

    @Test
    fun `accent colors match premium widget cues`() {
        assertEquals(0xFF5BD8E6.toInt(), AppColors.Cyan.toArgb())
        assertEquals(0xFFB158D8.toInt(), AppColors.Purple.toArgb())
        assertEquals(0xFF0B121A.toInt(), AppColors.Background.toArgb())
    }
}

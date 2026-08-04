package com.zndtoshi.garminwidget.widget

import android.graphics.Bitmap
import com.zndtoshi.garminwidget.data.ActivityHeartRatePoint
import com.zndtoshi.garminwidget.data.ActivityHrColorMode
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityHrChartModeTest {
    private val zoneSpanningTimeline = listOf(
        ActivityHeartRatePoint(0, 110), // grey (~55%)
        ActivityHeartRatePoint(30, 130), // grey/blue blend (~65%)
        ActivityHeartRatePoint(60, 150), // blue/green (~75%)
        ActivityHeartRatePoint(90, 170), // green/orange (~85%)
        ActivityHeartRatePoint(120, 190), // orange/red (~95%)
        ActivityHeartRatePoint(150, 200), // red (100%)
    )
    private val maxHr = 200

    private fun chartPixelSignature(mode: ActivityHrColorMode): Long {
        val bitmap = drawActivityHrChartBitmap(
            160,
            64,
            zoneSpanningTimeline,
            maxHr,
            hrColorMode = mode,
        ) ?: return 0L
        val stream = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        return stream.toByteArray().fold(mode.storageValue.hashCode().toLong()) { hash, byte ->
            hash * 31 + (byte.toInt() and 0xff)
        }
    }

    private fun Bitmap.containsNearColor(targetArgb: Int, maxDistance: Float = 48f): Boolean {
        val targetRed = (targetArgb ushr 16) and 0xff
        val targetGreen = (targetArgb ushr 8) and 0xff
        val targetBlue = targetArgb and 0xff
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.any { pixel ->
            if ((pixel ushr 24) and 0xff < 40) return@any false
            val redDelta = ((pixel ushr 16) and 0xff) - targetRed
            val greenDelta = ((pixel ushr 8) and 0xff) - targetGreen
            val blueDelta = (pixel and 0xff) - targetBlue
            sqrt((redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta).toFloat()) <= maxDistance
        }
    }

    @Test
    fun color_modes_produce_different_non_empty_charts() {
        val whiteSig = chartPixelSignature(ActivityHrColorMode.WHITE_RED_PEAKS)
        val garminSig = chartPixelSignature(ActivityHrColorMode.GARMIN_ZONES)
        assertNotEquals(0L, whiteSig)
        assertNotEquals(0L, garminSig)
        assertNotEquals(whiteSig, garminSig)

        val whiteBmp = drawActivityHrChartBitmap(
            160,
            64,
            zoneSpanningTimeline,
            maxHr,
            hrColorMode = ActivityHrColorMode.WHITE_RED_PEAKS,
        )
        val garminBmp = drawActivityHrChartBitmap(
            160,
            64,
            zoneSpanningTimeline,
            maxHr,
            hrColorMode = ActivityHrColorMode.GARMIN_ZONES,
        )
        assertNotNull(whiteBmp)
        assertNotNull(garminBmp)
        assertTrue(whiteBmp!!.width > 0 && whiteBmp.height > 0)
        assertTrue(garminBmp!!.width > 0 && garminBmp.height > 0)
    }

    @Test
    fun garmin_mode_chart_includes_multiple_zone_colors() {
        val bmp = drawActivityHrChartBitmap(
            200,
            80,
            zoneSpanningTimeline,
            maxHr,
            hrColorMode = ActivityHrColorMode.GARMIN_ZONES,
        )
        assertNotNull(bmp)
        assertTrue(bmp!!.containsNearColor(GARMIN_HR_ZONE_GREY))
        assertTrue(bmp.containsNearColor(GARMIN_HR_ZONE_BLUE))
        assertTrue(bmp.containsNearColor(GARMIN_HR_ZONE_GREEN))
        assertTrue(bmp.containsNearColor(GARMIN_HR_ZONE_ORANGE))
        assertTrue(bmp.containsNearColor(GARMIN_HR_ZONE_RED))
    }
}

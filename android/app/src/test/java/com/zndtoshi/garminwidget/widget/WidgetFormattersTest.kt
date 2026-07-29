package com.zndtoshi.garminwidget.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zndtoshi.garminwidget.data.HrvTrendPoint
import com.zndtoshi.garminwidget.data.LastActivity
import com.zndtoshi.garminwidget.data.LocalStatus
import com.zndtoshi.garminwidget.data.TimelinePoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFormattersTest {
    @Test
    fun `equal top panels share width and centers`() {
        listOf(164f, 284f, 334f).forEach { contentWidth ->
            val geo = equalTopPanelGeometry(contentWidth)
            assertEquals(3, geo.centersDp.size)
            assertEquals(contentWidth / 3f, geo.panelWidthDp, 0.001f)
            assertTrue(geo.equalWidths)
            val gaps = geo.centersDp.zipWithNext { a, b -> b - a }
            assertEquals(geo.panelWidthDp, gaps.first(), 0.01f)
            assertTrue(gaps.all { kotlin.math.abs(it - geo.panelWidthDp) < 0.01f })
        }
    }

    @Test
    fun `primary 438x236 two-row layout fits with charts and no intervening sections`() {
        val size = DpSize(438.dp, 236.dp)
        val spec = LayoutMetrics.fromSize(size)
        assertTrue(spec.fits)
        assertEquals(3, spec.equalTopPanels.centersDp.size)
        assertTrue(spec.equalTopPanels.equalWidths)
        assertTrue(spec.healthRowDp > 0)
        assertTrue(spec.activityDp > 0)
        assertTrue(spec.activityDp >= spec.healthRowDp - 8)
        assertTrue(spec.sleepRingDp >= LayoutMetrics.MIN_SLEEP_RING_DP)
        assertTrue(spec.hrvGraphWidthDp > 0f)
        assertTrue(spec.hrvGraphHeightDp > 0f)
        assertTrue(spec.panelChartWidthDp > 0f)
        assertTrue(spec.panelChartHeightDp > 0f)
        assertTrue(spec.showActivityHrChart)
        assertTrue(spec.activityHrChartHeightDp > 0)
        assertTrue(spec.activityInternalBudgetFits)
        assertTrue(spec.activityAlignsWithHealthRowInsets)
        assertEquals(
            LayoutMetrics.activityHrChartHeightDp(spec.activityDp),
            spec.activityHrChartHeightDp,
        )
        assertEquals(
            LayoutMetrics.activityChartContentWidthDp(spec.contentWidthDp),
            spec.activityChartContentWidthDp,
            0.01f,
        )
        assertFalse(spec.hasStandaloneHealthChart)
        assertFalse(spec.hasMetricsRow)
        assertFalse(spec.hasSleepLegend)
        assertTrue(spec.usesHealthCardBackground)
        assertTrue(healthPanelsUseCardBackground())
        assertEquals(0x0C, HEALTH_PANEL_CARD_COLOR ushr 24)
        assertTrue(spec.hrvInternalBudgetFits)
        assertTrue(spec.bodyBatteryInternalBudgetFits)
        assertTrue(spec.sleepInternalBudgetFits)
        assertTrue(spec.healthChartsBottomAligned)
        assertEquals(
            LayoutMetrics.HEALTH_CARD_BOTTOM_INSET_DP + LayoutMetrics.HEALTH_DATA_INNER_VERTICAL_PADDING_DP * 2,
            spec.healthPanelContentHeightDp - (spec.hrvGraphHeightDp.roundToInt() + LayoutMetrics.HRV_HEADER_DP + LayoutMetrics.HRV_STATUS_DP),
        )
        assertEquals(
            LayoutMetrics.HEALTH_CARD_BOTTOM_INSET_DP + LayoutMetrics.HEALTH_DATA_INNER_VERTICAL_PADDING_DP * 2,
            spec.healthPanelContentHeightDp - (spec.panelChartHeightDp.roundToInt() + LayoutMetrics.BODY_BATTERY_HEADER_DP),
        )
        assertEquals(26f, sleepScoreFontSp(spec.widthDp), 0.01f)
        assertTrue(spec.estimatedUsedHeightDp <= size.height.value.roundToInt())
        val sleep = sleepRingContentPresentation()
        assertFalse(sleep.showTitle)
        assertTrue(sleep.durationInsideRing)
        assertFalse(sleep.durationBelowRing)
        assertTrue(spec.sleepRingDp + 0.01f >= spec.healthPanelContentHeightDp - LayoutMetrics.HEALTH_CARD_BOTTOM_INSET_DP - 1f)
    }

    @Test
    fun `adaptive two-row layout fits representative wide sizes`() {
        val cases = listOf(
            DpSize(438.dp, 236.dp),
            DpSize(350.dp, 189.dp),
            DpSize(300.dp, 180.dp),
            DpSize(300.dp, 200.dp),
            DpSize(320.dp, 220.dp),
            DpSize(280.dp, 170.dp),
            DpSize(360.dp, 240.dp),
            DpSize(250.dp, 180.dp),
        )
        cases.forEach { size ->
            val spec = LayoutMetrics.fromSize(size)
            assertTrue("does not fit $size used=${spec.estimatedUsedHeightDp}", spec.fits)
            assertTrue(spec.healthRowDp > 0)
            assertTrue(spec.activityDp > 0)
            assertTrue(spec.sleepRingDp > 0f)
            assertTrue(spec.hrvGraphHeightDp > 0f)
            assertTrue(spec.panelChartHeightDp > 0f)
            assertTrue(spec.showActivityHrChart)
            assertTrue("activity budget for $size", spec.activityInternalBudgetFits)
            assertTrue(spec.activityAlignsWithHealthRowInsets)
            assertFalse(spec.hasStandaloneHealthChart)
            assertFalse(spec.hasMetricsRow)
            assertFalse(spec.hasSleepLegend)
            assertTrue(spec.usesHealthCardBackground)
            assertTrue("hrv budget overflow for $size", spec.hrvInternalBudgetFits)
            assertTrue("bb budget overflow for $size", spec.bodyBatteryInternalBudgetFits)
            assertTrue("sleep budget overflow for $size", spec.sleepInternalBudgetFits)
            assertTrue("charts not bottom-aligned for $size", spec.healthChartsBottomAligned)
        }
        val tall = LayoutMetrics.fromSize(DpSize(350.dp, 260.dp))
        val primary = LayoutMetrics.fromSize(DpSize(438.dp, 236.dp))
        assertTrue(tall.activityDp >= primary.activityDp - 20)
    }

    @Test
    fun `health headers expose trailing values without duplication`() {
        assertEquals(13f, HEALTH_TRAILING_VALUE_FONT_SP, 0.01f)
        assertEquals(11f, ACTIVITY_MAX_HR_FONT_SP, 0.01f)
        val hrv = hrvHeaderPresentation(43, "BALANCED", showTitle = true)
        assertEquals("HRV Status", hrv.title)
        assertEquals("43 ms", hrv.trailingValue)
        assertEquals("Balanced", hrv.supportingLeft)
        assertFalse(hrv.duplicatesTrailingBelow)

        val bb = bodyBatteryHeaderPresentation(64, showTitle = true)
        assertEquals("Body Battery", bb.title)
        assertEquals("64", bb.trailingValue)
        assertEquals(null, bb.supportingLeft)
        assertFalse(bb.duplicatesTrailingBelow)
    }

    @Test
    fun `sleep score typography grows at wide allocation`() {
        assertEquals(26f, sleepScoreFontSp(438f), 0.01f)
        assertTrue(sleepScoreFontSp(438f) > sleepScoreFontSp(300f))
        assertTrue(sleepScoreFontSp(300f) >= 13f)
        assertTrue(sleepScoreFontSp(200f) >= 13f)
        assertTrue(sleepDurationFontSp(438f) < sleepScoreFontSp(438f))
        assertEquals(12f, sleepDurationFontSp(438f), 0.01f)
    }

    @Test
    fun `timeline day range follows the response date in the phone timezone`() {
        val range = timelineDayRange("2026-07-29", ZoneId.of("Europe/Bucharest"))!!

        assertEquals(Instant.parse("2026-07-28T21:00:00Z"), range.first)
        assertEquals(Instant.parse("2026-07-29T21:00:00Z"), range.second)
    }

    @Test
    fun `sleep stage legend is absent from widget presentation`() {
        assertFalse(widgetRendersSleepStageLegend())
        assertEquals("Deep 1h 30m", formatStageLabel("Deep", 5400))
    }

    @Test
    fun `widget bitmap estimate stays under 600KB`() {
        val bytes = LayoutMetrics.estimateWidgetBitmapBytes()
        assertTrue("estimated $bytes bytes", bytes < LayoutMetrics.MAX_LARGE_WIDGET_BITMAP_BYTES)
        assertTrue(bytes > 0)
    }

    @Test
    fun `clamps opacity and alpha with slight scrim darkening`() {
        assertEquals(88, clampOpacityPercent(null))
        assertEquals(0, clampOpacityPercent(-3))
        assertEquals(100, clampOpacityPercent(120))
        assertEquals(0.88f + (1f - 0.88f) * WIDGET_SCRIM_EXTRA_ALPHA, opacityPercentToAlpha(88), 0.0001f)
        assertTrue(opacityPercentToAlpha(88) > 0.88f)
        assertTrue(opacityPercentToAlpha(50) > 0.50f)
        assertEquals(1f, opacityPercentToAlpha(100), 0.0001f)
        assertTrue(widgetBackgroundRemainsTranslucent(88))
        assertFalse(widgetBackgroundRemainsTranslucent(100))
    }

    @Test
    fun `activity card chrome is charcoal with square top and rounded bottom`() {
        val corners = activityCardCornerSpec()
        assertEquals(0f, corners.topLeftRadiusDp, 0.01f)
        assertEquals(0f, corners.topRightRadiusDp, 0.01f)
        assertEquals(24f, corners.bottomLeftRadiusDp, 0.01f)
        assertEquals(24f, corners.bottomRightRadiusDp, 0.01f)
        val scrim = activityCardScrimSpec()
        assertEquals(0x2E000000, scrim.topArgb)
        assertEquals(0x1F000000, scrim.bottomArgb)
        assertTrue(scrim.topAlpha in 0.16f..0.18f + 0.01f)
        assertTrue(scrim.bottomAlpha in 0.10f..0.12f + 0.01f)
        assertTrue(scrim.topAlpha > scrim.bottomAlpha)
        assertEquals(0.40f, ACTIVITY_NAME_MAX_WIDTH_FRACTION, 0.01f)
        assertFalse(activityNameOverlapsCenteredMaxHr(ACTIVITY_NAME_MAX_WIDTH_FRACTION))
        assertTrue(activityNameOverlapsCenteredMaxHr(0.55f))
        assertTrue(ACTIVITY_MAX_HR_FONT_SP >= 11f)
    }
    @Test
    fun `formats duration and stage labels`() {
        assertEquals("6h 29m", formatSleepDuration(23340))
        assertEquals("—", formatSleepDuration(null))
        assertEquals("Deep 1h 30m", formatStageLabel("Deep", 5400))
    }

    @Test
    fun `maps hrv status markers case insensitive`() {
        assertEquals(HrvMarkerKind.CIRCLE, mapHrvStatusToMarker("balanced"))
        assertEquals(HrvMarkerKind.SQUARE, mapHrvStatusToMarker("UNBALANCED"))
        assertEquals(HrvMarkerKind.TRIANGLE, mapHrvStatusToMarker("poor"))
        assertEquals(HrvMarkerKind.NEUTRAL, mapHrvStatusToMarker("none"))
    }

    @Test
    fun `point aware hrv markers force neutral when incomplete`() {
        assertEquals(
            HrvMarkerKind.NEUTRAL,
            mapHrvPointToMarker(HrvTrendPoint(date = null, overnightAverage = 40, sevenDayAverage = 41, status = "BALANCED")),
        )
        assertEquals(
            HrvMarkerKind.NEUTRAL,
            mapHrvPointToMarker(HrvTrendPoint(date = LocalDate.parse("2026-07-28"), overnightAverage = null, sevenDayAverage = null, status = "BALANCED")),
        )
        assertEquals(
            HrvMarkerKind.CIRCLE,
            mapHrvPointToMarker(HrvTrendPoint(date = LocalDate.parse("2026-07-28"), overnightAverage = 40, sevenDayAverage = null, status = "BALANCED")),
        )
    }

    @Test
    fun `picks recent hrv points by chronological order`() {
        val points = listOf(
            HrvTrendPoint(LocalDate.parse("2026-07-24"), 44, 42, "BALANCED"),
            HrvTrendPoint(LocalDate.parse("2026-07-22"), 41, 40, "BALANCED"),
            HrvTrendPoint(LocalDate.parse("2026-07-23"), 42, 41, "NONE"),
            HrvTrendPoint(LocalDate.parse("2026-07-25"), 43, null, null),
        )
        val selected = pickRecentHrvPoints(points, 3)
        assertEquals(3, selected.size)
        assertEquals(LocalDate.parse("2026-07-23"), selected[0].date)
        assertEquals(LocalDate.parse("2026-07-25"), selected[2].date)
        assertTrue(hasRenderableHrvTrend(points))
        assertFalse(hasRenderableHrvTrend(listOf(HrvTrendPoint(LocalDate.parse("2026-07-25"), 43, null, null))))
    }

    @Test
    fun `filters timeline by local response date`() {
        val zone = ZoneId.of("Europe/Bucharest")
        val values = listOf(
            TimelinePoint(Instant.parse("2026-07-27T20:30:00Z"), 40),
            TimelinePoint(Instant.parse("2026-07-27T22:10:00Z"), 50),
            TimelinePoint(Instant.parse("2026-07-28T10:00:00Z"), 60),
        )
        val filtered = filterTimelineForResponseDate(values, "2026-07-28", zone)
        assertEquals(2, filtered.size)
        assertEquals(50, filtered[0].value)
        assertEquals(60, filtered[1].value)
    }

    @Test
    fun `formats activity metrics`() {
        assertEquals("5:00 /km", formatPace(5000.0, 1500))
        assertEquals("28.8 km/h", formatSpeedMps(8.0))
        assertEquals("5.1 km", formatDistanceKm(5120.5))
        assertEquals("148 bpm", formatHr(148))
        assertEquals("380 kcal", formatCalories(380))
    }

    @Test
    fun `formats local time with explicit timezone and locale`() {
        val instant = Instant.parse("2026-07-28T05:00:00Z")
        val text = formatLocalTime(instant, ZoneId.of("Europe/Bucharest"), Locale.US)
        assertEquals("08:00", text)
    }

    @Test
    fun `activity type mapping and primary metric`() {
        val run = LastActivity(
            typeKey = "running",
            distanceMeters = 5000.0,
            durationSeconds = 1500,
            movingDurationSeconds = 1450,
        )
        val bike = LastActivity(
            typeKey = "cycling",
            averageSpeedMetersPerSecond = 8.0,
        )
        assertEquals("running", activityTypeIcon("running"))
        assertEquals("running", activityTypeIcon("trail_running"))
        assertEquals("running", activityTypeIcon("treadmill-running"))
        assertEquals("walking", activityTypeIcon("walking"))
        assertEquals("hiking", activityTypeIcon("hiking"))
        assertEquals("cycling", activityTypeIcon("indoor_cycling"))
        assertEquals("cycling", activityTypeIcon("mountain-biking"))
        assertEquals("strength_training", activityTypeIcon("strength_training"))
        assertEquals("swimming", activityTypeIcon("lap_swimming"))
        assertEquals("swimming", activityTypeIcon("open_water_swimming"))
        assertEquals("cardio", activityTypeIcon("hiit"))
        assertEquals("yoga", activityTypeIcon("pilates"))
        assertEquals("skiing", activityTypeIcon("resort_skiing"))
        assertEquals("generic", activityTypeIcon("unknown_sport"))
        assertEquals("Activity icon for hiking", activityIconContentDescription("hiking"))
        assertEquals("4:50 /km", activityPrimaryMetric(run))
        assertEquals("28.8 km/h", activityPrimaryMetric(bike))
    }

    @Test
    fun `battery stress presentation avoids ambiguous abbreviations`() {
        val labels = batteryStressPresentation(72, 18).visibleLabels()
        assertEquals(listOf("Body Battery", "Stress"), labels)
        assertTrue(!hasAmbiguousMetricAbbreviation(labels))
        assertTrue(hasAmbiguousMetricAbbreviation(listOf("B", "Stress")))
        assertTrue(hasAmbiguousMetricAbbreviation(listOf("BATT")))
        assertTrue(hasAmbiguousMetricAbbreviation(listOf("S")))
    }

    @Test
    fun `status accessibility descriptions`() {
        assertEquals("Refresh Garmin widget", refreshContentDescription(LocalStatus.READY))
        assertEquals("Refreshing Garmin widget", refreshContentDescription(LocalStatus.REFRESHING))
        val chart = chartContentDescription(10, 12, bodyBattery = 72, stress = 18)
        assertTrue(chart.contains("Body Battery 72"))
        assertTrue(chart.contains("Stress 18"))
        assertTrue(sleepRingContentDescription(80, true).contains("80"))
        assertTrue(statusContentDescription(LocalStatus.READY).contains("ready"))
        assertEquals("Sleep panel icon", healthPanelIconContentDescription(HealthPanelIcon.SLEEP))
        assertEquals("HRV panel icon", healthPanelIconContentDescription(HealthPanelIcon.HRV))
        assertEquals("Body Battery panel icon", healthPanelIconContentDescription(HealthPanelIcon.BODY_BATTERY))
    }
}

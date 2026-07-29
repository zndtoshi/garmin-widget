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
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFormattersTest {
    @Test
    fun `classifies size buckets with width and height boundaries`() {
        assertEquals(WidgetSizeClass.COMPACT, classifySize(DpSize(180.dp, 110.dp)))
        assertEquals(WidgetSizeClass.WIDE, classifySize(DpSize(300.dp, 180.dp)))
        assertEquals(WidgetSizeClass.LARGE, classifySize(DpSize(300.dp, 280.dp)))

        // Wide-short / narrow-tall stay compact when either dimension misses thresholds.
        assertEquals(WidgetSizeClass.COMPACT, classifySize(DpSize(300.dp, 140.dp)))
        assertEquals(WidgetSizeClass.COMPACT, classifySize(DpSize(200.dp, 240.dp)))
        assertEquals(WidgetSizeClass.COMPACT, classifySize(DpSize(249.dp, 240.dp)))
        assertEquals(WidgetSizeClass.COMPACT, classifySize(DpSize(250.dp, 149.dp)))

        // Boundary at thresholds.
        assertEquals(WidgetSizeClass.WIDE, classifySize(DpSize(250.dp, 150.dp)))
        assertEquals(WidgetSizeClass.WIDE, classifySize(DpSize(250.dp, 239.dp)))
        assertEquals(WidgetSizeClass.LARGE, classifySize(DpSize(250.dp, 240.dp)))
    }

    @Test
    fun `equal top panels share width and centers`() {
        listOf(164f, 284f, 284f).forEach { contentWidth ->
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
    fun `layout budgets fit advertised widget heights`() {
        assertTrue(LayoutMetrics.HEADER_DP >= LayoutMetrics.REFRESH_TOUCH_TARGET_DP)
        assertTrue(LayoutMetrics.compactBudget().fits)
        assertTrue(LayoutMetrics.wideBudget().fits)
        assertTrue(LayoutMetrics.largeBudget().fits)
        assertTrue(LayoutMetrics.compactBudget().estimatedUsedHeightDp <= 110)
        assertTrue(LayoutMetrics.wideBudget().estimatedUsedHeightDp <= 180)
        assertTrue(LayoutMetrics.largeBudget().estimatedUsedHeightDp <= 280)
        assertTrue(LayoutMetrics.wideBudget().healthRowDp in 64..96)
        assertTrue(LayoutMetrics.wideBudget().activityDp in 30..48)
        assertTrue(LayoutMetrics.largeBudget().activityDp in 100..140)
        assertTrue(
            LayoutMetrics.largeSleepPanelContentHeightDp() <= LayoutMetrics.largeBudget().healthRowDp + 20,
        )
    }

    @Test
    fun `adaptive layout fits every representative and boundary size`() {
        val cases = listOf(
            DpSize(180.dp, 110.dp),
            DpSize(300.dp, 180.dp),
            DpSize(300.dp, 280.dp),
            DpSize(300.dp, 360.dp),
            DpSize(200.dp, 280.dp),
            DpSize(320.dp, 140.dp),
            DpSize(249.dp, 240.dp),
            DpSize(250.dp, 150.dp),
            DpSize(250.dp, 239.dp),
            DpSize(250.dp, 240.dp),
            DpSize(250.dp, 250.dp),
        )
        cases.forEach { size ->
            val spec = LayoutMetrics.fromSize(size)
            assertEquals(classifySize(size), spec.sizeClass)
            assertEquals(3, spec.equalTopPanels.centersDp.size)
            assertTrue(spec.equalTopPanels.equalWidths)
            assertTrue(spec.panelWidthDp > 0f)
            assertTrue(
                "used ${spec.estimatedUsedHeightDp} > height ${size.height.value} for $size",
                spec.fits,
            )
            assertTrue(spec.estimatedUsedHeightDp <= size.height.value.roundToInt())
            assertTrue(spec.healthRowDp >= 0)
            assertTrue(spec.chartDp >= 0)
            assertTrue(spec.metricsRowDp >= 0)
            assertTrue(spec.activityDp >= 0)
            when (spec.sizeClass) {
                WidgetSizeClass.COMPACT -> {
                    assertEquals(0, spec.afterHealthSpacerDp)
                    assertEquals(0, spec.afterChartSpacerDp)
                    assertEquals(0, spec.afterMetricsSpacerDp)
                    assertEquals(0, spec.activityDp)
                }
                WidgetSizeClass.WIDE -> {
                    assertEquals(if (spec.activityDp > 0) 4 else 0, spec.afterHealthSpacerDp)
                    assertEquals(0, spec.afterChartSpacerDp)
                    assertEquals(0, spec.afterMetricsSpacerDp)
                }
                WidgetSizeClass.LARGE -> {
                    assertEquals(if (spec.chartDp > 0) 4 else 0, spec.afterHealthSpacerDp)
                    assertEquals(if (spec.metricsRowDp > 0) 4 else 0, spec.afterChartSpacerDp)
                    assertEquals(if (spec.activityDp > 0) 4 else 0, spec.afterMetricsSpacerDp)
                }
            }
        }

        val boundaryLarge = LayoutMetrics.fromSize(DpSize(250.dp, 240.dp))
        assertEquals(WidgetSizeClass.LARGE, boundaryLarge.sizeClass)
        assertTrue(boundaryLarge.fits)
        val largeMinPreset = LayoutMetrics.fromSize(DpSize(250.dp, 250.dp))
        assertEquals(WidgetSizeClass.LARGE, largeMinPreset.sizeClass)
        assertTrue(largeMinPreset.fits)

        val baseline = LayoutMetrics.fromSize(DpSize(300.dp, 280.dp))
        assertTrue(baseline.fits)
        assertTrue("baseline activity ${baseline.activityDp}", baseline.activityDp in 100..110)
        val tall = LayoutMetrics.fromSize(DpSize(300.dp, 360.dp))
        assertTrue(tall.activityDp >= baseline.activityDp)
    }

    @Test
    fun `sleep legend presentation never includes stage durations`() {
        sleepLegendPresentation().forEach { label ->
            assertTrue(!sleepLegendContainsStageDuration(label))
            assertTrue(label.length <= 2)
        }
        assertEquals("Deep 1h 30m", formatStageLabel("Deep", 5400))
        assertTrue(sleepLegendContainsStageDuration(formatStageLabel("Deep", 5400)))
    }

    @Test
    fun `large layout bitmap estimate stays under 600KB`() {
        val bytes = LayoutMetrics.estimateLargeLayoutBitmapBytes()
        assertTrue("estimated $bytes bytes", bytes < LayoutMetrics.MAX_LARGE_WIDGET_BITMAP_BYTES)
        assertTrue(bytes > 0)
    }

    @Test
    fun `clamps opacity and alpha`() {
        assertEquals(88, clampOpacityPercent(null))
        assertEquals(0, clampOpacityPercent(-3))
        assertEquals(100, clampOpacityPercent(120))
        assertEquals(0.88f, opacityPercentToAlpha(88), 0.0001f)
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
        assertEquals(7, pickRecentHrvPoints(points + points + points, 7).size.coerceAtMost(7))
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
    }
}

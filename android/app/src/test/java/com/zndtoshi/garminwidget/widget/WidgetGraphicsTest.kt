package com.zndtoshi.garminwidget.widget

import com.zndtoshi.garminwidget.data.ActivityHeartRatePoint
import com.zndtoshi.garminwidget.data.HrvTrendPoint
import com.zndtoshi.garminwidget.data.LastActivity
import com.zndtoshi.garminwidget.data.SleepStages
import com.zndtoshi.garminwidget.data.TimelinePoint
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class WidgetGraphicsTest {
    @Test
    fun `sleep ring segments fallback to neutral`() {
        val segments = buildSleepRingSegments(null)
        assertEquals(1, segments.size)
        assertEquals(360f, segments.first().sweepDegrees)
        assertEquals(WidgetPalette.neutral, segments.first().color)
    }

    @Test
    fun `sleep ring segments use proportional arcs and palette colors`() {
        val segments = buildSleepRingSegments(
            SleepStages(
                deepSeconds = 1800,
                lightSeconds = 3600,
                remSeconds = 1800,
                awakeSeconds = 600,
            ),
        )
        assertEquals(4, segments.size)
        assertEquals(WidgetPalette.deep, segments[0].color)
        assertEquals(WidgetPalette.light, segments[1].color)
        assertEquals(WidgetPalette.rem, segments[2].color)
        assertEquals(WidgetPalette.awake, segments[3].color)
        val totalSweep = segments.sumOf { it.sweepDegrees.toDouble() }
        assertTrue(totalSweep > 340.0)
    }

    @Test
    fun `combined chart geometry handles empty one-point sparse unsorted and fixed scale`() {
        val empty = buildCombinedChartGeometry(100, 50, emptyList(), emptyList())
        assertTrue(empty.batteryPoints.isEmpty())
        assertTrue(empty.stressPoints.isEmpty())

        val oneBattery = buildCombinedChartGeometry(
            100,
            50,
            listOf(TimelinePoint(Instant.parse("2026-07-28T12:00:00Z"), 50)),
            emptyList(),
        )
        assertEquals(1, oneBattery.batteryPoints.size)
        assertEquals(50, oneBattery.batteryPoints.first().value)

        val oneStress = buildCombinedChartGeometry(
            100,
            50,
            emptyList(),
            listOf(TimelinePoint(Instant.parse("2026-07-28T12:00:00Z"), 25)),
        )
        assertEquals(1, oneStress.stressPoints.size)

        val unsorted = listOf(
            TimelinePoint(Instant.parse("2026-07-28T04:00:00Z"), 80),
            TimelinePoint(Instant.parse("2026-07-28T01:00:00Z"), 20),
            TimelinePoint(Instant.parse("2026-07-28T02:00:00Z"), 50),
        )
        val geo = buildCombinedChartGeometry(104, 54, unsorted, emptyList())
        assertEquals(listOf(20, 50, 80), geo.batteryPoints.map { it.value })
        assertTrue(geo.batteryPoints[0].x < geo.batteryPoints[1].x)
        assertTrue(geo.batteryPoints[1].x < geo.batteryPoints[2].x)

        val height = 104
        val scaled = buildCombinedChartGeometry(
            104,
            height,
            listOf(
                TimelinePoint(Instant.parse("2026-07-28T01:00:00Z"), 0),
                TimelinePoint(Instant.parse("2026-07-28T02:00:00Z"), 50),
                TimelinePoint(Instant.parse("2026-07-28T03:00:00Z"), 100),
            ),
            emptyList(),
        )
        val top = 4f
        val bottom = (height - 15).toFloat()
        assertEquals(bottom, scaled.batteryPoints[0].y, 0.01f)
        assertEquals((top + bottom) / 2f, scaled.batteryPoints[1].y, 0.01f)
        assertEquals(top, scaled.batteryPoints[2].y, 0.01f)
    }

    @Test
    fun `combined chart geometry spans different series timestamp ranges`() {
        val battery = listOf(TimelinePoint(Instant.parse("2026-07-28T00:00:00Z"), 40))
        val stress = listOf(TimelinePoint(Instant.parse("2026-07-28T12:00:00Z"), 70))
        val geo = buildCombinedChartGeometry(204, 54, battery, stress)
        assertTrue(geo.batteryPoints.first().x < geo.stressPoints.first().x)
    }

    @Test
    fun `combined chart can use a complete day for its horizontal scale`() {
        val start = Instant.parse("2026-07-28T00:00:00Z")
        val end = Instant.parse("2026-07-29T00:00:00Z")
        val stress = listOf(
            TimelinePoint(Instant.parse("2026-07-28T06:00:00Z"), 20),
            TimelinePoint(Instant.parse("2026-07-28T18:00:00Z"), 40),
        )
        val geo = buildCombinedChartGeometry(104, 54, emptyList(), stress, start, end)
        val plotWidth = geo.right - geo.left

        assertEquals(geo.left + plotWidth * 0.25f, geo.stressPoints[0].x, 0.01f)
        assertEquals(geo.left + plotWidth * 0.75f, geo.stressPoints[1].x, 0.01f)
    }

    @Test
    fun `hrv graph uses fixed 22-80 scale with fallback gaps and markers`() {
        val points = listOf(
            HrvTrendPoint(LocalDate.parse("2026-07-22"), overnightAverage = 22, sevenDayAverage = null, status = "BALANCED"),
            HrvTrendPoint(LocalDate.parse("2026-07-23"), overnightAverage = 10, sevenDayAverage = null, status = "LOW"),
            HrvTrendPoint(LocalDate.parse("2026-07-24"), overnightAverage = null, sevenDayAverage = 51, status = "UNBALANCED"),
            HrvTrendPoint(LocalDate.parse("2026-07-25"), overnightAverage = null, sevenDayAverage = null, status = "BALANCED"),
            HrvTrendPoint(LocalDate.parse("2026-07-26"), overnightAverage = 90, sevenDayAverage = 80, status = "POOR"),
            HrvTrendPoint(LocalDate.parse("2026-07-27"), overnightAverage = 40, sevenDayAverage = 41, status = "NONE"),
            HrvTrendPoint(LocalDate.parse("2026-07-28"), overnightAverage = 55, sevenDayAverage = 54, status = "BALANCED"),
        )
        val geo = buildHrvGraphGeometry(160, 64, points, maxPoints = 7, showMidLabel = true)
        assertEquals(7, geo.slots.size)
        assertEquals(geo.plotBottom, geo.yAtMin, 0.01f)
        assertEquals(geo.plotTop, geo.yAtMax, 0.01f)
        assertEquals(geo.yAtMin, geo.slots[0].y!!, 0.01f)
        assertEquals(geo.yAtMin, geo.slots[1].y!!, 0.01f)
        assertEquals(51, geo.slots[2].rawValue)
        assertEquals(geo.yAtMid, geo.slots[2].y!!, 0.01f)
        assertNull(geo.slots[3].y)
        assertEquals(80, geo.slots[4].rawValue)
        assertEquals(geo.yAtMax, geo.slots[4].y!!, 0.01f)
        assertEquals(HrvMarkerKind.CIRCLE, geo.slots[0].marker)
        assertEquals(HrvMarkerKind.TRIANGLE, geo.slots[1].marker)
        assertEquals(HrvMarkerKind.SQUARE, geo.slots[2].marker)
        assertEquals(HrvMarkerKind.NEUTRAL, geo.slots[3].marker)
        assertEquals(HrvMarkerKind.TRIANGLE, geo.slots[4].marker)
        assertEquals(HrvMarkerKind.NEUTRAL, geo.slots[5].marker)
        assertTrue(geo.slots[0].x < geo.slots[6].x)
    }

    @Test
    fun `activity icon key mapping covers sport family`() {
        assertEquals("running", activityTypeIcon("running"))
        assertEquals("walking", activityTypeIcon("walking"))
        assertEquals("hiking", activityTypeIcon("hiking"))
        assertEquals("swimming", activityTypeIcon("lap_swimming"))
        assertEquals("cardio", activityTypeIcon("hiit"))
        assertEquals("yoga", activityTypeIcon("yoga"))
        assertEquals("skiing", activityTypeIcon("snowboarding"))
        val activity = LastActivity(
            name = "Run",
            typeKey = "running",
            maxHeartRate = 170,
            elevationGainMeters = 120.4,
            aerobicTrainingEffect = 3.2,
            anaerobicTrainingEffect = 1.1,
            trainingLoad = 85.0,
        )
        val details = activityDetailPairs(activity, rich = true)
        assertTrue(details.any { it.second.contains("120") })
        assertTrue(details.any { it.second == "3.2" })
    }

    @Test
    fun `activity hr chart geometry orders elapsed scale and marks max`() {
        val empty = buildActivityHrChartGeometry(120, 40, emptyList())
        assertTrue(empty.points.isEmpty())
        assertTrue(!empty.hasRenderableSeries)

        val one = buildActivityHrChartGeometry(
            120,
            40,
            listOf(ActivityHeartRatePoint(0, 120)),
        )
        assertEquals(1, one.points.size)
        assertTrue(!one.hasRenderableSeries)

        val unsorted = listOf(
            ActivityHeartRatePoint(90, 150),
            ActivityHeartRatePoint(0, 112),
            ActivityHeartRatePoint(30, 138),
            ActivityHeartRatePoint(60, 172),
            ActivityHeartRatePoint(120, 140),
        )
        val geo = buildActivityHrChartGeometry(200, 60, unsorted)
        assertEquals(listOf(112, 138, 172, 150, 140), geo.points.map { it.value })
        assertTrue(geo.points.zipWithNext().all { (a, b) -> a.x <= b.x })
        assertEquals(172, geo.maxPoint?.value)
        assertTrue(geo.minHr in 20..250)
        assertTrue(geo.maxHr in geo.minHr..250)
        assertTrue(geo.hasRenderableSeries)

        val outOfBounds = buildActivityHrChartGeometry(
            100,
            40,
            listOf(
                ActivityHeartRatePoint(-1, 120),
                ActivityHeartRatePoint(0, 10),
                ActivityHeartRatePoint(10, 260),
                ActivityHeartRatePoint(20, 130),
                ActivityHeartRatePoint(30, 140),
            ),
        )
        assertEquals(listOf(130, 140), outOfBounds.points.map { it.value })
    }

    @Test
    fun `activity hr chart falls back for sparse timelines`() {
        assertTrue(!buildActivityHrChartGeometry(100, 40, emptyList()).hasRenderableSeries)
        assertTrue(
            !buildActivityHrChartGeometry(
                100,
                40,
                listOf(ActivityHeartRatePoint(0, 120)),
            ).hasRenderableSeries,
        )
        assertNull(drawActivityHrChartBitmap(100, 40, emptyList()))
        assertNull(drawActivityHrChartBitmap(100, 40, listOf(ActivityHeartRatePoint(0, 120))))
        assertTrue(
            buildActivityHrChartGeometry(
                120,
                40,
                listOf(
                    ActivityHeartRatePoint(0, 120),
                    ActivityHeartRatePoint(30, 150),
                    ActivityHeartRatePoint(60, 140),
                ),
            ).hasRenderableSeries,
        )
    }

    @Test
    fun `hr zone colors progress continuously from green to red`() {
        val maxHr = 200
        assertEquals(HR_ZONE_GREEN, heartRateZoneColorArgb(140, maxHr)) // 70%
        assertEquals(HR_ZONE_GREEN, heartRateZoneColorArgb(100, maxHr)) // below 70%
        val midGreenYellow = heartRateZoneColorArgb(150, maxHr) // 75%
        assertTrue(midGreenYellow != HR_ZONE_GREEN)
        assertTrue(midGreenYellow != HR_ZONE_YELLOW)
        assertEquals(HR_ZONE_YELLOW, heartRateZoneColorArgb(160, maxHr)) // 80%
        val midYellowOrange = heartRateZoneColorArgb(170, maxHr) // 85%
        assertTrue(midYellowOrange != HR_ZONE_YELLOW)
        assertTrue(midYellowOrange != HR_ZONE_ORANGE)
        assertEquals(HR_ZONE_ORANGE, heartRateZoneColorArgb(180, maxHr)) // 90%
        val midOrangeRed = heartRateZoneColorArgb(190, maxHr) // 95%
        assertTrue(midOrangeRed != HR_ZONE_ORANGE)
        assertTrue(midOrangeRed != HR_ZONE_RED)
        assertEquals(HR_ZONE_RED, heartRateZoneColorArgb(200, maxHr))
        assertEquals(HR_ZONE_RED, heartRateZoneColorArgb(220, maxHr))

        // Continuity near thresholds: adjacent samples must not jump across zones abruptly.
        val justBelow80 = heartRateZoneColorArgb(159, maxHr)
        val justAbove80 = heartRateZoneColorArgb(161, maxHr)
        val y80 = heartRateZoneColorArgb(160, maxHr)
        fun channelDelta(a: Int, b: Int): Int {
            val dr = kotlin.math.abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff))
            val dg = kotlin.math.abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff))
            val db = kotlin.math.abs((a and 0xff) - (b and 0xff))
            return dr + dg + db
        }
        assertTrue(channelDelta(justBelow80, y80) < 80)
        assertTrue(channelDelta(justAbove80, y80) < 80)
    }

    @Test
    fun `hr ceiling falls back to timeline max when activity max missing`() {
        val timeline = listOf(
            ActivityHeartRatePoint(0, 120),
            ActivityHeartRatePoint(30, 171),
            ActivityHeartRatePoint(60, 140),
        )
        assertEquals(193, resolveActivityHrCeiling(193, timeline))
        assertEquals(171, resolveActivityHrCeiling(null, timeline))
        assertEquals(171, resolveActivityHrCeiling(0, timeline))
        assertEquals(180, resolveActivityHrCeiling(null, emptyList()))
    }

    @Test
    fun `activity hr stroke and marker stay thin`() {
        val stroke = activityHrStrokeWidthPx(120)
        val marker = activityHrMarkerRadiusPx(120)
        assertTrue("stroke $stroke", stroke in 2f..3f)
        assertTrue("marker $marker", marker in 2f..3.2f)
        assertTrue(stroke <= 3f)
        assertTrue(marker < stroke * 1.6f)
    }

    @Test
    fun `hrv geometry keeps y labels inside plot vertical bounds`() {
        val geo = buildHrvGraphGeometry(
            widthPx = 160,
            heightPx = 56,
            points = listOf(
                HrvTrendPoint(LocalDate.parse("2026-07-22"), 40, 42, "BALANCED"),
                HrvTrendPoint(LocalDate.parse("2026-07-23"), 44, 43, "BALANCED"),
            ),
            maxPoints = 7,
            showMidLabel = true,
        )
        assertTrue(geo.yAtMax >= geo.plotTop - 0.01f)
        assertTrue(geo.yAtMin <= geo.plotBottom + 0.01f)
        assertTrue(geo.yAtMax < geo.yAtMin)
        assertTrue(geo.showMidLabel)
        assertTrue(geo.plotTop >= 6f)
    }

    @Test
    fun `stress bars classify rest blue and stress orange`() {
        assertEquals(StressBarKind.REST, classifyStressBar(0))
        assertEquals(StressBarKind.REST, classifyStressBar(25))
        assertEquals(StressBarKind.STRESS, classifyStressBar(26))
        assertEquals(StressBarKind.STRESS, classifyStressBar(100))
        assertNull(classifyStressBar(-1))
        assertNull(classifyStressBar(101))
        assertEquals(WidgetPalette.stressRest, stressBarColorArgb(0))
        assertEquals(WidgetPalette.stressRest, stressBarColorArgb(25))
        assertEquals(WidgetPalette.stress, stressBarColorArgb(26))
        assertEquals(WidgetPalette.stress, stressBarColorArgb(100))
    }

    @Test
    fun `mixed stress series counts blue and orange bars`() {
        val values = List(38) { 12 } + List(10) { 40 }
        val (rest, stress) = countStressBarColors(values)
        assertEquals(38, rest)
        assertEquals(10, stress)
        assertEquals(48, rest + stress)
    }

    @Test
    fun `combined chart layers draw grid then bars then curve`() {
        assertEquals(
            listOf(
                CombinedChartLayer.GRID,
                CombinedChartLayer.STRESS_BARS,
                CombinedChartLayer.BATTERY_CURVE,
                CombinedChartLayer.BATTERY_MARKER,
            ),
            combinedChartDrawOrder(),
        )
    }

    @Test
    fun `six sparse body battery samples produce smooth path with exact endpoints`() {
        val dayStart = Instant.parse("2026-07-28T00:00:00Z")
        val dayEnd = Instant.parse("2026-07-29T00:00:00Z")
        val measured = listOf(
            TimelinePoint(Instant.parse("2026-07-28T00:00:00Z"), 17),
            TimelinePoint(Instant.parse("2026-07-28T02:06:00Z"), 30),
            TimelinePoint(Instant.parse("2026-07-28T02:15:00Z"), 30),
            TimelinePoint(Instant.parse("2026-07-28T04:24:00Z"), 47),
            TimelinePoint(Instant.parse("2026-07-28T04:30:00Z"), 48),
            TimelinePoint(Instant.parse("2026-07-28T06:06:00Z"), 64),
        )
        val geo = buildCombinedChartGeometry(200, 80, measured, emptyList(), dayStart, dayEnd)
        assertEquals(6, geo.batteryPoints.size)
        assertEquals(17, geo.batteryPoints.first().value)
        assertEquals(64, geo.batteryPoints.last().value)

        val gapsMinutes = measured.zipWithNext { a, b ->
            (b.timestamp.epochSecond - a.timestamp.epochSecond) / 60
        }
        assertEquals(listOf(126L, 9L, 129L, 6L, 96L), gapsMinutes)

        val smooth = buildSmoothBatteryRenderPoints(geo.batteryPoints, samplesPerSegmentHint = 12)
        assertTrue("expected denser render path, got ${smooth.size}", smooth.size > measured.size)
        // 5 segments × ≥12 samples + start ≈ ≥61
        assertTrue(smooth.size >= 1 + 5 * 12)
        assertEquals(geo.batteryPoints.first().x, smooth.first().x, 0.01f)
        assertEquals(geo.batteryPoints.first().y, smooth.first().y, 0.01f)
        assertEquals(geo.batteryPoints.last().x, smooth.last().x, 0.01f)
        assertEquals(geo.batteryPoints.last().y, smooth.last().y, 0.01f)
        assertEquals(17, smooth.first().value)
        assertEquals(64, smooth.last().value)
        assertEquals(0xFFF5F7FA.toInt(), WidgetPalette.battery)
        assertTrue(WidgetPalette.batteryFill ushr 24 <= 0x20)
    }

    @Test
    fun `monotone cubic stays within adjacent values and keeps plateaus flat`() {
        val xs = floatArrayOf(0f, 126f, 135f, 264f, 270f, 366f)
        val values = floatArrayOf(17f, 30f, 30f, 47f, 48f, 64f)
        val samples = sampleMonotoneCubicValues(xs, values, samplesPerSegment = 16)
        assertEquals(xs.first(), samples.first().first, 0.001f)
        assertEquals(values.first(), samples.first().second, 0.001f)
        assertEquals(xs.last(), samples.last().first, 0.001f)
        assertEquals(values.last(), samples.last().second, 0.001f)

        for (i in 0 until xs.size - 1) {
            val lo = min(values[i], values[i + 1])
            val hi = max(values[i], values[i + 1])
            val mid = samples.filter { it.first in xs[i]..xs[i + 1] }
            assertTrue(mid.all { it.second in lo..hi })
        }

        // Equal-value plateau 30→30 stays flat (no NaN / overshoot).
        val plateau = samples.filter { it.first in 126f..135f }
        assertTrue(plateau.isNotEmpty())
        assertTrue(plateau.all { abs(it.second - 30f) < 1e-3f })
        assertTrue(fritschCarlsonSlopes(xs, values).all { it.isFinite() })
    }

    @Test
    fun `monotone cubic handles empty one two duplicate and irregular safely`() {
        assertTrue(sampleMonotoneCubicValues(floatArrayOf(), floatArrayOf()).isEmpty())
        assertEquals(listOf(1f to 50f), sampleMonotoneCubicValues(floatArrayOf(1f), floatArrayOf(50f)))

        val two = sampleMonotoneCubicValues(floatArrayOf(0f, 10f), floatArrayOf(10f, 40f), samplesPerSegment = 5)
        assertEquals(0f, two.first().first, 0.001f)
        assertEquals(10f, two.last().first, 0.001f)
        assertEquals(10f, two.first().second, 0.001f)
        assertEquals(40f, two.last().second, 0.001f)

        val deduped = dedupeChartPointsByX(
            listOf(
                ChartPoint(5f, 10f, 20),
                ChartPoint(5f, 12f, 25),
                ChartPoint(8f, 14f, 30),
            ),
        )
        assertEquals(2, deduped.size)
        assertEquals(25, deduped.first().value)

        val irregular = buildCombinedChartGeometry(
            104,
            54,
            listOf(
                TimelinePoint(Instant.parse("2026-07-28T00:00:00Z"), 17),
                TimelinePoint(Instant.parse("2026-07-28T02:06:00Z"), 30),
                TimelinePoint(Instant.parse("2026-07-28T06:06:00Z"), 64),
            ),
            emptyList(),
            Instant.parse("2026-07-28T00:00:00Z"),
            Instant.parse("2026-07-29T00:00:00Z"),
        )
        val plot = irregular.right - irregular.left
        // 2:06 is 126/1440 of the day ≈ 0.0875
        assertEquals(irregular.left + plot * (126.0 / 1440.0).toFloat(), irregular.batteryPoints[1].x, 0.5f)
        assertTrue(buildSmoothBatteryRenderPoints(emptyList()).isEmpty())
        assertEquals(1, buildSmoothBatteryRenderPoints(listOf(ChartPoint(1f, 2f, 3))).size)
    }

    @Test
    fun `stress bar widths shrink for close timestamps`() {
        val clustered = listOf(
            ChartPoint(10f, 20f, 10),
            ChartPoint(12f, 18f, 40),
            ChartPoint(30f, 15f, 15),
        )
        val closeHalf = stressBarHalfWidthPx(clustered, 0)
        val wideHalf = stressBarHalfWidthPx(clustered, 2)
        assertTrue(closeHalf <= 1.35f)
        assertTrue(closeHalf < wideHalf || closeHalf <= 0.85f)
        assertEquals(3f, stressBarHalfWidthPx(listOf(ChartPoint(5f, 5f, 10)), 0), 0.01f)
    }

    @Test
    fun `activity hr diffusion is zone colored short and fades to transparent`() {
        val plotHeight = 80f
        val depth = activityHrDiffusionDepthPx(plotHeight)
        assertTrue("depth $depth", depth in (34f * LayoutMetrics.RENDER_SCALE)..(52f * LayoutMetrics.RENDER_SCALE + 0.01f))
        assertTrue(depth < plotHeight)
        assertEquals(0x60, activityHrDiffusionStartAlpha())

        val midLineY = 20f
        val bottom = 100f
        val diffusedBottom = activityHrDiffusionBottomY(midLineY, depth, bottom)
        assertEquals(midLineY + depth, diffusedBottom, 0.01f)
        assertFalse(activityHrDiffusionExtendsToBaseline(midLineY, depth, bottom))

        val nearBaselineY = bottom - depth * 0.4f
        assertTrue(activityHrDiffusionExtendsToBaseline(nearBaselineY, depth, bottom))
        assertEquals(bottom, activityHrDiffusionBottomY(nearBaselineY, depth, bottom), 0.01f)

        val green = heartRateZoneColorArgb(120, 200)
        val red = heartRateZoneColorArgb(200, 200)
        assertEquals(activityHrDiffusionStartAlpha(), argbWithAlpha(green, activityHrDiffusionStartAlpha()) ushr 24)
        assertEquals(0, argbWithAlpha(red, 0) ushr 24)
        assertEquals(green and 0x00FFFFFF, argbWithAlpha(green, activityHrDiffusionStartAlpha()) and 0x00FFFFFF)
        assertTrue(green != red)
    }
}

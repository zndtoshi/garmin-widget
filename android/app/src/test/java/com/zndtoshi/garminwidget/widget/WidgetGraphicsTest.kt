package com.zndtoshi.garminwidget.widget

import com.zndtoshi.garminwidget.data.ActivityHeartRatePoint
import com.zndtoshi.garminwidget.data.HrvTrendPoint
import com.zndtoshi.garminwidget.data.LastActivity
import com.zndtoshi.garminwidget.data.SleepStages
import com.zndtoshi.garminwidget.data.TimelinePoint
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

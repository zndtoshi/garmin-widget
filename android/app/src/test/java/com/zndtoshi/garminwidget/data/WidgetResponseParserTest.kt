package com.zndtoshi.garminwidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class WidgetResponseParserTest {
    @Test
    fun parsesCompletePayload() {
        val response = WidgetResponseParser.parse(COMPLETE_PAYLOAD)

        assertEquals(1, response.schemaVersion)
        assertEquals("2026-07-29", response.date)
        assertEquals(63, response.sleepScore)
        assertEquals(64, response.bodyBattery)
        assertEquals(RefreshStatus.SUCCESS, response.refreshStatus)
        assertEquals(5400, response.sleepStages?.deepSeconds)
        assertEquals(1, response.hrvTrend.size)
        assertEquals(2, response.bodyBatteryTimeline.size)
        assertEquals(1, response.stressTimeline.size)
        assertEquals("Morning Run", response.lastActivity?.name)
    }

    @Test
    fun preservesNullableMetrics() {
        val response = WidgetResponseParser.parse(
            COMPLETE_PAYLOAD.replace("\"restingHeartRate\":49", "\"restingHeartRate\":null"),
        )

        assertNull(response.restingHeartRate)
    }

    @Test
    fun ignoresMalformedAdditiveFields() {
        val json = JSONObject(COMPLETE_PAYLOAD)
        json.put("sleepStages", JSONObject().put("deepSeconds", "x"))
        json.put("hrvTrend", JSONArray().put(JSONObject().put("date", "bad").put("overnightAverage", "x")))
        json.put("bodyBatteryTimeline", JSONArray().put(JSONObject().put("timestamp", "bad").put("value", "x")))
        json.put("lastActivity", JSONObject().put("name", true))
        val response = WidgetResponseParser.parse(json.toString())

        assertNull(response.sleepStages?.deepSeconds)
        assertEquals(1, response.hrvTrend.size) // retains item but nullable fields
        assertEquals(0, response.bodyBatteryTimeline.size)
        assertNull(response.lastActivity?.name)
    }

    @Test
    fun enforces_defensive_bounds_and_filters_invalid_values() {
        val json = JSONObject(COMPLETE_PAYLOAD)
        val longTrend = JSONArray()
        repeat(36) {
            longTrend.put(JSONObject().put("date", "2026-07-${10 + it}").put("overnightAverage", it))
        }
        val longTimeline = JSONArray()
        repeat(220) {
            longTimeline.put(
                JSONObject()
                    .put(
                        "timestamp",
                        "2026-07-29T${(it / 60).toString().padStart(2, '0')}:" +
                            "${(it % 60).toString().padStart(2, '0')}:00Z",
                    )
                    .put("value", if (it % 3 == 0) 90 else 50),
            )
        }
        json.put("hrvTrend", longTrend)
        json.put("bodyBatteryTimeline", longTimeline)
        json.put("stressTimeline", longTimeline)
        json.put(
            "sleepStages",
            JSONObject().put("deepSeconds", -5).put("lightSeconds", 100),
        )
        json.put(
            "lastActivity",
            JSONObject()
                .put("distanceMeters", -1.0)
                .put("averageSpeedMetersPerSecond", "NaN")
                .put("trainingLoad", "Infinity")
                .put("durationSeconds", 1200),
        )
        val response = WidgetResponseParser.parse(json.toString())
        assertEquals(28, response.hrvTrend.size)
        assertTrue(response.bodyBatteryTimeline.size <= 192)
        assertTrue(response.stressTimeline.size <= 192)
        assertTrue(response.bodyBatteryTimeline.all { it.value in 0..100 })
        assertTrue(response.stressTimeline.all { it.value in 0..100 })
        assertEquals(100, response.sleepStages?.lightSeconds)
        assertNull(response.sleepStages?.deepSeconds)
        assertNull(response.lastActivity?.distanceMeters)
        assertNull(response.lastActivity?.averageSpeedMetersPerSecond)
        assertNull(response.lastActivity?.trainingLoad)
    }

    @Test
    fun parses_and_bounds_activity_heart_rate_timeline() {
        val json = JSONObject(COMPLETE_PAYLOAD)
        val activity = json.getJSONObject("lastActivity")
        val timeline = JSONArray()
            .put(JSONObject().put("elapsedSeconds", 30).put("heartRate", 138))
            .put(JSONObject().put("elapsedSeconds", 30).put("heartRate", 188))
            .put(JSONObject().put("elapsedSeconds", 0).put("heartRate", 112))
            .put(JSONObject().put("elapsedSeconds", 10).put("heartRate", 10))
            .put(JSONObject().put("elapsedSeconds", -1).put("heartRate", 120))
            .put(JSONObject().put("elapsedSeconds", 60).put("heartRate", 260))
        repeat(250) { i ->
            timeline.put(JSONObject().put("elapsedSeconds", 100 + i).put("heartRate", 140))
        }
        activity.put("heartRateTimeline", timeline)
        val response = WidgetResponseParser.parse(json.toString())
        val points = response.lastActivity?.heartRateTimeline.orEmpty()
        assertTrue(points.size <= 240)
        assertEquals(0, points.first().elapsedSeconds)
        assertEquals(112, points.first().heartRate)
        assertTrue(points.all { it.heartRate in 20..250 })
        assertTrue(points.zipWithNext().all { (a, b) -> a.elapsedSeconds <= b.elapsedSeconds })
        assertEquals(points.size, points.map { it.elapsedSeconds }.distinct().size)
        assertEquals(188, points.first { it.elapsedSeconds == 30 }.heartRate)
    }

    @Test
    fun deduplicates_daily_timeline_timestamps_deterministically() {
        val json = JSONObject(COMPLETE_PAYLOAD)
        val duplicateTimeline = JSONArray()
            .put(JSONObject().put("timestamp", "2026-07-29T01:00:00Z").put("value", 20))
            .put(JSONObject().put("timestamp", "2026-07-29T01:00:00Z").put("value", 55))
            .put(JSONObject().put("timestamp", "2026-07-29T02:00:00Z").put("value", 30))
        json.put("bodyBatteryTimeline", duplicateTimeline)
        json.put("stressTimeline", duplicateTimeline)

        val response = WidgetResponseParser.parse(json.toString())

        assertEquals(listOf(55, 30), response.bodyBatteryTimeline.map { it.value })
        assertEquals(listOf(55, 30), response.stressTimeline.map { it.value })
    }

    @Test
    fun rejectsUnknownSchema() {
        assertThrows(IllegalArgumentException::class.java) {
            WidgetResponseParser.parse(COMPLETE_PAYLOAD.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        }
    }

    private companion object {
        val COMPLETE_PAYLOAD = """
            {
              "schemaVersion":1,
              "date":"2026-07-29",
              "sleepScore":63,
              "sleepDurationSeconds":23340,
              "overnightHrv":43,
              "hrvStatus":"BALANCED",
              "bodyBattery":64,
              "restingHeartRate":49,
              "stress":20,
              "trainingReadiness":38,
              "garminSyncAt":"2026-07-29T03:46:34Z",
              "refreshedAt":"2026-07-29T06:37:17Z",
              "stale":false,
              "refreshStatus":"SUCCESS",
              "source":"garmin-connect-unofficial",
              "sleepStages":{"deepSeconds":5400,"lightSeconds":10800,"remSeconds":4200,"awakeSeconds":2220},
              "hrvTrend":[{"date":"2026-07-29","overnightAverage":43,"sevenDayAverage":42,"status":"BALANCED"}],
              "bodyBatteryTimeline":[{"timestamp":"2026-07-29T00:00:00Z","value":50},{"timestamp":"2026-07-29T04:00:00Z","value":64}],
              "stressTimeline":[{"timestamp":"2026-07-29T01:00:00Z","value":20}],
              "lastActivity":{"name":"Morning Run","typeKey":"running","startedAt":"2026-07-29T05:00:00Z","durationSeconds":2400}
            }
        """.trimIndent()
    }
}

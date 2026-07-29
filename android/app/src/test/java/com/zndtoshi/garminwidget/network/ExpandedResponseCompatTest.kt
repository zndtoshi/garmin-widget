package com.zndtoshi.garminwidget.network

import com.zndtoshi.garminwidget.data.RefreshStatus
import com.zndtoshi.garminwidget.data.WidgetResponseParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExpandedResponseCompatTest {

    private val expandedJson = """
    {
      "schemaVersion": 1,
      "date": "2026-07-28",
      "sleepScore": 84,
      "sleepDurationSeconds": 22620,
      "sleepStages": {
        "deepSeconds": 5400,
        "lightSeconds": 10800,
        "remSeconds": 4200,
        "awakeSeconds": 2220
      },
      "overnightHrv": 47,
      "hrvStatus": "BALANCED",
      "hrvTrend": [
        {"date": "2026-07-22", "overnightAverage": 40, "sevenDayAverage": 42, "status": "BALANCED"},
        {"date": "2026-07-28", "overnightAverage": 47, "sevenDayAverage": 46, "status": "BALANCED"}
      ],
      "bodyBattery": 72,
      "bodyBatteryTimeline": [
        {"timestamp": "2026-07-28T00:00:00Z", "value": 50},
        {"timestamp": "2026-07-28T06:00:00Z", "value": 72}
      ],
      "restingHeartRate": 49,
      "stress": 18,
      "stressTimeline": [
        {"timestamp": "2026-07-28T01:00:00Z", "value": 15}
      ],
      "trainingReadiness": 81,
      "lastActivity": {
        "name": "Morning Run",
        "typeKey": "running",
        "startedAt": "2026-07-28T05:00:00Z",
        "durationSeconds": 2400,
        "distanceMeters": 5120.5,
        "calories": 380,
        "averageHeartRate": 148,
        "maxHeartRate": 172,
        "elevationGainMeters": 45.0,
        "averageSpeedMetersPerSecond": 2.13,
        "aerobicTrainingEffect": 3.2,
        "anaerobicTrainingEffect": 1.1,
        "trainingLoad": 85.0
      },
      "garminSyncAt": "2026-07-28T05:35:00Z",
      "refreshedAt": "2026-07-28T05:36:04Z",
      "stale": false,
      "refreshStatus": "SUCCESS",
      "source": "garmin-connect-unofficial"
    }
    """.trimIndent()

    @Test
    fun `v0_1_0 parser reads original fields from expanded response`() {
        val response = WidgetResponseParser.parse(expandedJson)

        assertEquals("2026-07-28", response.date)
        assertEquals(84, response.sleepScore)
        assertEquals(22620, response.sleepDurationSeconds)
        assertEquals(47, response.overnightHrv)
        assertEquals("BALANCED", response.hrvStatus)
        assertEquals(72, response.bodyBattery)
        assertEquals(49, response.restingHeartRate)
        assertEquals(18, response.stress)
        assertEquals(81, response.trainingReadiness)
        assertEquals("2026-07-28T05:35:00Z", response.garminSyncAt)
        assertEquals("2026-07-28T05:36:04Z", response.refreshedAt)
        assertFalse(response.stale)
        assertEquals(RefreshStatus.SUCCESS, response.refreshStatus)
    }

    @Test
    fun `expanded fields do not corrupt original field parsing`() {
        val json = JSONObject(expandedJson)
        json.put("sleepStages", JSONObject().put("deepSeconds", 9999))
        json.put("unknownFutureField", "anything")

        val response = WidgetResponseParser.parse(json.toString())

        assertNotNull(response)
        assertEquals(84, response.sleepScore)
        assertEquals(72, response.bodyBattery)
    }

    @Test
    fun `null additive fields do not break parsing`() {
        val json = JSONObject(expandedJson)
        json.put("sleepStages", JSONObject.NULL)
        json.put("hrvTrend", JSONObject.NULL)
        json.put("bodyBatteryTimeline", JSONObject.NULL)
        json.put("stressTimeline", JSONObject.NULL)
        json.put("lastActivity", JSONObject.NULL)

        val response = WidgetResponseParser.parse(json.toString())

        assertEquals(84, response.sleepScore)
        assertEquals(47, response.overnightHrv)
    }
}

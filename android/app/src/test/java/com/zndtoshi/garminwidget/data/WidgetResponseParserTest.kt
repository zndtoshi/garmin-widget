package com.zndtoshi.garminwidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WidgetResponseParserTest {
    @Test
    fun parsesCompletePayload() {
        val response = WidgetResponseParser.parse(COMPLETE_PAYLOAD)

        assertEquals(1, response.schemaVersion)
        assertEquals("2026-07-29", response.date)
        assertEquals(63, response.sleepScore)
        assertEquals(64, response.bodyBattery)
        assertEquals(RefreshStatus.SUCCESS, response.refreshStatus)
    }

    @Test
    fun preservesNullableMetrics() {
        val response = WidgetResponseParser.parse(
            COMPLETE_PAYLOAD.replace("\"restingHeartRate\":49", "\"restingHeartRate\":null"),
        )

        assertNull(response.restingHeartRate)
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
              "source":"garmin-connect-unofficial"
            }
        """.trimIndent()
    }
}

package com.zndtoshi.garminwidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StoreCodecTest {
    @Test
    fun widget_store_decode_parses_expanded_payload() {
        val raw = """
            {
              "schemaVersion":1,
              "date":"2026-07-28",
              "sleepScore":83,
              "sleepStages":{"deepSeconds":5200,"lightSeconds":9600},
              "hrvTrend":[{"date":"2026-07-28","overnightAverage":48,"status":"BALANCED"}],
              "bodyBatteryTimeline":[{"timestamp":"2026-07-28T00:00:00Z","value":50}],
              "stressTimeline":[{"timestamp":"2026-07-28T01:00:00Z","value":20}],
              "lastActivity":{"name":"Run","typeKey":"running","durationSeconds":1500}
            }
        """.trimIndent()
        val state = WidgetStore.decodeState(LocalStatus.READY.name, raw)
        assertEquals(LocalStatus.READY, state.status)
        assertNotNull(state.data)
        assertEquals(83, state.data?.sleepScore)
        assertEquals(5200, state.data?.sleepStages?.deepSeconds)
        assertEquals(1, state.data?.hrvTrend?.size)
        assertEquals("Run", state.data?.lastActivity?.name)
    }

    @Test
    fun widget_store_decode_handles_missing_and_bad_status() {
        assertEquals(LocalStatus.NOT_CONFIGURED, WidgetStore.decodeState(null, null).status)
        assertEquals(LocalStatus.NOT_CONFIGURED, WidgetStore.decodeState("BAD", "{}").status)
    }

    @Test
    fun settings_opacity_clamp_helper_default_and_bounds() {
        assertEquals(88, SettingsStore.clampOpacity(null))
        assertEquals(0, SettingsStore.clampOpacity(-1))
        assertEquals(100, SettingsStore.clampOpacity(101))
        assertEquals(64, SettingsStore.clampOpacity(64))
    }
}

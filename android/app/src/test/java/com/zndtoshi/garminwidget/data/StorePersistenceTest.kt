package com.zndtoshi.garminwidget.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StorePersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(WidgetStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun widgetStore_saveSuccess_then_newInstance_read_retains_expanded_fields() {
        val payload = """
            {
              "schemaVersion":1,
              "date":"2026-07-28",
              "sleepScore":83,
              "sleepStages":{"deepSeconds":5200,"lightSeconds":9600,"remSeconds":3000,"awakeSeconds":600},
              "hrvTrend":[{"date":"2026-07-28","overnightAverage":48,"sevenDayAverage":46,"status":"BALANCED"}],
              "bodyBatteryTimeline":[{"timestamp":"2026-07-28T00:00:00Z","value":50}],
              "stressTimeline":[{"timestamp":"2026-07-28T01:00:00Z","value":20}],
              "lastActivity":{"name":"Morning Run","typeKey":"running","durationSeconds":1500}
            }
        """.trimIndent()

        WidgetStore(context).saveSuccess(payload)
        val state = WidgetStore(context).read()

        assertEquals(LocalStatus.READY, state.status)
        assertNotNull(state.data)
        assertEquals(83, state.data?.sleepScore)
        assertEquals(5200, state.data?.sleepStages?.deepSeconds)
        assertEquals(9600, state.data?.sleepStages?.lightSeconds)
        assertEquals(1, state.data?.hrvTrend?.size)
        assertEquals(48, state.data?.hrvTrend?.first()?.overnightAverage)
        assertEquals(1, state.data?.bodyBatteryTimeline?.size)
        assertEquals(1, state.data?.stressTimeline?.size)
        assertEquals("Morning Run", state.data?.lastActivity?.name)
        assertEquals("running", state.data?.lastActivity?.typeKey)
    }

    @Test
    fun cached_data_recovers_from_a_transient_network_status() {
        val store = WidgetStore(context)
        store.saveSuccess("{\"schemaVersion\":1,\"bodyBattery\":91}")
        store.saveFailure(LocalStatus.NETWORK_ERROR)

        store.recoverCachedTransientFailure()

        assertEquals(LocalStatus.READY, WidgetStore(context).read().status)
    }

    @Test
    fun lower_card_morning_event_dismissal_and_next_day_persist() {
        val store = WidgetStore(context)
        val incompleteMorning = """
            {"schemaVersion":1,"date":"2026-07-30","bodyBattery":90,
             "sleepScore":null,"sleepDurationSeconds":null}
        """.trimIndent()
        val completedMorning = """
            {"schemaVersion":1,"date":"2026-07-30","bodyBattery":90,
             "sleepScore":82,"sleepDurationSeconds":21600}
        """.trimIndent()
        val sameMorningUpdated = """
            {"schemaVersion":1,"date":"2026-07-30","bodyBattery":88,
             "sleepScore":82,"sleepDurationSeconds":21600}
        """.trimIndent()
        val nextMorning = """
            {"schemaVersion":1,"date":"2026-07-31","bodyBattery":93,
             "sleepScore":85,"sleepDurationSeconds":22500}
        """.trimIndent()

        assertTrue(store.saveSuccessAndReconcile(incompleteMorning))
        assertEquals(LowerCardKind.NONE, store.read().lowerCard.selected)

        assertTrue(store.saveSuccessAndReconcile(completedMorning))
        assertEquals(LowerCardKind.BODY_BATTERY, store.read().lowerCard.selected)

        store.dismissVisibleLowerCard()
        val dismissed = WidgetStore(context).read().lowerCard
        assertEquals(LowerCardKind.NONE, dismissed.selected)
        assertEquals("2026-07-30", dismissed.dismissedMorningIdentity)

        assertTrue(store.saveSuccessAndReconcile(sameMorningUpdated))
        assertEquals(
            LowerCardKind.NONE,
            resolveVisibleLowerCard(WidgetStore(context).read().data, WidgetStore(context).read().lowerCard),
        )

        assertTrue(store.saveSuccessAndReconcile(nextMorning))
        assertEquals(LowerCardKind.BODY_BATTERY, WidgetStore(context).read().lowerCard.selected)
    }

    @Test
    fun malformed_success_payload_preserves_cached_data_and_lower_card_state() {
        val store = WidgetStore(context)
        val valid = """
            {"schemaVersion":1,"date":"2026-07-30","bodyBattery":88,
             "sleepScore":82,"sleepDurationSeconds":21600}
        """.trimIndent()
        assertTrue(store.saveSuccessAndReconcile(valid))
        val before = store.read()

        assertFalse(store.saveSuccessAndReconcile("{not-json"))

        val after = WidgetStore(context).read()
        assertEquals(before.data, after.data)
        assertEquals(before.lowerCard, after.lowerCard)
        assertEquals(LocalStatus.READY, after.status)
    }

    @Test
    fun legacy_activity_dismissal_migrates_once_and_survives_new_store_instance() {
        val raw = """
            {"schemaVersion":1,"lastActivity":{"name":"Morning Run","typeKey":"running",
             "startedAt":"2026-07-29T17:34:35Z"}}
        """.trimIndent()
        val identity = "startedAt:2026-07-29T17:34:35Z"
        context.getSharedPreferences(WidgetStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(WidgetStore.KEY_RAW_JSON, raw)
            .putString(WidgetStore.KEY_STATUS, LocalStatus.READY.name)
            .putString(WidgetStore.KEY_DISMISSED_ACTIVITY_IDENTITY, identity)
            .commit()

        val migrated = WidgetStore(context).read().lowerCard
        assertEquals(LowerCardKind.NONE, migrated.selected)
        assertEquals(identity, migrated.dismissedActivityIdentity)

        val persisted = WidgetStore(context).read().lowerCard
        assertEquals(migrated, persisted)
    }

    @Test
    fun dismissed_activity_stays_hidden_until_a_new_activity_arrives() {
        val first = LastActivity(
            name = "Morning Run",
            typeKey = "running",
            startedAt = Instant.parse("2026-07-29T17:34:35Z"),
        )
        val next = first.copy(startedAt = Instant.parse("2026-07-30T05:10:00Z"))
        val store = WidgetStore(context)

        assertTrue(shouldShowActivity(first, store.dismissedActivityIdentity()))
        store.dismissActivity(activityDismissalIdentity(first))

        val persistedDismissal = WidgetStore(context).dismissedActivityIdentity()
        assertEquals(activityDismissalIdentity(first), persistedDismissal)
        assertFalse(shouldShowActivity(first, persistedDismissal))
        assertTrue(shouldShowActivity(next, persistedDismissal))
        assertFalse(shouldShowActivity(null, persistedDismissal))
    }

    @Test
    fun activity_identity_has_stable_fallback_when_start_time_is_missing() {
        val activity = LastActivity(
            name = "Indoor Ride",
            typeKey = "cycling",
            durationSeconds = 1800,
            distanceMeters = 12000.0,
            maxHeartRate = 176,
        )

        assertEquals(
            "Indoor Ride|cycling|1800|12000.0|176",
            activityDismissalIdentity(activity),
        )
    }

    @Test
    fun settingsStore_opacity_defaults_persists_and_clamps() {
        val store = SettingsStore(context)
        assertEquals(88, store.widgetOpacityPercent())

        store.saveWidgetOpacityPercent(64)
        assertEquals(64, SettingsStore(context).widgetOpacityPercent())

        store.saveWidgetOpacityPercent(150)
        assertEquals(100, SettingsStore(context).widgetOpacityPercent())

        // Corrupt/out-of-range persisted raw value is clamped on read.
        context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(SettingsStore.KEY_WIDGET_OPACITY_PERCENT, -20)
            .commit()
        assertEquals(0, SettingsStore(context).widgetOpacityPercent())
    }

    @Test
    fun settingsStore_corrupt_opacity_string_returns_default() {
        context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SettingsStore.KEY_WIDGET_OPACITY_PERCENT, "not-an-int")
            .commit()
        assertEquals(88, SettingsStore(context).widgetOpacityPercent())
    }

    @Test
    fun settings_blank_token_does_not_remove_existing_encrypted_token() {
        val prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SettingsStore.KEY_ENCRYPTED_TOKEN, "existing-encrypted-token")
            .putString(SettingsStore.KEY_BACKEND_URL, "https://old.example")
            .commit()

        SettingsStore.persistSettings(
            prefs = prefs,
            backendUrl = "https://garmin.zndtoshi.com/",
            encryptedToken = null,
            replaceToken = false,
        )

        assertTrue(prefs.contains(SettingsStore.KEY_ENCRYPTED_TOKEN))
        assertEquals("existing-encrypted-token", prefs.getString(SettingsStore.KEY_ENCRYPTED_TOKEN, null))
        assertEquals("https://garmin.zndtoshi.com", prefs.getString(SettingsStore.KEY_BACKEND_URL, null))
        assertFalse(SettingsStore(context).backendUrl().endsWith("/"))
    }
}

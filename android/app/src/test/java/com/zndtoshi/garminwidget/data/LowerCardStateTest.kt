package com.zndtoshi.garminwidget.data

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowerCardStateTest {

    private fun response(
        date: String? = "2026-07-30",
        sleepScore: Int? = 82,
        sleepDurationSeconds: Int? = 21600,
        bodyBattery: Int? = 88,
        activityStartedAt: String? = "2026-07-29T17:00:00Z",
        activityName: String? = "Ride",
        hrPoints: Int = 0,
        speedPoints: Int = 0,
    ): WidgetResponse {
        val activity = if (activityStartedAt == null && activityName == null) {
            null
        } else {
            LastActivity(
                name = activityName,
                typeKey = "cycling",
                startedAt = activityStartedAt?.let { Instant.parse(it) },
                heartRateTimeline = List(hrPoints) {
                    ActivityHeartRatePoint(elapsedSeconds = it * 30, heartRate = 120 + it)
                },
                speedTimeline = List(speedPoints) {
                    ActivitySpeedPoint(elapsedSeconds = it * 30, speedMetersPerSecond = 2.0 + it)
                },
            )
        }
        return WidgetResponse(
            date = date,
            sleepScore = sleepScore,
            sleepDurationSeconds = sleepDurationSeconds,
            bodyBattery = bodyBattery,
            lastActivity = activity,
        )
    }

    @Test
    fun morning_identity_requires_date_bb_and_completed_sleep() {
        assertNull(morningIdentity(response(sleepScore = null, sleepDurationSeconds = 0)))
        assertNull(morningIdentity(response(bodyBattery = null)))
        assertNull(morningIdentity(response(date = null)))
        assertNull(morningIdentity(response(date = "not-a-date")))
        assertEquals("2026-07-30", morningIdentity(response(sleepScore = null, sleepDurationSeconds = 100)))
        assertEquals("2026-07-30", morningIdentity(response(sleepScore = 70, sleepDurationSeconds = 0)))
    }

    @Test
    fun midnight_rollover_without_sleep_is_not_a_morning_event() {
        val previous = response(date = "2026-07-29", sleepScore = 80)
        val incoming = response(date = "2026-07-30", sleepScore = null, sleepDurationSeconds = 0)
        val next = reconcileLowerCardOnSuccess(previous, incoming, LowerCardState())
        assertEquals(LowerCardKind.NONE, next.selected)
        assertNull(morningIdentity(incoming))
    }

    @Test
    fun same_date_sleep_completion_is_one_morning_event() {
        val previous = response(date = "2026-07-30", sleepScore = null, sleepDurationSeconds = 0)
        val incoming = response(date = "2026-07-30", sleepScore = 82)
        val next = reconcileLowerCardOnSuccess(previous, incoming, LowerCardState())
        assertEquals(LowerCardKind.BODY_BATTERY, next.selected)
        val again = reconcileLowerCardOnSuccess(incoming, incoming, next)
        assertEquals(LowerCardKind.BODY_BATTERY, again.selected)
    }

    @Test
    fun dismissed_morning_stays_hidden_when_bb_value_changes() {
        val day = response(bodyBattery = 90)
        val dismissed = dismissLowerCard(
            day,
            LowerCardState(selected = LowerCardKind.BODY_BATTERY),
        )
        assertEquals(LowerCardKind.NONE, dismissed.selected)
        assertEquals("2026-07-30", dismissed.dismissedMorningIdentity)
        val updated = response(bodyBattery = 88)
        val after = reconcileLowerCardOnSuccess(day, updated, dismissed)
        assertEquals(LowerCardKind.NONE, resolveVisibleLowerCard(updated, after))
    }

    @Test
    fun new_morning_date_reopens_body_battery() {
        val dismissed = LowerCardState(
            selected = LowerCardKind.NONE,
            dismissedMorningIdentity = "2026-07-29",
        )
        val incoming = response(date = "2026-07-30")
        val next = reconcileLowerCardOnSuccess(
            response(date = "2026-07-29"),
            incoming,
            dismissed,
        )
        assertEquals(LowerCardKind.BODY_BATTERY, next.selected)
        assertTrue(isMorningEligible(incoming, next))
    }

    @Test
    fun richer_same_activity_timeline_does_not_reopen() {
        val first = response(hrPoints = 2)
        val richer = response(hrPoints = 10, speedPoints = 10)
        assertEquals(activityIdentity(first.lastActivity), activityIdentity(richer.lastActivity))
        val dismissed = LowerCardState(
            selected = LowerCardKind.NONE,
            dismissedActivityIdentity = activityIdentity(first.lastActivity),
        )
        val next = reconcileLowerCardOnSuccess(first, richer, dismissed)
        assertEquals(LowerCardKind.NONE, next.selected)
        assertFalse(isActivityEligible(richer, next))
    }

    @Test
    fun new_activity_wins_over_visible_body_battery_and_coincident_morning() {
        val previous = response(
            date = "2026-07-29",
            activityStartedAt = "2026-07-28T10:00:00Z",
            sleepScore = null,
            sleepDurationSeconds = 0,
        )
        val incoming = response(
            date = "2026-07-30",
            activityStartedAt = "2026-07-30T06:00:00Z",
            sleepScore = 80,
        )
        val state = LowerCardState(selected = LowerCardKind.BODY_BATTERY)
        val next = reconcileLowerCardOnSuccess(previous, incoming, state)
        assertEquals(LowerCardKind.ACTIVITY, next.selected)
    }

    @Test
    fun dismiss_hides_both_available_lower_cards_with_one_press() {
        val data = response()
        val afterDismiss = dismissLowerCard(
            data,
            LowerCardState(selected = LowerCardKind.ACTIVITY),
        )
        assertEquals(LowerCardKind.NONE, resolveVisibleLowerCard(data, afterDismiss))
        assertFalse(isMorningEligible(data, afterDismiss))
        assertFalse(isActivityEligible(data, afterDismiss))
        assertEquals(morningIdentity(data), afterDismiss.dismissedMorningIdentity)
        assertEquals(activityIdentity(data.lastActivity), afterDismiss.dismissedActivityIdentity)

        val dismissedFromBodyBattery = dismissLowerCard(
            data,
            LowerCardState(selected = LowerCardKind.BODY_BATTERY),
        )
        assertFalse(isMorningEligible(data, dismissedFromBodyBattery))
        assertFalse(isActivityEligible(data, dismissedFromBodyBattery))
    }

    @Test
    fun toggle_manually_reopens_dismissed_alternate_but_skips_missing_data() {
        val data = response()
        val dismissedActivity = LowerCardState(
            selected = LowerCardKind.BODY_BATTERY,
            dismissedActivityIdentity = activityIdentity(data.lastActivity),
        )
        val reopenedActivity = toggleLowerCard(data, dismissedActivity)
        assertEquals(LowerCardKind.ACTIVITY, reopenedActivity.selected)
        assertNull(reopenedActivity.dismissedActivityIdentity)

        val dismissedMorning = LowerCardState(
            selected = LowerCardKind.ACTIVITY,
            dismissedMorningIdentity = morningIdentity(data),
        )
        val reopenedMorning = toggleLowerCard(data, dismissedMorning)
        assertEquals(LowerCardKind.BODY_BATTERY, reopenedMorning.selected)
        assertNull(reopenedMorning.dismissedMorningIdentity)

        val noActivity = response(activityStartedAt = null, activityName = null)
        val fromBb = LowerCardState(selected = LowerCardKind.BODY_BATTERY)
        assertEquals(fromBb, toggleLowerCard(noActivity, fromBb))

        val toggled = toggleLowerCard(data, LowerCardState(selected = LowerCardKind.BODY_BATTERY))
        assertEquals(LowerCardKind.ACTIVITY, toggled.selected)
    }

    @Test
    fun migration_preserves_old_dismissed_activity_without_false_event() {
        val cached = response(activityStartedAt = "2026-07-29T17:00:00Z")
        val identity = activityIdentity(cached.lastActivity)
        val zone = ZoneId.of("UTC")
        val migrated = migrateLowerCardState(cached, identity, zone)
        assertEquals(LowerCardKind.BODY_BATTERY, migrated.selected)
        assertEquals(identity, migrated.dismissedActivityIdentity)

        val sameDayActivity = response(
            activityStartedAt = "2026-07-30T17:00:00Z",
        )
        val visible = migrateLowerCardState(
            sameDayActivity,
            dismissedActivityIdentity = null,
            zoneId = zone,
        )
        assertEquals(LowerCardKind.ACTIVITY, visible.selected)

        val dismissedSameDay = migrateLowerCardState(
            sameDayActivity,
            activityIdentity(sameDayActivity.lastActivity),
            zone,
        )
        assertEquals(LowerCardKind.NONE, dismissedSameDay.selected)
    }
}

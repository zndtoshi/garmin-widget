package com.zndtoshi.garminwidget.data

import java.time.Instant
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
    fun dismiss_leaves_row_empty_without_revealing_alternate() {
        val data = response()
        val afterDismiss = dismissLowerCard(
            data,
            LowerCardState(selected = LowerCardKind.ACTIVITY),
        )
        assertEquals(LowerCardKind.NONE, resolveVisibleLowerCard(data, afterDismiss))
        assertTrue(isMorningEligible(data, afterDismiss))
    }

    @Test
    fun toggle_skips_dismissed_or_missing_alternate() {
        val data = response()
        val dismissedActivity = LowerCardState(
            selected = LowerCardKind.BODY_BATTERY,
            dismissedActivityIdentity = activityIdentity(data.lastActivity),
        )
        assertEquals(dismissedActivity, toggleLowerCard(data, dismissedActivity))

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
        val migrated = migrateLowerCardState(cached, identity)
        assertEquals(LowerCardKind.NONE, migrated.selected)
        assertEquals(identity, migrated.dismissedActivityIdentity)

        val visible = migrateLowerCardState(cached, dismissedActivityIdentity = null)
        assertEquals(LowerCardKind.ACTIVITY, visible.selected)
    }
}

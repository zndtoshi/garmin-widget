package com.zndtoshi.garminwidget.data

import java.time.LocalDate
import java.time.ZoneId

/**
 * Event-driven lower-card selection (full-width Body Battery vs latest Activity).
 * Pure helpers — no Android framework dependencies — so JVM tests can cover edge cases.
 */
enum class LowerCardKind {
    NONE,
    BODY_BATTERY,
    ACTIVITY,
}

data class LowerCardState(
    val selected: LowerCardKind = LowerCardKind.NONE,
    val dismissedMorningIdentity: String? = null,
    val dismissedActivityIdentity: String? = null,
)

/** Payload-date morning identity when BB + completed-sleep evidence exist. */
internal fun morningIdentity(response: WidgetResponse?): String? {
    if (response == null) return null
    val date = response.date?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // Validate date shape without throwing on legacy junk.
    if (runCatching { java.time.LocalDate.parse(date) }.getOrNull() == null) return null
    val hasBodyBattery = response.bodyBattery != null || response.bodyBatteryTimeline.isNotEmpty()
    val hasCompletedSleep =
        response.sleepScore != null || (response.sleepDurationSeconds ?: 0) > 0
    if (!hasBodyBattery || !hasCompletedSleep) return null
    return date
}

internal fun activityIdentity(activity: LastActivity?): String? =
    activity?.let { activityDismissalIdentity(it) }

internal fun isMorningEligible(response: WidgetResponse?, state: LowerCardState): Boolean {
    val identity = morningIdentity(response) ?: return false
    return identity != state.dismissedMorningIdentity
}

internal fun isActivityEligible(response: WidgetResponse?, state: LowerCardState): Boolean {
    val identity = activityIdentity(response?.lastActivity) ?: return false
    return identity != state.dismissedActivityIdentity
}

/** Visible card after applying dismissals to the selected kind. */
internal fun resolveVisibleLowerCard(
    response: WidgetResponse?,
    state: LowerCardState,
): LowerCardKind =
    when (state.selected) {
        LowerCardKind.BODY_BATTERY ->
            if (isMorningEligible(response, state)) LowerCardKind.BODY_BATTERY else LowerCardKind.NONE
        LowerCardKind.ACTIVITY ->
            if (isActivityEligible(response, state)) LowerCardKind.ACTIVITY else LowerCardKind.NONE
        LowerCardKind.NONE -> LowerCardKind.NONE
    }

/**
 * Reconcile after a successful refresh. Compares previously cached response to incoming.
 * Activity events win over coincident morning events. Unchanged payloads do not reopen cards.
 */
internal fun reconcileLowerCardOnSuccess(
    previous: WidgetResponse?,
    incoming: WidgetResponse,
    state: LowerCardState,
): LowerCardState {
    val prevMorning = morningIdentity(previous)
    val newMorning = morningIdentity(incoming)
    val morningEvent = newMorning != null && newMorning != prevMorning

    val prevActivity = activityIdentity(previous?.lastActivity)
    val newActivity = activityIdentity(incoming.lastActivity)
    val activityEvent = newActivity != null && newActivity != prevActivity

    return when {
        activityEvent -> state.copy(selected = LowerCardKind.ACTIVITY)
        morningEvent -> state.copy(selected = LowerCardKind.BODY_BATTERY)
        else -> state
    }
}

internal fun toggleLowerCard(
    response: WidgetResponse?,
    state: LowerCardState,
): LowerCardState {
    val visible = resolveVisibleLowerCard(response, state)
    return when (visible) {
        LowerCardKind.BODY_BATTERY -> {
            if (activityIdentity(response?.lastActivity) == null) state else state.copy(
                selected = LowerCardKind.ACTIVITY,
                // Dismissal suppresses automatic reopening, not an explicit user cycle.
                dismissedActivityIdentity = null,
            )
        }
        LowerCardKind.ACTIVITY -> {
            if (morningIdentity(response) == null) state else state.copy(
                selected = LowerCardKind.BODY_BATTERY,
                // Dismissal suppresses automatic reopening, not an explicit user cycle.
                dismissedMorningIdentity = null,
            )
        }
        LowerCardKind.NONE -> state
    }
}

internal fun dismissLowerCard(
    response: WidgetResponse?,
    state: LowerCardState,
): LowerCardState {
    if (resolveVisibleLowerCard(response, state) == LowerCardKind.NONE) return state
    return state.copy(
        selected = LowerCardKind.NONE,
        dismissedMorningIdentity = morningIdentity(response) ?: state.dismissedMorningIdentity,
        dismissedActivityIdentity = activityIdentity(response?.lastActivity) ?: state.dismissedActivityIdentity,
    )
}

/**
 * Baseline when upgrading from the activity-only dismiss key with an existing cache.
 * Does not treat the cached payload as a brand-new event.
 */
internal fun migrateLowerCardState(
    cached: WidgetResponse?,
    dismissedActivityIdentity: String?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): LowerCardState {
    val activity = cached?.lastActivity
    val currentActivityIdentity = activityIdentity(activity)
    val morningDate = morningIdentity(cached)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val activityDate = activity?.startedAt?.atZone(zoneId)?.toLocalDate()
    val morningIsNewer = morningDate != null &&
        (activity == null || (activityDate != null && activityDate.isBefore(morningDate)))
    val selected = when {
        morningIsNewer -> LowerCardKind.BODY_BATTERY
        activity != null && currentActivityIdentity != dismissedActivityIdentity -> LowerCardKind.ACTIVITY
        else -> LowerCardKind.NONE
    }
    return LowerCardState(
        selected = selected,
        dismissedMorningIdentity = null,
        dismissedActivityIdentity = dismissedActivityIdentity,
    )
}

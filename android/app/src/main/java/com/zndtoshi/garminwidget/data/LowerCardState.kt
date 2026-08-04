package com.zndtoshi.garminwidget.data

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
    val alternate = when (visible) {
        LowerCardKind.BODY_BATTERY ->
            if (isActivityEligible(response, state)) LowerCardKind.ACTIVITY else null
        LowerCardKind.ACTIVITY ->
            if (isMorningEligible(response, state)) LowerCardKind.BODY_BATTERY else null
        LowerCardKind.NONE -> null
    } ?: return state
    return state.copy(selected = alternate)
}

internal fun dismissLowerCard(
    response: WidgetResponse?,
    state: LowerCardState,
): LowerCardState {
    return when (resolveVisibleLowerCard(response, state)) {
        LowerCardKind.BODY_BATTERY -> state.copy(
            selected = LowerCardKind.NONE,
            dismissedMorningIdentity = morningIdentity(response),
        )
        LowerCardKind.ACTIVITY -> state.copy(
            selected = LowerCardKind.NONE,
            dismissedActivityIdentity = activityIdentity(response?.lastActivity),
        )
        LowerCardKind.NONE -> state
    }
}

/**
 * Baseline when upgrading from the activity-only dismiss key with an existing cache.
 * Does not treat the cached payload as a brand-new event.
 */
internal fun migrateLowerCardState(
    cached: WidgetResponse?,
    dismissedActivityIdentity: String?,
): LowerCardState {
    val selected =
        if (cached?.lastActivity != null &&
            activityIdentity(cached.lastActivity) != dismissedActivityIdentity
        ) {
            LowerCardKind.ACTIVITY
        } else {
            LowerCardKind.NONE
        }
    return LowerCardState(
        selected = selected,
        dismissedMorningIdentity = null,
        dismissedActivityIdentity = dismissedActivityIdentity,
    )
}

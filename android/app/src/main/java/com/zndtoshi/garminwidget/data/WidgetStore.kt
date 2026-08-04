package com.zndtoshi.garminwidget.data

import android.content.Context

class WidgetStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class State(
        val data: WidgetResponse?,
        val status: LocalStatus,
        val lowerCard: LowerCardState = LowerCardState(),
    )

    fun read(): State = synchronized(STATE_LOCK) {
        val statusName = prefs.getString(KEY_STATUS, null)
        val rawJson = prefs.getString(KEY_RAW_JSON, null)
        val decoded = decodeState(statusName, rawJson)
        decoded.copy(lowerCard = readLowerCardState(decoded.data))
    }

    /**
     * Atomically compare previous cache → incoming payload, persist JSON + lower-card state.
     * Malformed payloads leave prior cache and dismissals untouched.
     */
    fun saveSuccessAndReconcile(rawJson: String): Boolean = synchronized(STATE_LOCK) {
        val incoming = try {
            WidgetResponseParser.parse(rawJson)
        } catch (_: Exception) {
            return@synchronized false
        }
        val previousRaw = prefs.getString(KEY_RAW_JSON, null)
        val previous = previousRaw?.let {
            try {
                WidgetResponseParser.parse(it)
            } catch (_: Exception) {
                null
            }
        }
        val currentLower = readLowerCardState(previous)
        val nextLower = reconcileLowerCardOnSuccess(previous, incoming, currentLower)
        prefs.edit()
            .putString(KEY_RAW_JSON, rawJson)
            .putString(KEY_STATUS, LocalStatus.READY.name)
            .putString(KEY_LOWER_CARD_SELECTED, nextLower.selected.name)
            .putString(KEY_DISMISSED_MORNING_IDENTITY, nextLower.dismissedMorningIdentity)
            .putString(KEY_DISMISSED_ACTIVITY_IDENTITY, nextLower.dismissedActivityIdentity)
            .putBoolean(KEY_LOWER_CARD_MIGRATED, true)
            .putInt(KEY_LOWER_CARD_STATE_VERSION, LOWER_CARD_STATE_VERSION)
            .commit()
        true
    }

    /** @deprecated Prefer [saveSuccessAndReconcile] so lower-card events are detected. */
    fun saveSuccess(rawJson: String) {
        saveSuccessAndReconcile(rawJson)
    }

    fun saveFailure(status: LocalStatus) {
        prefs.edit()
            .putString(KEY_STATUS, status.name)
            .apply()
    }

    fun recoverCachedTransientFailure() {
        val state = read()
        if (state.status == LocalStatus.NETWORK_ERROR && state.data != null) {
            prefs.edit()
                .putString(KEY_STATUS, LocalStatus.READY.name)
                .commit()
        }
    }

    fun markRefreshing() {
        prefs.edit()
            .putString(KEY_STATUS, LocalStatus.REFRESHING.name)
            .apply()
    }

    fun lowerCardState(): LowerCardState = read().lowerCard

    fun dismissedActivityIdentity(): String? =
        prefs.getString(KEY_DISMISSED_ACTIVITY_IDENTITY, null)

    fun dismissActivity(identity: String) {
        if (identity.isBlank()) return
        prefs.edit()
            .putString(KEY_DISMISSED_ACTIVITY_IDENTITY, identity)
            .putString(KEY_LOWER_CARD_SELECTED, LowerCardKind.NONE.name)
            .putBoolean(KEY_LOWER_CARD_MIGRATED, true)
            .putInt(KEY_LOWER_CARD_STATE_VERSION, LOWER_CARD_STATE_VERSION)
            .commit()
    }

    fun applyLowerCardState(state: LowerCardState) = synchronized(STATE_LOCK) {
        prefs.edit()
            .putString(KEY_LOWER_CARD_SELECTED, state.selected.name)
            .putString(KEY_DISMISSED_MORNING_IDENTITY, state.dismissedMorningIdentity)
            .putString(KEY_DISMISSED_ACTIVITY_IDENTITY, state.dismissedActivityIdentity)
            .putBoolean(KEY_LOWER_CARD_MIGRATED, true)
            .putInt(KEY_LOWER_CARD_STATE_VERSION, LOWER_CARD_STATE_VERSION)
            .commit()
    }

    fun toggleVisibleLowerCard(): LowerCardState = synchronized(STATE_LOCK) {
        val current = read()
        val next = toggleLowerCard(current.data, current.lowerCard)
        applyLowerCardState(next)
        next
    }

    fun dismissVisibleLowerCard(): LowerCardState = synchronized(STATE_LOCK) {
        val current = read()
        val next = dismissLowerCard(current.data, current.lowerCard)
        applyLowerCardState(next)
        next
    }

    /**
     * Opening Garmin Connect is an explicit request to sync current health data.
     * Restore today's Body Battery card even if the user previously dismissed it;
     * the delayed cloud refresh will then replace its cached values in place.
     */
    fun restoreBodyBatteryCard(): LowerCardState = synchronized(STATE_LOCK) {
        val current = read()
        val next = current.lowerCard.copy(
            selected = LowerCardKind.BODY_BATTERY,
            dismissedMorningIdentity = null,
        )
        applyLowerCardState(next)
        next
    }

    private fun readLowerCardState(cached: WidgetResponse?): LowerCardState {
        val stateVersion = prefs.getInt(KEY_LOWER_CARD_STATE_VERSION, 0)
        val dismissedActivity = prefs.getString(KEY_DISMISSED_ACTIVITY_IDENTITY, null)
        if (stateVersion < LOWER_CARD_STATE_VERSION) {
            val baseline = migrateLowerCardState(cached, dismissedActivity)
            applyLowerCardState(baseline)
            return baseline
        }
        val selected = prefs.getString(KEY_LOWER_CARD_SELECTED, null)?.let {
            runCatching { LowerCardKind.valueOf(it) }.getOrNull()
        } ?: LowerCardKind.NONE
        return LowerCardState(
            selected = selected,
            dismissedMorningIdentity = prefs.getString(KEY_DISMISSED_MORNING_IDENTITY, null),
            dismissedActivityIdentity = dismissedActivity,
        )
    }

    companion object {
        const val PREFS_NAME = "garmin_widget_data"
        const val KEY_RAW_JSON = "raw_json"
        const val KEY_STATUS = "status"
        const val KEY_DISMISSED_ACTIVITY_IDENTITY = "dismissed_activity_identity"
        const val KEY_DISMISSED_MORNING_IDENTITY = "dismissed_morning_identity"
        const val KEY_LOWER_CARD_SELECTED = "lower_card_selected"
        const val KEY_LOWER_CARD_MIGRATED = "lower_card_migrated"
        const val KEY_LOWER_CARD_STATE_VERSION = "lower_card_state_version"

        private const val LOWER_CARD_STATE_VERSION = 2

        /** Shared across store instances used by workers and Glance actions in this process. */
        private val STATE_LOCK = Any()

        internal fun decodeState(statusName: String?, rawJson: String?): State {
            val status = try {
                if (statusName == null) LocalStatus.NOT_CONFIGURED else LocalStatus.valueOf(statusName)
            } catch (_: IllegalArgumentException) {
                LocalStatus.NOT_CONFIGURED
            }
            val data = rawJson?.let {
                try {
                    WidgetResponseParser.parse(it)
                } catch (_: Exception) {
                    null
                }
            }
            return State(data, status)
        }
    }
}

internal fun activityDismissalIdentity(activity: LastActivity): String {
    activity.startedAt?.let { return "startedAt:$it" }
    return listOf(
        activity.name.orEmpty(),
        activity.typeKey.orEmpty(),
        activity.durationSeconds?.toString().orEmpty(),
        activity.distanceMeters?.toString().orEmpty(),
        activity.maxHeartRate?.toString().orEmpty(),
    ).joinToString(separator = "|")
}

internal fun shouldShowActivity(activity: LastActivity?, dismissedIdentity: String?): Boolean =
    activity != null && activityDismissalIdentity(activity) != dismissedIdentity

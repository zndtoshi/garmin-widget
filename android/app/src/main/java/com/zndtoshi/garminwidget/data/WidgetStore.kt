package com.zndtoshi.garminwidget.data

import android.content.Context

class WidgetStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class State(
        val data: WidgetResponse?,
        val status: LocalStatus,
    )

    fun read(): State {
        val statusName = prefs.getString(KEY_STATUS, null)
        val rawJson = prefs.getString(KEY_RAW_JSON, null)
        return decodeState(statusName, rawJson)
    }

    fun saveSuccess(rawJson: String) {
        prefs.edit()
            .putString(KEY_RAW_JSON, rawJson)
            .putString(KEY_STATUS, LocalStatus.READY.name)
            .commit()
    }

    fun saveFailure(status: LocalStatus) {
        prefs.edit()
            .putString(KEY_STATUS, status.name)
            .apply()
    }

    fun markRefreshing() {
        prefs.edit()
            .putString(KEY_STATUS, LocalStatus.REFRESHING.name)
            .apply()
    }

    fun dismissedActivityIdentity(): String? =
        prefs.getString(KEY_DISMISSED_ACTIVITY_IDENTITY, null)

    fun dismissActivity(identity: String) {
        if (identity.isBlank()) return
        prefs.edit()
            .putString(KEY_DISMISSED_ACTIVITY_IDENTITY, identity)
            .commit()
    }

    companion object {
        const val PREFS_NAME = "garmin_widget_data"
        const val KEY_RAW_JSON = "raw_json"
        const val KEY_STATUS = "status"
        const val KEY_DISMISSED_ACTIVITY_IDENTITY = "dismissed_activity_identity"

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

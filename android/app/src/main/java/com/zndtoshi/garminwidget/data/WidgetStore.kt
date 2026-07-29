package com.zndtoshi.garminwidget.data

import android.content.Context
import org.json.JSONObject

class WidgetStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class State(
        val data: WidgetResponse?,
        val status: LocalStatus,
    )

    fun read(): State {
        val statusName = prefs.getString(KEY_STATUS, null) ?: return State(null, LocalStatus.NOT_CONFIGURED)
        val status = try {
            LocalStatus.valueOf(statusName)
        } catch (_: IllegalArgumentException) {
            LocalStatus.NOT_CONFIGURED
        }
        val rawJson = prefs.getString(KEY_RAW_JSON, null)
        val data = rawJson?.let {
            try {
                WidgetResponse.fromJson(JSONObject(it))
            } catch (_: Exception) {
                null
            }
        }
        return State(data, status)
    }

    fun saveSuccess(rawJson: String) {
        prefs.edit()
            .putString(KEY_RAW_JSON, rawJson)
            .putString(KEY_STATUS, LocalStatus.READY.name)
            .apply()
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

    private companion object {
        const val PREFS_NAME = "garmin_widget_data"
        const val KEY_RAW_JSON = "raw_json"
        const val KEY_STATUS = "status"
    }
}

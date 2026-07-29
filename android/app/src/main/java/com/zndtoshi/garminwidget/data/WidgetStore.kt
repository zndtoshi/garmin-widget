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

    companion object {
        const val PREFS_NAME = "garmin_widget_data"
        const val KEY_RAW_JSON = "raw_json"
        const val KEY_STATUS = "status"

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

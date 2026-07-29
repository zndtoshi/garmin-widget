package com.zndtoshi.garminwidget.data

import org.json.JSONObject

enum class RefreshStatus {
    SUCCESS, CACHE_HIT, COOLDOWN, UPSTREAM_UNAVAILABLE, NO_DATA;

    companion object {
        fun fromString(value: String): RefreshStatus = try {
            valueOf(value)
        } catch (_: IllegalArgumentException) {
            SUCCESS
        }
    }
}

data class WidgetResponse(
    val schemaVersion: Int = 1,
    val date: String? = null,
    val sleepScore: Int? = null,
    val sleepDurationSeconds: Int? = null,
    val overnightHrv: Int? = null,
    val hrvStatus: String? = null,
    val bodyBattery: Int? = null,
    val restingHeartRate: Int? = null,
    val stress: Int? = null,
    val trainingReadiness: Int? = null,
    val garminSyncAt: String? = null,
    val refreshedAt: String? = null,
    val stale: Boolean = false,
    val refreshStatus: RefreshStatus = RefreshStatus.SUCCESS,
) {
    companion object {
        fun fromJson(json: JSONObject): WidgetResponse {
            return WidgetResponse(
                schemaVersion = json.optInt("schemaVersion", 1),
                date = json.optStringOrNull("date"),
                sleepScore = json.optIntOrNull("sleepScore"),
                sleepDurationSeconds = json.optIntOrNull("sleepDurationSeconds"),
                overnightHrv = json.optIntOrNull("overnightHrv"),
                hrvStatus = json.optStringOrNull("hrvStatus"),
                bodyBattery = json.optIntOrNull("bodyBattery"),
                restingHeartRate = json.optIntOrNull("restingHeartRate"),
                stress = json.optIntOrNull("stress"),
                trainingReadiness = json.optIntOrNull("trainingReadiness"),
                garminSyncAt = json.optStringOrNull("garminSyncAt"),
                refreshedAt = json.optStringOrNull("refreshedAt"),
                stale = json.optBoolean("stale", false),
                refreshStatus = RefreshStatus.fromString(
                    json.optString("refreshStatus", "SUCCESS"),
                ),
            )
        }

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) getInt(key) else null

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val value = getString(key)
            return value.ifEmpty { null }
        }
    }
}

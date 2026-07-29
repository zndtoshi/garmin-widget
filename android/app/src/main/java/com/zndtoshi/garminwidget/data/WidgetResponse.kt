package com.zndtoshi.garminwidget.data

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.math.abs
import org.json.JSONObject
import org.json.JSONTokener

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

data class SleepStages(
    val deepSeconds: Int? = null,
    val lightSeconds: Int? = null,
    val remSeconds: Int? = null,
    val awakeSeconds: Int? = null,
)

data class HrvTrendPoint(
    val date: LocalDate? = null,
    val overnightAverage: Int? = null,
    val sevenDayAverage: Int? = null,
    val status: String? = null,
)

data class TimelinePoint(
    val timestamp: Instant,
    val value: Int,
)

data class ActivityHeartRatePoint(
    val elapsedSeconds: Int,
    val heartRate: Int,
)

data class LastActivity(
    val name: String? = null,
    val typeKey: String? = null,
    val startedAt: Instant? = null,
    val durationSeconds: Int? = null,
    val movingDurationSeconds: Int? = null,
    val distanceMeters: Double? = null,
    val calories: Int? = null,
    val averageHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val elevationGainMeters: Double? = null,
    val averageSpeedMetersPerSecond: Double? = null,
    val aerobicTrainingEffect: Double? = null,
    val anaerobicTrainingEffect: Double? = null,
    val trainingLoad: Double? = null,
    val heartRateTimeline: List<ActivityHeartRatePoint> = emptyList(),
)

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
    val sleepStages: SleepStages? = null,
    val hrvTrend: List<HrvTrendPoint> = emptyList(),
    val bodyBatteryTimeline: List<TimelinePoint> = emptyList(),
    val stressTimeline: List<TimelinePoint> = emptyList(),
    val lastActivity: LastActivity? = null,
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
                sleepStages = json.optJSONObject("sleepStages")?.let {
                    SleepStages(
                        deepSeconds = it.optNonNegativeIntOrNull("deepSeconds"),
                        lightSeconds = it.optNonNegativeIntOrNull("lightSeconds"),
                        remSeconds = it.optNonNegativeIntOrNull("remSeconds"),
                        awakeSeconds = it.optNonNegativeIntOrNull("awakeSeconds"),
                    )
                }?.takeUnless { s ->
                    listOf(s.deepSeconds, s.lightSeconds, s.remSeconds, s.awakeSeconds).all { v -> v == null }
                },
                hrvTrend = json.optArrayObjects("hrvTrend").map {
                    HrvTrendPoint(
                        date = it.optDateOrNull("date"),
                        overnightAverage = it.optIntOrNull("overnightAverage"),
                        sevenDayAverage = it.optIntOrNull("sevenDayAverage"),
                        status = it.optStringOrNull("status"),
                    )
                }.takeLast(28),
                bodyBatteryTimeline = json.optArrayObjects("bodyBatteryTimeline").mapNotNull {
                    val instant = it.optInstantOrNull("timestamp") ?: return@mapNotNull null
                    val value = it.optIntOrNull("value") ?: return@mapNotNull null
                    if (value !in 0..100) return@mapNotNull null
                    TimelinePoint(timestamp = instant, value = value)
                }.sortedBy { it.timestamp }.takeLast(48),
                stressTimeline = json.optArrayObjects("stressTimeline").mapNotNull {
                    val instant = it.optInstantOrNull("timestamp") ?: return@mapNotNull null
                    val value = it.optIntOrNull("value") ?: return@mapNotNull null
                    if (value !in 0..100) return@mapNotNull null
                    TimelinePoint(timestamp = instant, value = value)
                }.sortedBy { it.timestamp }.takeLast(48),
                lastActivity = json.optJSONObject("lastActivity")?.let {
                    LastActivity(
                        name = it.optStringOrNull("name"),
                        typeKey = it.optStringOrNull("typeKey"),
                        startedAt = it.optInstantOrNull("startedAt"),
                        durationSeconds = it.optNonNegativeIntOrNull("durationSeconds"),
                        movingDurationSeconds = it.optNonNegativeIntOrNull("movingDurationSeconds"),
                        distanceMeters = it.optNonNegativeDoubleOrNull("distanceMeters"),
                        calories = it.optNonNegativeIntOrNull("calories"),
                        averageHeartRate = it.optNonNegativeIntOrNull("averageHeartRate"),
                        maxHeartRate = it.optNonNegativeIntOrNull("maxHeartRate"),
                        elevationGainMeters = it.optNonNegativeDoubleOrNull("elevationGainMeters"),
                        averageSpeedMetersPerSecond = it.optNonNegativeDoubleOrNull("averageSpeedMetersPerSecond"),
                        aerobicTrainingEffect = it.optNonNegativeDoubleOrNull("aerobicTrainingEffect"),
                        anaerobicTrainingEffect = it.optNonNegativeDoubleOrNull("anaerobicTrainingEffect"),
                        trainingLoad = it.optNonNegativeDoubleOrNull("trainingLoad"),
                        heartRateTimeline = it.optArrayObjects("heartRateTimeline").mapNotNull { point ->
                            val elapsed = point.optNonNegativeIntOrNull("elapsedSeconds") ?: return@mapNotNull null
                            val hr = point.optIntOrNull("heartRate") ?: return@mapNotNull null
                            if (hr !in ACTIVITY_HR_MIN..ACTIVITY_HR_MAX) return@mapNotNull null
                            ActivityHeartRatePoint(elapsedSeconds = elapsed, heartRate = hr)
                        }.sortedBy { point -> point.elapsedSeconds }.take(ACTIVITY_HR_TIMELINE_MAX),
                    )
                }?.takeUnless { activity ->
                    listOf(
                        activity.name,
                        activity.typeKey,
                        activity.startedAt,
                        activity.durationSeconds,
                        activity.movingDurationSeconds,
                        activity.distanceMeters,
                        activity.calories,
                        activity.averageHeartRate,
                        activity.maxHeartRate,
                        activity.elevationGainMeters,
                        activity.averageSpeedMetersPerSecond,
                        activity.aerobicTrainingEffect,
                        activity.anaerobicTrainingEffect,
                        activity.trainingLoad,
                    ).all { field -> field == null } && activity.heartRateTimeline.isEmpty()
                },
            )
        }

        private const val ACTIVITY_HR_MIN = 20
        private const val ACTIVITY_HR_MAX = 250
        private const val ACTIVITY_HR_TIMELINE_MAX = 48

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) runCatching { getInt(key) }.getOrNull() else null

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val value = opt(key) as? String ?: return null
            return value.trim().ifEmpty { null }
        }

        private fun JSONObject.optDoubleOrNull(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            return runCatching { getDouble(key) }.getOrNull()
        }

        private fun JSONObject.optNonNegativeIntOrNull(key: String): Int? =
            optIntOrNull(key)?.takeIf { it >= 0 }

        private fun JSONObject.optNonNegativeDoubleOrNull(key: String): Double? {
            val value = optDoubleOrNull(key) ?: return null
            if (!value.isFinite()) return null
            if (value < 0.0 && abs(value) > 0.0) return null
            return value
        }

        private fun JSONObject.optDateOrNull(key: String): LocalDate? =
            optStringOrNull(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        private fun JSONObject.optInstantOrNull(key: String): Instant? =
            optStringOrNull(key)?.let { parseInstant(it) }

        private fun parseInstant(raw: String): Instant? {
            if (raw.isBlank()) return null
            return runCatching { Instant.parse(raw) }
                .recoverCatching { OffsetDateTime.parse(raw).toInstant() }
                .getOrNull()
        }

        private fun JSONObject.optArrayObjects(key: String): List<JSONObject> {
            if (!has(key) || isNull(key)) return emptyList()
            val arr = optJSONArray(key) ?: return emptyList()
            val items = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                val value = arr.get(i)
                val obj = when (value) {
                    is JSONObject -> value
                    is String -> runCatching {
                        val parsed = JSONTokener(value).nextValue()
                        parsed as? JSONObject
                    }.getOrNull()
                    else -> null
                }
                if (obj != null) {
                    items.add(obj)
                }
            }
            return items
        }
    }
}

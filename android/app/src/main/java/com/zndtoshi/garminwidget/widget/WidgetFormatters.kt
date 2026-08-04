package com.zndtoshi.garminwidget.widget

import com.zndtoshi.garminwidget.data.HrvTrendPoint
import com.zndtoshi.garminwidget.data.LastActivity
import com.zndtoshi.garminwidget.data.LocalStatus
import com.zndtoshi.garminwidget.data.TimelinePoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal enum class HrvMarkerKind { CIRCLE, SQUARE, TRIANGLE, NEUTRAL }

internal fun clampOpacityPercent(value: Int?): Int = (value ?: 88).coerceIn(0, 100)

/**
 * Extra darkening layered onto the configured opacity so bright wallpapers stay
 * readable without replacing the user's transparency preference.
 */
internal const val WIDGET_SCRIM_EXTRA_ALPHA = 0.05f

internal fun opacityPercentToAlpha(opacityPercent: Int): Float {
    val configured = clampOpacityPercent(opacityPercent) / 100f
    return configured + (1f - configured) * WIDGET_SCRIM_EXTRA_ALPHA
}

internal fun widgetBackgroundRemainsTranslucent(opacityPercent: Int): Boolean =
    opacityPercentToAlpha(opacityPercent) < 1f

internal fun formatSleepDuration(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return "—"
    val totalMinutes = seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "${hours}h ${minutes}m"
}

internal fun formatStageLabel(name: String, seconds: Int?): String =
    if (seconds == null || seconds <= 0) "$name —" else "$name ${formatSleepDuration(seconds)}"

/** Sleep-stage legend was removed from the widget; keep helper only for negative proof in tests. */
internal fun widgetRendersSleepStageLegend(): Boolean = false

internal fun hasRenderableHrvTrend(points: List<HrvTrendPoint>): Boolean =
    pickRecentHrvPoints(points, 28).count { hrvPlotValue(it) != null } >= 2

internal fun mapHrvStatusToMarker(status: String?): HrvMarkerKind {
    return when (status?.trim()?.uppercase(Locale.US)) {
        "BALANCED" -> HrvMarkerKind.CIRCLE
        "UNBALANCED" -> HrvMarkerKind.SQUARE
        "LOW", "POOR" -> HrvMarkerKind.TRIANGLE
        else -> HrvMarkerKind.NEUTRAL
    }
}

/** Point-aware: missing date or both averages forces neutral even if status looks healthy. */
internal fun mapHrvPointToMarker(point: HrvTrendPoint): HrvMarkerKind {
    if (point.date == null) return HrvMarkerKind.NEUTRAL
    if (point.overnightAverage == null && point.sevenDayAverage == null) return HrvMarkerKind.NEUTRAL
    return mapHrvStatusToMarker(point.status)
}

internal fun formatHrvStatusLabel(status: String?): String {
    return when (status?.trim()?.uppercase(Locale.US)) {
        "BALANCED" -> "Balanced"
        "UNBALANCED" -> "Unbalanced"
        "LOW" -> "Low"
        "POOR" -> "Poor"
        "NONE", null, "" -> "—"
        else -> status.trim().replaceFirstChar { it.titlecase(Locale.US) }
    }
}

internal fun hrvPointDisplayValue(point: HrvTrendPoint): String =
    point.sevenDayAverage?.toString()
        ?: point.overnightAverage?.toString()
        ?: "—"

internal fun pickRecentHrvPoints(
    points: List<HrvTrendPoint>,
    maxPoints: Int,
): List<HrvTrendPoint> = points
    .sortedBy { it.date ?: LocalDate.MIN }
    .takeLast(maxPoints)

internal fun filterTimelineForResponseDate(
    points: List<TimelinePoint>,
    responseDate: String?,
    zoneId: ZoneId,
): List<TimelinePoint> {
    val targetDate = runCatching { LocalDate.parse(responseDate) }.getOrNull()
        ?: return points.filter { it.value in 0..100 }.sortedBy { it.timestamp }.takeLast(48)
    return points
        .filter { it.value in 0..100 }
        .filter { it.timestamp.atZone(zoneId).toLocalDate() == targetDate }
        .sortedBy { it.timestamp }
        .takeLast(48)
}

internal fun appendCurrentBodyBatteryPoint(
    points: List<TimelinePoint>,
    currentValue: Int?,
    refreshedAt: String?,
    responseDate: String?,
    zoneId: ZoneId,
): List<TimelinePoint> {
    val value = currentValue?.takeIf { it in 0..100 } ?: return points
    val timestamp = runCatching { Instant.parse(refreshedAt) }.getOrNull() ?: return points
    val targetDate = runCatching { LocalDate.parse(responseDate) }.getOrNull()
    if (targetDate != null && timestamp.atZone(zoneId).toLocalDate() != targetDate) return points

    val sorted = points.filter { it.value in 0..100 }.sortedBy { it.timestamp }
    if (sorted.lastOrNull()?.timestamp?.let { !timestamp.isAfter(it) } == true) return sorted
    return (sorted + TimelinePoint(timestamp, value)).takeLast(48)
}

internal fun timelineDayRange(responseDate: String?, zoneId: ZoneId): Pair<Instant, Instant>? {
    val date = runCatching { LocalDate.parse(responseDate) }.getOrNull() ?: return null
    val start = date.atStartOfDay(zoneId).toInstant()
    return start to date.plusDays(1).atStartOfDay(zoneId).toInstant()
}

internal fun formatLocalTime(
    instant: Instant?,
    zoneId: ZoneId,
    locale: Locale,
): String {
    if (instant == null) return "—"
    val formatter = DateTimeFormatter.ofPattern("HH:mm", locale)
    return formatter.format(instant.atZone(zoneId))
}

internal fun formatDistanceKm(meters: Double?): String {
    if (meters == null || meters <= 0.0) return "—"
    val km = meters / 1000.0
    return String.format(Locale.US, "%.1f km", km)
}

internal fun formatPace(distanceMeters: Double?, durationSeconds: Int?): String {
    if (distanceMeters == null || durationSeconds == null || distanceMeters <= 0.0 || durationSeconds <= 0) return "—"
    val secondsPerKm = durationSeconds / (distanceMeters / 1000.0)
    val mins = (secondsPerKm / 60.0).toInt()
    val secs = (secondsPerKm - mins * 60).roundToInt().coerceIn(0, 59)
    return String.format(Locale.US, "%d:%02d /km", mins, secs)
}

internal fun formatPaceFromActivity(activity: LastActivity): String {
    val duration = activity.movingDurationSeconds ?: activity.durationSeconds
    return formatPace(activity.distanceMeters, duration)
}

internal fun formatSpeedMps(speedMps: Double?): String {
    if (speedMps == null || speedMps <= 0.0) return "—"
    val kmh = speedMps * 3.6
    return String.format(Locale.US, "%.1f km/h", kmh)
}

internal fun formatDuration(seconds: Int?): String = formatSleepDuration(seconds)

internal fun formatCalories(calories: Int?): String =
    if (calories == null || calories < 0) "—" else "$calories kcal"

internal fun formatHr(value: Int?): String =
    if (value == null || value <= 0) "—" else "$value bpm"

internal data class TopPanelGeometry(
    val contentWidthDp: Float,
    val panelWidthDp: Float,
    val centersDp: List<Float>,
) {
    val equalWidths: Boolean
        get() {
            if (centersDp.size < 2) return true
            val gaps = centersDp.zipWithNext { a, b -> b - a }
            return gaps.all { kotlin.math.abs(it - panelWidthDp) < 0.01f }
        }
}

/** Equal horizontal allocations for Sleep / HRV / Training Readiness panels. */
internal fun equalTopPanelGeometry(contentWidthDp: Float, panelCount: Int = 3): TopPanelGeometry {
    require(panelCount > 0)
    val width = contentWidthDp / panelCount.toFloat()
    val centers = (0 until panelCount).map { index -> width * index + width / 2f }
    return TopPanelGeometry(contentWidthDp, width, centers)
}

internal const val HRV_SCALE_MIN_MS = 22
internal const val HRV_SCALE_MAX_MS = 80

/** Prefer seven-day average; fall back to overnight; never invent a seven-day value. */
internal fun hrvPlotValue(point: HrvTrendPoint): Int? =
    point.sevenDayAverage ?: point.overnightAverage

internal fun clampHrvForScale(value: Int): Int = value.coerceIn(HRV_SCALE_MIN_MS, HRV_SCALE_MAX_MS)

internal data class BatteryStressPresentation(
    val bodyBatteryLabel: String,
    val bodyBatteryValue: String,
    val stressLabel: String,
    val stressValue: String,
) {
    fun visibleLabels(): List<String> = listOf(bodyBatteryLabel, stressLabel)
}

internal fun batteryStressPresentation(bodyBattery: Int?, stress: Int?): BatteryStressPresentation =
    BatteryStressPresentation(
        bodyBatteryLabel = "Body Battery",
        bodyBatteryValue = bodyBattery?.toString() ?: "—",
        stressLabel = "Stress",
        stressValue = stress?.toString() ?: "—",
    )

/** Subtle separation without the milky appearance of the earlier 0x18 alpha cards. */
internal const val HEALTH_PANEL_CARD_COLOR = 0x0CFFFFFF
internal const val HEALTH_TRAILING_VALUE_FONT_SP = 13f
internal const val ACTIVITY_MAX_HR_FONT_SP = 11f
internal const val ACTIVITY_TITLE_FONT_SP = 10f

/** Fraction of chart width reserved for the left-aligned activity name (avoids Max HR). */
internal const val ACTIVITY_NAME_MAX_WIDTH_FRACTION = 0.40f

internal fun activityNameOverlapsCenteredMaxHr(nameWidthFraction: Float): Boolean =
    nameWidthFraction > 0.48f

/** Activity card: square top corners, 24dp rounded bottom (matches drawable). */
internal data class ActivityCardCornerSpec(
    val topLeftRadiusDp: Float = 0f,
    val topRightRadiusDp: Float = 0f,
    val bottomLeftRadiusDp: Float = LayoutMetrics.ACTIVITY_CARD_BOTTOM_CORNER_RADIUS_DP.toFloat(),
    val bottomRightRadiusDp: Float = LayoutMetrics.ACTIVITY_CARD_BOTTOM_CORNER_RADIUS_DP.toFloat(),
)

internal fun activityCardCornerSpec(): ActivityCardCornerSpec = ActivityCardCornerSpec()

/**
 * Charcoal activity-card scrim (~18% top → ~12% bottom). Kept translucent and
 * darker than the near-invisible white `#0CFFFFFF` health-card tint.
 */
internal data class ActivityCardScrimSpec(
    val topArgb: Int = 0x2E000000,
    val bottomArgb: Int = 0x1F000000,
) {
    val topAlpha: Float get() = ((topArgb ushr 24) and 0xff) / 255f
    val bottomAlpha: Float get() = ((bottomArgb ushr 24) and 0xff) / 255f
}

internal fun activityCardScrimSpec(): ActivityCardScrimSpec = ActivityCardScrimSpec()

internal fun healthPanelsUseCardBackground(): Boolean = true

/**
 * Garmin-style panel header: icon + title on the left, optional primary value on the right.
 * Trailing values must not be duplicated in the body below the header.
 */
internal data class HealthPanelHeaderPresentation(
    val title: String?,
    val trailingValue: String?,
    val supportingLeft: String?,
) {
    val duplicatesTrailingBelow: Boolean get() = false
}

internal fun hrvHeaderPresentation(
    overnightHrv: Int?,
    hrvStatus: String?,
    showTitle: Boolean,
): HealthPanelHeaderPresentation =
    HealthPanelHeaderPresentation(
        title = if (showTitle) "HRV Status" else null,
        trailingValue = overnightHrv?.let { "$it ms" } ?: "—",
        supportingLeft = formatHrvStatusLabel(hrvStatus),
    )

internal fun bodyBatteryHeaderPresentation(
    bodyBattery: Int?,
    showTitle: Boolean,
): HealthPanelHeaderPresentation = HealthPanelHeaderPresentation(
    title = if (showTitle) "Body Battery" else null,
    trailingValue = bodyBattery?.toString() ?: "—",
    supportingLeft = null,
)

/**
 * Training Readiness header: title only — score lives inside the ring, never in the header.
 */
internal fun trainingReadinessHeaderPresentation(showTitle: Boolean): HealthPanelHeaderPresentation =
    HealthPanelHeaderPresentation(
        title = if (showTitle) "Training Readiness" else null,
        trailingValue = null,
        supportingLeft = null,
    )

internal fun sleepHeaderPresentation(showTitle: Boolean): HealthPanelHeaderPresentation =
    HealthPanelHeaderPresentation(
        title = if (showTitle) "Sleep Score" else null,
        trailingValue = null,
        supportingLeft = null,
    )

internal fun clampTrainingReadiness(score: Int?): Int? =
    score?.coerceIn(0, 100)

internal enum class TrainingReadinessLevel {
    POOR,
    LOW,
    MODERATE,
    HIGH,
    PRIME,
}

internal fun trainingReadinessLevel(score: Int?): TrainingReadinessLevel? {
    val clamped = clampTrainingReadiness(score) ?: return null
    return when (clamped) {
        in 0..24 -> TrainingReadinessLevel.POOR
        in 25..49 -> TrainingReadinessLevel.LOW
        in 50..74 -> TrainingReadinessLevel.MODERATE
        in 75..94 -> TrainingReadinessLevel.HIGH
        else -> TrainingReadinessLevel.PRIME
    }
}

internal fun trainingReadinessClassification(score: Int?): String =
    when (trainingReadinessLevel(score)) {
        TrainingReadinessLevel.POOR -> "Poor"
        TrainingReadinessLevel.LOW -> "Low"
        TrainingReadinessLevel.MODERATE -> "Moderate"
        TrainingReadinessLevel.HIGH -> "High"
        TrainingReadinessLevel.PRIME -> "Prime"
        null -> "No data"
    }

internal fun trainingReadinessScoreLabel(score: Int?): String =
    clampTrainingReadiness(score)?.toString() ?: "—"

internal fun trainingReadinessContentDescription(score: Int?): String {
    val clamped = clampTrainingReadiness(score)
    return if (clamped == null) {
        "Training Readiness unavailable"
    } else {
        "Training Readiness $clamped, ${trainingReadinessClassification(clamped)}"
    }
}

/** Ordered top health cards in the two-row widget. */
internal fun topHealthPanelOrder(): List<String> =
    listOf("Sleep", "HRV Status", "Training Readiness")

/** Body Battery/stress combined chart is retained for tests/helpers but not composed. */
internal fun widgetRendersBodyBatteryChart(): Boolean = false

/**
 * Sleep card: title/icon restored; duration remains inside the ring under the score.
 */
internal data class SleepRingContentPresentation(
    val showTitle: Boolean = true,
    val durationInsideRing: Boolean = true,
    val durationBelowRing: Boolean = false,
)

internal fun sleepRingContentPresentation(): SleepRingContentPresentation =
    SleepRingContentPresentation()

/** Sleep score inside the ring: larger at the current ~438dp allocation, safe when narrower. */
internal fun sleepScoreFontSp(widgetWidthDp: Float): Float = when {
    widgetWidthDp >= 400f -> 26f
    widgetWidthDp >= 340f -> 22f
    widgetWidthDp >= 280f -> 18f
    else -> 16f
}

/** White duration under the score inside the ring. */
internal fun sleepDurationFontSp(widgetWidthDp: Float): Float = when {
    widgetWidthDp >= 400f -> 12f
    widgetWidthDp >= 300f -> 10f
    else -> 9f
}

/** Training Readiness and Sleep use the same primary score hierarchy. */
internal fun trainingReadinessScoreFontSp(widgetWidthDp: Float): Float =
    sleepScoreFontSp(widgetWidthDp)

/** Classification under the readiness score inside the ring. */
internal fun trainingReadinessClassificationFontSp(widgetWidthDp: Float): Float = when {
    widgetWidthDp >= 400f -> 10f
    widgetWidthDp >= 300f -> 9f
    else -> 8f
}

internal fun hasAmbiguousMetricAbbreviation(labels: Collection<String>): Boolean =
    labels.any { label ->
        val trimmed = label.trim()
        trimmed.equals("B", ignoreCase = true) ||
            trimmed.equals("S", ignoreCase = true) ||
            trimmed.equals("BATT", ignoreCase = true)
    }

internal fun normalizeActivityTypeKey(typeKey: String?): String =
    typeKey
        ?.trim()
        ?.lowercase(Locale.US)
        ?.replace('-', '_')
        ?.replace(' ', '_')
        .orEmpty()

internal fun activityTypeIcon(typeKey: String?): String {
    val key = normalizeActivityTypeKey(typeKey)
    return when {
        key in setOf("running", "trail_running", "treadmill_running", "indoor_running", "track_running") -> "running"
        key == "walking" -> "walking"
        key in setOf("hiking", "mountaineering") -> "hiking"
        key.contains("cycl") ||
            key in setOf("road_biking", "indoor_cycling", "mountain_biking", "gravel_cycling", "cycling") -> "cycling"
        key.contains("strength") || key in setOf("weight_training", "strength_training") -> "strength_training"
        key.contains("swim") || key in setOf("lap_swimming", "open_water_swimming") -> "swimming"
        key in setOf("cardio", "hiit", "indoor_cardio", "elliptical") -> "cardio"
        key in setOf("yoga", "pilates") -> "yoga"
        key.contains("ski") || key.contains("snowboard") -> "skiing"
        else -> "generic"
    }
}

internal fun activityIconContentDescription(typeKey: String?): String =
    "Activity icon for ${activityTypeIcon(typeKey)}"

internal fun activityPrimaryMetric(activity: LastActivity): String {
    val type = activityTypeIcon(activity.typeKey)
    return when (type) {
        "running", "walking", "hiking" -> formatPaceFromActivity(activity)
        "cycling" -> formatSpeedMps(activity.averageSpeedMetersPerSecond)
        else -> formatDistanceKm(activity.distanceMeters)
    }
}

internal fun refreshContentDescription(status: LocalStatus): String =
    if (status == LocalStatus.REFRESHING) {
        "Refreshing Garmin widget"
    } else {
        "Refresh Garmin widget"
    }

internal fun statusContentDescription(status: LocalStatus): String =
    "Widget status ${status.name.lowercase(Locale.US).replace('_', ' ')}"

internal fun chartContentDescription(
    bodyPoints: Int,
    stressPoints: Int,
    bodyBattery: Int? = null,
    stress: Int? = null,
): String =
    "Body Battery ${bodyBattery ?: "—"} and Stress ${stress ?: "—"} chart with $bodyPoints battery points and $stressPoints stress points"

internal fun activityChartContentDescription(
    hrTimeline: List<com.zndtoshi.garminwidget.data.ActivityHeartRatePoint>,
    speedTimeline: List<com.zndtoshi.garminwidget.data.ActivitySpeedPoint>,
): String {
    val parts = mutableListOf<String>()
    if (hrTimeline.size >= 2) parts += "heart rate"
    if (speedTimeline.size >= 2) {
        val maxMps = speedTimeline.maxOfOrNull { it.speedMetersPerSecond } ?: 0.0
        val maxKmh = maxMps * 3.6
        parts += "speed up to ${String.format(java.util.Locale.US, "%.0f", maxKmh)} km/h"
    }
    return if (parts.isEmpty()) {
        "Activity chart"
    } else {
        "Activity chart with ${parts.joinToString(" and ")}"
    }
}

internal fun sleepRingContentDescription(score: Int?, hasStages: Boolean): String =
    if (hasStages) "Sleep ring with score ${score ?: 0}" else "Sleep ring with no stage data"

internal fun hrvMarkerContentDescription(point: HrvTrendPoint): String {
    val kind = mapHrvPointToMarker(point)
    val label = when (kind) {
        HrvMarkerKind.CIRCLE -> "balanced"
        HrvMarkerKind.SQUARE -> "unbalanced"
        HrvMarkerKind.TRIANGLE -> "low"
        HrvMarkerKind.NEUTRAL -> "missing"
    }
    return "HRV marker $label"
}

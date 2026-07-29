from __future__ import annotations

from datetime import UTC, datetime

from app.models.domain import DailyMetrics
from app.models.widget import (
    WIDGET_SCHEMA_VERSION,
    WIDGET_SOURCE,
    HrvTrendPoint,
    LastActivity,
    RefreshStatus,
    SleepStages,
    TimelinePoint,
    WidgetResponse,
)


def normalize_daily_metrics(
    metrics: DailyMetrics,
    *,
    refreshed_at: datetime | None = None,
    stale: bool = False,
    refresh_status: RefreshStatus = RefreshStatus.SUCCESS,
) -> WidgetResponse:
    """Map internal metrics into the public widget response model."""
    timestamp = refreshed_at or datetime.now(UTC)

    sleep_stages = None
    if metrics.sleep_stages is not None:
        sleep_stages = SleepStages(
            deepSeconds=metrics.sleep_stages.deep_seconds,
            lightSeconds=metrics.sleep_stages.light_seconds,
            remSeconds=metrics.sleep_stages.rem_seconds,
            awakeSeconds=metrics.sleep_stages.awake_seconds,
        )

    hrv_trend = None
    if metrics.hrv_trend is not None:
        hrv_trend = [
            HrvTrendPoint(
                date=p.date,
                overnightAverage=p.overnight_average,
                sevenDayAverage=p.seven_day_average,
                status=p.status,
            )
            for p in metrics.hrv_trend
        ]

    bb_timeline = None
    if metrics.body_battery_timeline is not None:
        bb_timeline = [
            TimelinePoint(timestamp=p.timestamp, value=p.value)
            for p in metrics.body_battery_timeline
        ]

    stress_timeline = None
    if metrics.stress_timeline is not None:
        stress_timeline = [
            TimelinePoint(timestamp=p.timestamp, value=p.value)
            for p in metrics.stress_timeline
        ]

    last_activity = None
    if metrics.last_activity is not None:
        a = metrics.last_activity
        last_activity = LastActivity(
            name=a.name,
            typeKey=a.type_key,
            startedAt=a.started_at,
            durationSeconds=a.duration_seconds,
            movingDurationSeconds=a.moving_duration_seconds,
            distanceMeters=a.distance_meters,
            calories=a.calories,
            averageHeartRate=a.average_heart_rate,
            maxHeartRate=a.max_heart_rate,
            elevationGainMeters=a.elevation_gain_meters,
            averageSpeedMetersPerSecond=a.average_speed_meters_per_second,
            aerobicTrainingEffect=a.aerobic_training_effect,
            anaerobicTrainingEffect=a.anaerobic_training_effect,
            trainingLoad=a.training_load,
        )

    return WidgetResponse(
        schemaVersion=WIDGET_SCHEMA_VERSION,
        date=metrics.metric_date,
        sleepScore=metrics.sleep_score,
        sleepDurationSeconds=metrics.sleep_duration_seconds,
        sleepStages=sleep_stages,
        overnightHrv=metrics.overnight_hrv,
        hrvStatus=metrics.hrv_status,
        hrvTrend=hrv_trend,
        bodyBattery=metrics.body_battery,
        bodyBatteryTimeline=bb_timeline,
        restingHeartRate=metrics.resting_heart_rate,
        stress=metrics.stress,
        stressTimeline=stress_timeline,
        trainingReadiness=metrics.training_readiness,
        lastActivity=last_activity,
        garminSyncAt=metrics.garmin_sync_at,
        refreshedAt=timestamp,
        stale=stale,
        refreshStatus=refresh_status,
        source=WIDGET_SOURCE,
    )

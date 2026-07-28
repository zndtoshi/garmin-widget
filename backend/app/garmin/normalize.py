from __future__ import annotations

from datetime import UTC, datetime

from app.models.domain import DailyMetrics
from app.models.widget import WIDGET_SCHEMA_VERSION, WIDGET_SOURCE, RefreshStatus, WidgetResponse


def normalize_daily_metrics(
    metrics: DailyMetrics,
    *,
    refreshed_at: datetime | None = None,
    stale: bool = False,
    refresh_status: RefreshStatus = RefreshStatus.SUCCESS,
) -> WidgetResponse:
    """Map internal metrics into the public widget response model."""
    timestamp = refreshed_at or datetime.now(UTC)

    return WidgetResponse(
        schemaVersion=WIDGET_SCHEMA_VERSION,
        date=metrics.metric_date,
        sleepScore=metrics.sleep_score,
        sleepDurationSeconds=metrics.sleep_duration_seconds,
        overnightHrv=metrics.overnight_hrv,
        hrvStatus=metrics.hrv_status,
        bodyBattery=metrics.body_battery,
        restingHeartRate=metrics.resting_heart_rate,
        stress=metrics.stress,
        trainingReadiness=metrics.training_readiness,
        garminSyncAt=metrics.garmin_sync_at,
        refreshedAt=timestamp,
        stale=stale,
        refreshStatus=refresh_status,
        source=WIDGET_SOURCE,
    )

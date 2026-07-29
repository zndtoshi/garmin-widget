from __future__ import annotations

from datetime import date, datetime

from pydantic import BaseModel, ConfigDict


class SleepStagesInternal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    deep_seconds: int | None = None
    light_seconds: int | None = None
    rem_seconds: int | None = None
    awake_seconds: int | None = None


class HrvTrendPointInternal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    date: date
    overnight_average: int | None = None
    seven_day_average: int | None = None
    status: str | None = None


class TimelinePointInternal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    timestamp: datetime
    value: int


class ActivityHeartRatePointInternal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    elapsed_seconds: int
    heart_rate: int


class LastActivityInternal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str | None = None
    type_key: str | None = None
    started_at: datetime | None = None
    duration_seconds: int | None = None
    moving_duration_seconds: int | None = None
    distance_meters: float | None = None
    calories: int | None = None
    average_heart_rate: int | None = None
    max_heart_rate: int | None = None
    elevation_gain_meters: float | None = None
    average_speed_meters_per_second: float | None = None
    aerobic_training_effect: float | None = None
    anaerobic_training_effect: float | None = None
    training_load: float | None = None
    heart_rate_timeline: list[ActivityHeartRatePointInternal] | None = None


class DailyMetrics(BaseModel):
    """Internal normalized metrics. No raw Garmin field names."""

    model_config = ConfigDict(extra="forbid")

    metric_date: date
    sleep_score: int | None = None
    sleep_duration_seconds: int | None = None
    sleep_stages: SleepStagesInternal | None = None
    overnight_hrv: int | None = None
    hrv_status: str | None = None
    hrv_trend: list[HrvTrendPointInternal] | None = None
    body_battery: int | None = None
    body_battery_timeline: list[TimelinePointInternal] | None = None
    resting_heart_rate: int | None = None
    stress: int | None = None
    stress_timeline: list[TimelinePointInternal] | None = None
    training_readiness: int | None = None
    last_activity: LastActivityInternal | None = None
    garmin_sync_at: datetime | None = None

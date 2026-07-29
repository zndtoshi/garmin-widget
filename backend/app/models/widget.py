from __future__ import annotations

from datetime import UTC, datetime
from datetime import date as Date
from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, PlainSerializer, field_validator

WIDGET_SCHEMA_VERSION: Literal[1] = 1
WIDGET_SOURCE: Literal["garmin-connect-unofficial"] = "garmin-connect-unofficial"


def _serialize_utc_z(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


UtcDateTime = Annotated[
    datetime,
    PlainSerializer(_serialize_utc_z, return_type=str | None, when_used="json"),
]


class RefreshStatus(StrEnum):
    SUCCESS = "SUCCESS"
    CACHE_HIT = "CACHE_HIT"
    COOLDOWN = "COOLDOWN"
    UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE"
    NO_DATA = "NO_DATA"


class SleepStages(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    deep_seconds: int | None = Field(default=None, alias="deepSeconds")
    light_seconds: int | None = Field(default=None, alias="lightSeconds")
    rem_seconds: int | None = Field(default=None, alias="remSeconds")
    awake_seconds: int | None = Field(default=None, alias="awakeSeconds")


class HrvTrendPoint(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    date: Date
    overnight_average: int | None = Field(default=None, alias="overnightAverage")
    seven_day_average: int | None = Field(default=None, alias="sevenDayAverage")
    status: str | None = None


class TimelinePoint(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    timestamp: UtcDateTime
    value: int = Field(ge=0, le=100)

    @field_validator("timestamp", mode="after")
    @classmethod
    def normalize_timestamp(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)


class ActivityHeartRatePoint(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    elapsed_seconds: int = Field(ge=0, alias="elapsedSeconds")
    heart_rate: int = Field(ge=20, le=250, alias="heartRate")


class LastActivity(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    name: str | None = None
    type_key: str | None = Field(default=None, alias="typeKey")
    started_at: UtcDateTime | None = Field(default=None, alias="startedAt")
    duration_seconds: int | None = Field(default=None, alias="durationSeconds")
    moving_duration_seconds: int | None = Field(
        default=None, alias="movingDurationSeconds"
    )
    distance_meters: float | None = Field(default=None, alias="distanceMeters")
    calories: int | None = None
    average_heart_rate: int | None = Field(default=None, alias="averageHeartRate")
    max_heart_rate: int | None = Field(default=None, alias="maxHeartRate")
    elevation_gain_meters: float | None = Field(
        default=None, alias="elevationGainMeters"
    )
    average_speed_meters_per_second: float | None = Field(
        default=None, alias="averageSpeedMetersPerSecond"
    )
    aerobic_training_effect: float | None = Field(
        default=None, alias="aerobicTrainingEffect"
    )
    anaerobic_training_effect: float | None = Field(
        default=None, alias="anaerobicTrainingEffect"
    )
    training_load: float | None = Field(default=None, alias="trainingLoad")
    heart_rate_timeline: list[ActivityHeartRatePoint] | None = Field(
        default=None, alias="heartRateTimeline", max_length=48
    )

    @field_validator("started_at", mode="after")
    @classmethod
    def normalize_started_at(cls, value: datetime | None) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)


class WidgetResponse(BaseModel):
    """Public version-one widget payload (camelCase JSON)."""

    model_config = ConfigDict(
        extra="forbid",
        populate_by_name=True,
        ser_json_timedelta="iso8601",
    )

    schema_version: Literal[1] = Field(
        default=WIDGET_SCHEMA_VERSION, alias="schemaVersion"
    )
    date: Date | None = None
    sleep_score: int | None = Field(default=None, alias="sleepScore")
    sleep_duration_seconds: int | None = Field(
        default=None, alias="sleepDurationSeconds"
    )
    sleep_stages: SleepStages | None = Field(default=None, alias="sleepStages")
    overnight_hrv: int | None = Field(default=None, alias="overnightHrv")
    hrv_status: str | None = Field(default=None, alias="hrvStatus")
    hrv_trend: list[HrvTrendPoint] | None = Field(
        default=None, alias="hrvTrend", max_length=28,
    )
    body_battery: int | None = Field(default=None, alias="bodyBattery")
    body_battery_timeline: list[TimelinePoint] | None = Field(
        default=None, alias="bodyBatteryTimeline", max_length=48,
    )
    resting_heart_rate: int | None = Field(default=None, alias="restingHeartRate")
    stress: int | None = None
    stress_timeline: list[TimelinePoint] | None = Field(
        default=None, alias="stressTimeline", max_length=48,
    )
    training_readiness: int | None = Field(default=None, alias="trainingReadiness")
    last_activity: LastActivity | None = Field(default=None, alias="lastActivity")
    garmin_sync_at: UtcDateTime | None = Field(default=None, alias="garminSyncAt")
    refreshed_at: UtcDateTime | None = Field(default=None, alias="refreshedAt")
    stale: bool = False
    refresh_status: RefreshStatus = Field(
        default=RefreshStatus.SUCCESS, alias="refreshStatus"
    )
    source: Literal["garmin-connect-unofficial"] = WIDGET_SOURCE

    @field_validator("garmin_sync_at", "refreshed_at", mode="after")
    @classmethod
    def normalize_public_timestamps(cls, value: datetime | None) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)

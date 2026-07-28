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
    overnight_hrv: int | None = Field(default=None, alias="overnightHrv")
    hrv_status: str | None = Field(default=None, alias="hrvStatus")
    body_battery: int | None = Field(default=None, alias="bodyBattery")
    resting_heart_rate: int | None = Field(default=None, alias="restingHeartRate")
    stress: int | None = None
    training_readiness: int | None = Field(default=None, alias="trainingReadiness")
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

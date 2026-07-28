from __future__ import annotations

from datetime import date, datetime

from pydantic import BaseModel, ConfigDict


class DailyMetrics(BaseModel):
    """Internal normalized metrics. No raw Garmin field names."""

    model_config = ConfigDict(extra="forbid")

    metric_date: date
    sleep_score: int | None = None
    sleep_duration_seconds: int | None = None
    overnight_hrv: int | None = None
    hrv_status: str | None = None
    body_battery: int | None = None
    resting_heart_rate: int | None = None
    stress: int | None = None
    training_readiness: int | None = None
    garmin_sync_at: datetime | None = None

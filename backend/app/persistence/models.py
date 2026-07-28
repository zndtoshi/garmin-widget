from __future__ import annotations

from datetime import UTC, datetime
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.models.widget import RefreshStatus, WidgetResponse

SNAPSHOT_FORMAT_VERSION: Literal[1] = 1


class WidgetSnapshot(BaseModel):
    """Atomic on-disk unit: successful payload + cooldown metadata together."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    persistence_format_version: Literal[1] = Field(
        default=SNAPSHOT_FORMAT_VERSION,
        alias="persistenceFormatVersion",
    )
    last_successful_refresh_at: datetime = Field(alias="lastSuccessfulRefreshAt")
    payload: WidgetResponse

    @field_validator("last_successful_refresh_at", mode="after")
    @classmethod
    def ensure_utc(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)

    @model_validator(mode="after")
    def ensure_complete_successful_refresh(self) -> Self:
        payload = self.payload
        if payload.refresh_status != RefreshStatus.SUCCESS:
            raise ValueError("Snapshot payload refreshStatus must be SUCCESS.")
        if payload.stale:
            raise ValueError("Snapshot payload stale must be false.")
        if payload.refreshed_at is None:
            raise ValueError("Snapshot payload refreshedAt is required.")

        refreshed_at = payload.refreshed_at
        if refreshed_at.tzinfo is None:
            refreshed_at = refreshed_at.replace(tzinfo=UTC)
        else:
            refreshed_at = refreshed_at.astimezone(UTC)

        if refreshed_at != self.last_successful_refresh_at:
            raise ValueError(
                "Snapshot payload refreshedAt must equal lastSuccessfulRefreshAt."
            )
        return self

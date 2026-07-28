from __future__ import annotations

from datetime import UTC, date, datetime
from zoneinfo import ZoneInfo


def calendar_date_for_timezone(
    timezone_name: str,
    *,
    now: datetime | None = None,
) -> date:
    """Return the calendar date in the configured IANA timezone."""
    current = now or datetime.now(UTC)
    if current.tzinfo is None:
        current = current.replace(tzinfo=UTC)
    return current.astimezone(ZoneInfo(timezone_name)).date()


def parse_metric_date(value: str) -> date:
    return date.fromisoformat(value)

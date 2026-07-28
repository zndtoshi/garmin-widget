from __future__ import annotations

from datetime import date

from app.core.config import Settings
from app.garmin.adapter import GarminMetricsAdapter
from app.garmin.session import GarminSessionManager
from app.models.domain import DailyMetrics


class SessionBackedMetricsProvider:
    """Initialize/reuse the Garmin session only when a live refresh needs metrics."""

    def __init__(
        self,
        settings: Settings,
        *,
        session_manager: GarminSessionManager | None = None,
    ) -> None:
        self._session_manager = session_manager or GarminSessionManager(
            settings=settings
        )

    def fetch_daily_metrics(self, metric_date: date) -> DailyMetrics:
        client = self._session_manager.initialize_session()
        return GarminMetricsAdapter(client).fetch_daily_metrics(metric_date)

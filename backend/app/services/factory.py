from __future__ import annotations

from app.core.config import get_settings
from app.persistence.snapshot import FilesystemWidgetSnapshotRepository
from app.services.metrics_provider import SessionBackedMetricsProvider
from app.services.refresh import SystemClock, WidgetRefreshService

_refresh_service: WidgetRefreshService | None = None


def get_widget_refresh_service() -> WidgetRefreshService:
    """Return the process-scoped refresh service, constructing it lazily."""
    global _refresh_service
    if _refresh_service is None:
        settings = get_settings()
        snapshot = FilesystemWidgetSnapshotRepository(settings.data_dir)
        _refresh_service = WidgetRefreshService(
            clock=SystemClock(),
            metrics_provider=SessionBackedMetricsProvider(settings),
            snapshot=snapshot,
            cooldown_seconds=settings.refresh_cooldown_seconds,
            timezone_name=settings.timezone,
        )
    return _refresh_service


def clear_service_caches() -> None:
    """Drop cached process-scoped services (used when settings/env change in tests)."""
    global _refresh_service
    _refresh_service = None

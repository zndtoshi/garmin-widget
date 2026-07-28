from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from datetime import UTC, date, datetime
from typing import Protocol

from app.garmin.dates import calendar_date_for_timezone
from app.garmin.normalize import normalize_daily_metrics
from app.models.domain import DailyMetrics
from app.models.widget import RefreshStatus, WidgetResponse
from app.persistence.coordinator import refresh_lock_for
from app.persistence.errors import (
    CorruptCacheError,
    NoCachedWidgetError,
    PersistenceWriteError,
    RefreshFailedError,
)
from app.persistence.models import WidgetSnapshot
from app.persistence.snapshot import WidgetSnapshotRepository

logger = logging.getLogger(__name__)


class Clock(Protocol):
    def now(self) -> datetime: ...


class MetricsProvider(Protocol):
    def fetch_daily_metrics(self, metric_date: date) -> DailyMetrics: ...


NormalizeFn = Callable[..., WidgetResponse]


class SystemClock:
    def now(self) -> datetime:
        return datetime.now(UTC)


class WidgetRefreshService:
    """Coordinate cooldown, live Garmin refresh, snapshot reads, and locking.

    Default locking is process-scoped per data directory so independently
    constructed services sharing one DATA_DIR still deduplicate refreshes.
    This still does not coordinate multiple OS processes or Render instances.
    The first Render deployment must use one worker/instance.
    """

    def __init__(
        self,
        *,
        clock: Clock,
        metrics_provider: MetricsProvider,
        snapshot: WidgetSnapshotRepository,
        cooldown_seconds: int,
        timezone_name: str,
        normalize: NormalizeFn = normalize_daily_metrics,
        lock: threading.Lock | None = None,
    ) -> None:
        if cooldown_seconds < 0:
            raise ValueError("cooldown_seconds must be >= 0")
        self._clock = clock
        self._metrics_provider = metrics_provider
        self._snapshot = snapshot
        self._cooldown_seconds = cooldown_seconds
        self._timezone_name = timezone_name
        self._normalize = normalize
        self._lock = lock or refresh_lock_for(snapshot.data_dir())

    def get_latest(self) -> WidgetResponse:
        """Return the last successful payload without contacting Garmin."""
        with self._lock:
            stored = self._snapshot.load()
            return _derive_response(
                stored.payload,
                refresh_status=RefreshStatus.CACHE_HIT,
                stale=False,
            )

    def refresh(self) -> WidgetResponse:
        """Refresh when cooldown allows; otherwise return cached cooldown/fallback."""
        with self._lock:
            return self._refresh_locked()

    def _refresh_locked(self) -> WidgetResponse:
        now = _ensure_utc(self._clock.now())
        stored = self._load_valid_snapshot()

        if stored is not None and _within_cooldown(
            stored.last_successful_refresh_at, now, self._cooldown_seconds
        ):
            return _derive_response(
                stored.payload,
                refresh_status=RefreshStatus.COOLDOWN,
                stale=False,
            )

        try:
            return self._live_refresh(now)
        except PersistenceWriteError as exc:
            logger.warning("Widget snapshot persistence failed after live refresh.")
            if stored is not None:
                return _derive_response(
                    stored.payload,
                    refresh_status=RefreshStatus.UPSTREAM_UNAVAILABLE,
                    stale=True,
                )
            raise RefreshFailedError(
                "Widget refresh could not be persisted and no valid snapshot exists."
            ) from exc
        except RefreshFailedError:
            raise
        except Exception as exc:
            logger.warning("Live widget refresh failed.")
            if stored is not None:
                return _derive_response(
                    stored.payload,
                    refresh_status=RefreshStatus.UPSTREAM_UNAVAILABLE,
                    stale=True,
                )
            raise RefreshFailedError(
                "Widget refresh failed and no valid snapshot is available."
            ) from exc

    def _live_refresh(self, now: datetime) -> WidgetResponse:
        metric_date = calendar_date_for_timezone(self._timezone_name, now=now)
        metrics = self._metrics_provider.fetch_daily_metrics(metric_date)
        payload = self._normalize(
            metrics,
            refreshed_at=now,
            stale=False,
            refresh_status=RefreshStatus.SUCCESS,
        )
        snapshot = WidgetSnapshot(
            persistenceFormatVersion=1,
            lastSuccessfulRefreshAt=now,
            payload=payload,
        )
        self._snapshot.save(snapshot)
        return payload

    def _load_valid_snapshot(self) -> WidgetSnapshot | None:
        try:
            return self._snapshot.load()
        except NoCachedWidgetError:
            return None
        except CorruptCacheError:
            logger.warning("Ignoring corrupt widget snapshot during refresh.")
            return None


def _derive_response(
    payload: WidgetResponse,
    *,
    refresh_status: RefreshStatus,
    stale: bool,
) -> WidgetResponse:
    """Clone a cached payload with transient status fields; do not mutate storage."""
    return payload.model_copy(
        update={
            "refresh_status": refresh_status,
            "stale": stale,
        }
    )


def _within_cooldown(
    last_success: datetime, now: datetime, cooldown_seconds: int
) -> bool:
    elapsed = (_ensure_utc(now) - _ensure_utc(last_success)).total_seconds()
    return elapsed < cooldown_seconds


def _ensure_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)

from __future__ import annotations

import json
import threading
import time
from datetime import UTC, date, datetime, timedelta, timezone
from pathlib import Path

import pytest

from app.models.domain import DailyMetrics
from app.models.widget import RefreshStatus, WidgetResponse
from app.persistence.coordinator import clear_refresh_locks
from app.persistence.errors import (
    CorruptCacheError,
    NoCachedWidgetError,
    PersistenceWriteError,
    RefreshFailedError,
)
from app.persistence.models import WidgetSnapshot
from app.persistence.snapshot import FilesystemWidgetSnapshotRepository
from app.services.refresh import SystemClock, WidgetRefreshService


@pytest.fixture(autouse=True)
def _reset_process_locks() -> None:
    clear_refresh_locks()
    yield
    clear_refresh_locks()


def _sample_payload(
    *,
    refreshed_at: datetime,
    refresh_status: RefreshStatus = RefreshStatus.SUCCESS,
    stale: bool = False,
    sleep_score: int | None = 84,
) -> WidgetResponse:
    return WidgetResponse(
        schemaVersion=1,
        date=date(2026, 7, 28),
        sleepScore=sleep_score,
        sleepDurationSeconds=22620,
        overnightHrv=47,
        hrvStatus="BALANCED",
        bodyBattery=72,
        restingHeartRate=49,
        stress=18,
        trainingReadiness=81,
        garminSyncAt=datetime(2026, 7, 28, 5, 35, tzinfo=UTC),
        refreshedAt=refreshed_at,
        stale=stale,
        refreshStatus=refresh_status,
        source="garmin-connect-unofficial",
    )


def _sample_snapshot(
    *,
    refreshed_at: datetime,
    sleep_score: int | None = 84,
) -> WidgetSnapshot:
    return WidgetSnapshot(
        persistenceFormatVersion=1,
        lastSuccessfulRefreshAt=refreshed_at,
        payload=_sample_payload(refreshed_at=refreshed_at, sleep_score=sleep_score),
    )


class FakeClock:
    def __init__(self, instant: datetime) -> None:
        self.instant = instant

    def now(self) -> datetime:
        return self.instant


class FakeMetricsProvider:
    def __init__(
        self,
        *,
        payload: DailyMetrics | None = None,
        error: Exception | None = None,
    ) -> None:
        self.payload = payload or DailyMetrics(
            metric_date=date(2026, 7, 28),
            sleep_score=84,
            sleep_duration_seconds=22620,
            overnight_hrv=47,
            hrv_status="BALANCED",
            body_battery=72,
            resting_heart_rate=49,
            stress=18,
            training_readiness=81,
            garmin_sync_at=datetime(2026, 7, 28, 5, 35, tzinfo=UTC),
        )
        self.error = error
        self.calls: list[date] = []

    def fetch_daily_metrics(self, metric_date: date) -> DailyMetrics:
        self.calls.append(metric_date)
        if self.error is not None:
            raise self.error
        return self.payload.model_copy(update={"metric_date": metric_date})


def _service(
    tmp_path: Path,
    *,
    clock: FakeClock,
    provider: FakeMetricsProvider,
    cooldown_seconds: int = 60,
    lock: threading.Lock | None = None,
) -> WidgetRefreshService:
    return WidgetRefreshService(
        clock=clock,
        metrics_provider=provider,
        snapshot=FilesystemWidgetSnapshotRepository(tmp_path),
        cooldown_seconds=cooldown_seconds,
        timezone_name="Europe/Bucharest",
        lock=lock,
    )


def test_snapshot_payload_and_timestamp_round_trip_together(tmp_path: Path) -> None:
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    now = datetime(2026, 7, 28, 5, 36, 4, tzinfo=UTC)
    snapshot = _sample_snapshot(refreshed_at=now)

    repo.save(snapshot)
    loaded = repo.load()

    assert loaded.last_successful_refresh_at == now
    assert loaded.payload.model_dump(by_alias=True, mode="json") == (
        snapshot.payload.model_dump(by_alias=True, mode="json")
    )
    assert repo.path() == tmp_path / "widget" / "latest_snapshot.json"


def test_missing_snapshot_raises(tmp_path: Path) -> None:
    with pytest.raises(NoCachedWidgetError):
        FilesystemWidgetSnapshotRepository(tmp_path).load()


@pytest.mark.parametrize(
    ("raw", "match"),
    [
        ("{not-json", "malformed"),
        ('{"persistenceFormatVersion":1,"lastSuccessfulRefreshAt":', "malformed"),
    ],
)
def test_malformed_and_truncated_snapshot_raise(
    tmp_path: Path, raw: str, match: str
) -> None:
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.path().parent.mkdir(parents=True, exist_ok=True)
    repo.path().write_text(raw, encoding="utf-8")
    with pytest.raises(CorruptCacheError, match=match):
        repo.load()


def test_unsupported_snapshot_format_and_payload_schema_raise(tmp_path: Path) -> None:
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.path().parent.mkdir(parents=True, exist_ok=True)
    base = _sample_snapshot(
        refreshed_at=datetime(2026, 7, 28, 5, 36, tzinfo=UTC)
    ).model_dump(by_alias=True, mode="json")

    bad_format = dict(base)
    bad_format["persistenceFormatVersion"] = 2
    repo.path().write_text(json.dumps(bad_format), encoding="utf-8")
    with pytest.raises(CorruptCacheError, match="persistence format"):
        repo.load()

    bad_payload = dict(base)
    bad_payload["payload"] = dict(base["payload"])
    bad_payload["payload"]["schemaVersion"] = 2
    repo.path().write_text(json.dumps(bad_payload), encoding="utf-8")
    with pytest.raises(CorruptCacheError, match="schema version"):
        repo.load()


def test_load_and_save_reject_invalid_successful_refresh_invariants(
    tmp_path: Path,
) -> None:
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    now = datetime(2026, 7, 28, 5, 36, 4, tzinfo=UTC)
    repo.path().parent.mkdir(parents=True, exist_ok=True)

    cases: list[tuple[str, WidgetResponse, datetime]] = [
        (
            "stale",
            _sample_payload(refreshed_at=now, stale=True),
            now,
        ),
        (
            "cooldown",
            _sample_payload(
                refreshed_at=now,
                refresh_status=RefreshStatus.COOLDOWN,
            ),
            now,
        ),
        (
            "missing-refreshed-at",
            _sample_payload(refreshed_at=now).model_copy(update={"refreshed_at": None}),
            now,
        ),
        (
            "mismatched-instant",
            _sample_payload(refreshed_at=now),
            now + timedelta(seconds=1),
        ),
        (
            "mismatched-offset-instant",
            _sample_payload(refreshed_at=now),
            datetime(2026, 7, 28, 8, 36, 4, tzinfo=timezone(timedelta(hours=3)))
            + timedelta(minutes=5),
        ),
    ]

    for label, payload, last_success in cases:
        data = {
            "persistenceFormatVersion": 1,
            "lastSuccessfulRefreshAt": last_success.isoformat().replace("+00:00", "Z")
            if last_success.utcoffset() == timedelta(0)
            else last_success.isoformat(),
            "payload": payload.model_dump(by_alias=True, mode="json"),
        }
        # Keep JSON faithful for missing refreshedAt.
        if label == "missing-refreshed-at":
            data["payload"]["refreshedAt"] = None
        repo.path().write_text(json.dumps(data), encoding="utf-8")
        with pytest.raises(CorruptCacheError):
            repo.load()

        constructed = WidgetSnapshot.model_construct(
            persistence_format_version=1,
            last_successful_refresh_at=last_success
            if last_success.tzinfo is not None
            else last_success.replace(tzinfo=UTC),
            payload=payload,
        )
        with pytest.raises(PersistenceWriteError, match="invalid widget snapshot"):
            repo.save(constructed)


def test_equivalent_offset_timestamps_are_accepted_and_normalized(
    tmp_path: Path,
) -> None:
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    utc_instant = datetime(2026, 7, 28, 5, 36, 4, tzinfo=UTC)
    offset_instant = datetime(
        2026, 7, 28, 8, 36, 4, tzinfo=timezone(timedelta(hours=3))
    )
    snapshot = WidgetSnapshot(
        persistenceFormatVersion=1,
        lastSuccessfulRefreshAt=offset_instant,
        payload=_sample_payload(refreshed_at=utc_instant),
    )

    assert snapshot.last_successful_refresh_at == utc_instant
    assert snapshot.payload.refreshed_at == utc_instant

    repo.save(snapshot)
    loaded = repo.load()
    assert loaded.last_successful_refresh_at == utc_instant
    assert loaded.payload.refreshed_at == utc_instant


def test_serialization_failure_preserves_previous_snapshot(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.save(
        _sample_snapshot(refreshed_at=datetime(2026, 7, 28, 5, 0, tzinfo=UTC))
    )
    before = repo.path().read_bytes()

    def boom_dumps(*_args: object, **_kwargs: object) -> str:
        raise TypeError("serialization failed")

    monkeypatch.setattr("app.persistence.snapshot.json.dumps", boom_dumps)

    with pytest.raises(PersistenceWriteError):
        repo.save(
            _sample_snapshot(refreshed_at=datetime(2026, 7, 28, 6, 0, tzinfo=UTC))
        )

    assert repo.path().read_bytes() == before


def test_failed_write_preserves_previous_snapshot_bytes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    original = _sample_snapshot(
        refreshed_at=datetime(2026, 7, 28, 5, 0, tzinfo=UTC),
        sleep_score=70,
    )
    repo.save(original)
    before = repo.path().read_bytes()

    def boom_write(*_args: object, **_kwargs: object) -> None:
        raise OSError("disk full")

    monkeypatch.setattr("app.persistence.snapshot.write_text_atomic", boom_write)

    with pytest.raises(PersistenceWriteError):
        repo.save(
            _sample_snapshot(
                refreshed_at=datetime(2026, 7, 28, 6, 0, tzinfo=UTC),
                sleep_score=99,
            )
        )

    assert repo.path().read_bytes() == before


@pytest.mark.parametrize("fail_at", ["write", "fsync", "replace"])
def test_atomic_stage_failures_preserve_previous_snapshot(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    fail_at: str,
) -> None:
    import os

    from app.persistence import atomic as atomic_module

    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.save(
        _sample_snapshot(refreshed_at=datetime(2026, 7, 28, 5, 0, tzinfo=UTC))
    )
    before = repo.path().read_bytes()

    if fail_at == "write":
        real_open = os.open

        def boom_open(*args: object, **kwargs: object) -> int:
            raise OSError("write open failed")

        monkeypatch.setattr(atomic_module.os, "open", boom_open)
        # Keep real open available if needed elsewhere; only atomic uses this patch.
        _ = real_open
    elif fail_at == "fsync":

        def boom_fsync(_fd: int) -> None:
            raise OSError("fsync failed")

        monkeypatch.setattr(atomic_module.os, "fsync", boom_fsync)
    else:

        def boom_replace(*_args: object, **_kwargs: object) -> None:
            raise OSError("replace failed")

        monkeypatch.setattr(atomic_module.os, "replace", boom_replace)

    with pytest.raises(OSError):
        atomic_module.write_text_atomic(
            repo.path(),
            json.dumps({"broken": True}),
            temp_prefix="latest_snapshot",
        )

    assert repo.path().read_bytes() == before
    assert list((tmp_path / "widget").glob(".latest_snapshot_*.json")) == []


def test_first_persistence_failure_leaves_no_snapshot(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    repo = FilesystemWidgetSnapshotRepository(tmp_path)

    def boom(*_args: object, **_kwargs: object) -> None:
        raise OSError("disk full")

    monkeypatch.setattr("app.persistence.snapshot.write_text_atomic", boom)

    with pytest.raises(PersistenceWriteError):
        repo.save(
            _sample_snapshot(refreshed_at=datetime(2026, 7, 28, 6, 0, tzinfo=UTC))
        )

    assert not repo.path().exists()
    assert list((tmp_path / "widget").glob(".latest_snapshot_*.json")) == []


def test_cooldown_hit_skips_garmin(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    provider = FakeMetricsProvider()
    FilesystemWidgetSnapshotRepository(tmp_path).save(
        _sample_snapshot(refreshed_at=now - timedelta(seconds=30))
    )
    service = _service(
        tmp_path, clock=FakeClock(now), provider=provider, cooldown_seconds=60
    )

    result = service.refresh()

    assert result.refresh_status == RefreshStatus.COOLDOWN
    assert result.stale is False
    assert provider.calls == []


def test_cooldown_boundary_allows_refresh(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    provider = FakeMetricsProvider()
    FilesystemWidgetSnapshotRepository(tmp_path).save(
        _sample_snapshot(refreshed_at=now - timedelta(seconds=60))
    )
    service = _service(
        tmp_path, clock=FakeClock(now), provider=provider, cooldown_seconds=60
    )

    result = service.refresh()
    stored = FilesystemWidgetSnapshotRepository(tmp_path).load()

    assert result.refresh_status == RefreshStatus.SUCCESS
    assert result.stale is False
    assert len(provider.calls) == 1
    assert stored.last_successful_refresh_at == now


def test_successful_live_refresh_persists_atomic_snapshot(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    provider = FakeMetricsProvider()
    service = _service(
        tmp_path, clock=FakeClock(now), provider=provider, cooldown_seconds=60
    )

    result = service.refresh()
    stored = FilesystemWidgetSnapshotRepository(tmp_path).load()

    assert result.refresh_status == RefreshStatus.SUCCESS
    assert stored.payload.refresh_status == RefreshStatus.SUCCESS
    assert stored.payload.stale is False
    assert stored.last_successful_refresh_at == now
    assert provider.calls == [date(2026, 7, 28)]


def test_failed_refresh_with_snapshot_returns_stale_fallback(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    previous = now - timedelta(seconds=120)
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.save(_sample_snapshot(refreshed_at=previous, sleep_score=71))
    before = repo.path().read_bytes()

    provider = FakeMetricsProvider(error=RuntimeError("upstream down"))
    service = _service(
        tmp_path, clock=FakeClock(now), provider=provider, cooldown_seconds=60
    )

    result = service.refresh()

    assert result.refresh_status == RefreshStatus.UPSTREAM_UNAVAILABLE
    assert result.stale is True
    assert result.sleep_score == 71
    assert repo.path().read_bytes() == before
    assert "upstream down" not in result.model_dump_json()


def test_failed_persistence_after_live_refresh_falls_back_to_previous(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    previous = now - timedelta(seconds=120)
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.save(_sample_snapshot(refreshed_at=previous, sleep_score=71))
    before = repo.path().read_bytes()

    def boom(*_args: object, **_kwargs: object) -> None:
        raise OSError("disk full")

    monkeypatch.setattr("app.persistence.snapshot.write_text_atomic", boom)
    provider = FakeMetricsProvider()
    service = _service(
        tmp_path, clock=FakeClock(now), provider=provider, cooldown_seconds=60
    )

    result = service.refresh()

    assert len(provider.calls) == 1
    assert result.refresh_status == RefreshStatus.UPSTREAM_UNAVAILABLE
    assert result.stale is True
    assert result.sleep_score == 71
    assert repo.path().read_bytes() == before
    assert json.loads(before)["lastSuccessfulRefreshAt"].startswith("2026-07-28T05:")


def test_failed_refresh_without_snapshot_raises(tmp_path: Path) -> None:
    provider = FakeMetricsProvider(error=RuntimeError("upstream down"))
    service = _service(
        tmp_path,
        clock=FakeClock(datetime(2026, 7, 28, 6, 0, tzinfo=UTC)),
        provider=provider,
    )

    with pytest.raises(RefreshFailedError, match="no valid snapshot"):
        service.refresh()


def test_failed_attempt_does_not_advance_cooldown(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    previous = now - timedelta(seconds=120)
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.save(_sample_snapshot(refreshed_at=previous))
    provider = FakeMetricsProvider(error=RuntimeError("boom"))
    service = _service(
        tmp_path, clock=FakeClock(now), provider=provider, cooldown_seconds=60
    )

    service.refresh()

    assert repo.load().last_successful_refresh_at == previous


def test_independent_services_share_process_lock_for_same_data_dir(
    tmp_path: Path,
) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    started_fetch = threading.Event()
    release = threading.Event()
    shared_calls: list[date] = []

    class BlockingProvider(FakeMetricsProvider):
        def fetch_daily_metrics(self, metric_date: date) -> DailyMetrics:
            shared_calls.append(metric_date)
            started_fetch.set()
            assert release.wait(timeout=5)
            return self.payload.model_copy(update={"metric_date": metric_date})

    provider_a = BlockingProvider()
    provider_b = FakeMetricsProvider()
    service_a = _service(
        tmp_path, clock=FakeClock(now), provider=provider_a, cooldown_seconds=60
    )
    service_b = _service(
        tmp_path, clock=FakeClock(now), provider=provider_b, cooldown_seconds=60
    )

    results: list[WidgetResponse] = []
    errors: list[BaseException] = []

    def worker(service: WidgetRefreshService) -> None:
        try:
            results.append(service.refresh())
        except BaseException as exc:  # noqa: BLE001
            errors.append(exc)

    first = threading.Thread(target=worker, args=(service_a,))
    second = threading.Thread(target=worker, args=(service_b,))
    first.start()
    assert started_fetch.wait(timeout=5)
    second.start()
    time.sleep(0.05)
    release.set()
    first.join(timeout=5)
    second.join(timeout=5)

    assert errors == []
    assert len(shared_calls) == 1
    assert provider_b.calls == []
    assert len(results) == 2
    statuses = {item.refresh_status for item in results}
    assert RefreshStatus.SUCCESS in statuses
    assert statuses <= {RefreshStatus.SUCCESS, RefreshStatus.COOLDOWN}


def test_lock_released_after_failure(tmp_path: Path) -> None:
    provider = FakeMetricsProvider(error=RuntimeError("boom"))
    service = _service(
        tmp_path,
        clock=FakeClock(datetime(2026, 7, 28, 6, 0, tzinfo=UTC)),
        provider=provider,
    )

    with pytest.raises(RefreshFailedError):
        service.refresh()

    provider.error = None
    result = service.refresh()
    assert result.refresh_status == RefreshStatus.SUCCESS


def test_latest_never_contacts_garmin(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    FilesystemWidgetSnapshotRepository(tmp_path).save(
        _sample_snapshot(refreshed_at=now)
    )
    provider = FakeMetricsProvider(error=RuntimeError("should not be called"))
    service = _service(tmp_path, clock=FakeClock(now), provider=provider)

    result = service.get_latest()

    assert result.refresh_status == RefreshStatus.CACHE_HIT
    assert result.stale is False
    assert provider.calls == []


def test_latest_without_snapshot_raises(tmp_path: Path) -> None:
    service = _service(
        tmp_path,
        clock=FakeClock(datetime(2026, 7, 28, 6, 0, tzinfo=UTC)),
        provider=FakeMetricsProvider(),
    )
    with pytest.raises(NoCachedWidgetError):
        service.get_latest()


def test_transient_status_does_not_overwrite_persisted_snapshot(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.save(_sample_snapshot(refreshed_at=now - timedelta(seconds=10)))
    before = repo.path().read_bytes()
    service = _service(
        tmp_path,
        clock=FakeClock(now),
        provider=FakeMetricsProvider(),
        cooldown_seconds=60,
    )

    cooldown = service.refresh()
    latest = service.get_latest()

    assert cooldown.refresh_status == RefreshStatus.COOLDOWN
    assert latest.refresh_status == RefreshStatus.CACHE_HIT
    assert repo.path().read_bytes() == before
    assert json.loads(before)["payload"]["refreshStatus"] == "SUCCESS"


def test_restart_and_new_instance_see_complete_snapshot_only(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    first = _service(
        tmp_path,
        clock=FakeClock(now),
        provider=FakeMetricsProvider(),
        cooldown_seconds=60,
    )
    first.refresh()

    second = _service(
        tmp_path,
        clock=FakeClock(now + timedelta(seconds=10)),
        provider=FakeMetricsProvider(error=RuntimeError("unused")),
        cooldown_seconds=60,
    )
    loaded = FilesystemWidgetSnapshotRepository(tmp_path).load()
    latest = second.get_latest()
    cooldown = second.refresh()

    assert loaded.last_successful_refresh_at == now
    assert loaded.payload.sleep_score == 84
    assert latest.refresh_status == RefreshStatus.CACHE_HIT
    assert cooldown.refresh_status == RefreshStatus.COOLDOWN


def test_corrupt_snapshot_is_not_valid_fallback_or_cooldown(tmp_path: Path) -> None:
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.path().parent.mkdir(parents=True, exist_ok=True)
    repo.path().write_text("{bad", encoding="utf-8")
    provider = FakeMetricsProvider(error=RuntimeError("upstream down"))
    service = _service(
        tmp_path,
        clock=FakeClock(datetime(2026, 7, 28, 6, 0, tzinfo=UTC)),
        provider=provider,
        cooldown_seconds=60,
    )

    with pytest.raises(RefreshFailedError):
        service.refresh()


def test_system_clock_returns_aware_utc() -> None:
    value = SystemClock().now()
    assert value.tzinfo is not None
    assert value.utcoffset() == timedelta(0)

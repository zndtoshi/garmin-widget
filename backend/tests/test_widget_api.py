from __future__ import annotations

import json
import os
import threading
import time
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.api.auth import tokens_match
from app.core.config import clear_settings_cache
from app.main import create_app
from app.models.domain import DailyMetrics, HrvTrendPointInternal
from app.models.widget import RefreshStatus, WidgetResponse
from app.persistence.coordinator import clear_refresh_locks
from app.persistence.models import WidgetSnapshot
from app.persistence.snapshot import FilesystemWidgetSnapshotRepository
from app.services.factory import get_widget_refresh_service
from app.services.refresh import WidgetRefreshService

TOKEN = "widget-test-token"


@pytest.fixture(autouse=True)
def _reset_locks() -> None:
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


def _sample_snapshot(*, refreshed_at: datetime, sleep_score: int = 84) -> WidgetSnapshot:
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
    def __init__(self, *, error: Exception | None = None) -> None:
        self.error = error
        self.calls: list[date] = []
        self.block_until: threading.Event | None = None
        self.started: threading.Event | None = None

    def fetch_daily_metrics(
        self,
        metric_date: date,
        *,
        previous_hrv_trend: list[HrvTrendPointInternal] | None = None,
    ) -> DailyMetrics:
        self.calls.append(metric_date)
        if self.started is not None:
            self.started.set()
        if self.block_until is not None:
            assert self.block_until.wait(timeout=5)
        if self.error is not None:
            raise self.error
        return DailyMetrics(
            metric_date=metric_date,
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


def _auth_headers(token: str = TOKEN) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _configured_client(
    tmp_path: Path,
    *,
    token: str | None = TOKEN,
    service: WidgetRefreshService | None = None,
) -> TestClient:
    clear_settings_cache()
    if token is None:
        os.environ.pop("GARMIN_WIDGET_WIDGET_BEARER_TOKEN", None)
    else:
        os.environ["GARMIN_WIDGET_WIDGET_BEARER_TOKEN"] = token
    os.environ["GARMIN_WIDGET_DATA_DIR"] = str(tmp_path)
    os.environ["GARMIN_WIDGET_TIMEZONE"] = "Europe/Bucharest"
    os.environ["GARMIN_WIDGET_REFRESH_COOLDOWN_SECONDS"] = "60"

    app = create_app()
    if service is not None:
        app.dependency_overrides[get_widget_refresh_service] = lambda: service
    return TestClient(app)


def _service(
    tmp_path: Path,
    *,
    clock: FakeClock,
    provider: FakeMetricsProvider,
) -> WidgetRefreshService:
    return WidgetRefreshService(
        clock=clock,
        metrics_provider=provider,
        snapshot=FilesystemWidgetSnapshotRepository(tmp_path),
        cooldown_seconds=60,
        timezone_name="Europe/Bucharest",
    )


def test_health_without_auth_and_without_persistence(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: list[str] = []

    def boom_load(self: FilesystemWidgetSnapshotRepository) -> object:
        calls.append("load")
        raise AssertionError("snapshot must not be read by /health")

    monkeypatch.setattr(FilesystemWidgetSnapshotRepository, "load", boom_load)
    client = _configured_client(tmp_path)

    response = client.get("/health")

    assert response.status_code == 200
    assert calls == []


@pytest.mark.parametrize(
    "headers",
    [
        None,
        {},
        {"Authorization": "Bearer"},
        {"Authorization": "Bearer "},
        {"Authorization": "Basic abc"},
        {"Authorization": f"Bearer {TOKEN}-wrong"},
        {"Authorization": "bearer"},
    ],
)
def test_widget_auth_failures_are_indistinguishable(
    tmp_path: Path, headers: dict[str, str] | None
) -> None:
    client = _configured_client(tmp_path)
    response = client.get("/api/v1/widget/latest", headers=headers)

    assert response.status_code == 401
    assert response.json() == {"detail": "Unauthorized"}
    assert response.headers.get("www-authenticate") == "Bearer"


def test_correct_token_succeeds_for_latest(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    FilesystemWidgetSnapshotRepository(tmp_path).save(
        _sample_snapshot(refreshed_at=now)
    )
    client = _configured_client(tmp_path)

    response = client.get("/api/v1/widget/latest", headers=_auth_headers())

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/json")
    body = response.json()
    assert body["schemaVersion"] == 1
    assert body["refreshStatus"] == "CACHE_HIT"
    assert body["stale"] is False
    assert body["sleepScore"] == 84
    assert "dailySleepDTO" not in body


def test_tokens_match_uses_compare_digest(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[bytes, bytes]] = []

    def fake_compare(a: bytes | str, b: bytes | str) -> bool:
        left = a.encode("utf-8") if isinstance(a, str) else a
        right = b.encode("utf-8") if isinstance(b, str) else b
        calls.append((left, right))
        return left == right

    monkeypatch.setattr("app.api.auth.secrets.compare_digest", fake_compare)

    assert tokens_match("same-token", "same-token") is True
    assert tokens_match("short", "longer-token") is False
    assert calls
    assert any(left == right for left, right in calls)


def test_unset_server_token_returns_sanitized_503(tmp_path: Path) -> None:
    client = _configured_client(tmp_path, token=None)

    response = client.get(
        "/api/v1/widget/latest",
        headers=_auth_headers("any-token"),
    )

    assert response.status_code == 503
    assert response.json() == {"detail": "Widget authentication is not configured."}
    assert TOKEN not in response.text
    assert "any-token" not in response.text


def test_latest_makes_zero_garmin_calls(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    FilesystemWidgetSnapshotRepository(tmp_path).save(
        _sample_snapshot(refreshed_at=now)
    )
    provider = FakeMetricsProvider(error=RuntimeError("should not run"))
    service = _service(tmp_path, clock=FakeClock(now), provider=provider)
    client = _configured_client(tmp_path, service=service)

    response = client.get("/api/v1/widget/latest", headers=_auth_headers())

    assert response.status_code == 200
    assert response.json()["refreshStatus"] == "CACHE_HIT"
    assert provider.calls == []


def test_latest_no_data_and_corrupt_mappings(tmp_path: Path) -> None:
    client = _configured_client(tmp_path)
    missing = client.get("/api/v1/widget/latest", headers=_auth_headers())
    assert missing.status_code == 404
    assert missing.json() == {"detail": "No widget data is available."}

    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.path().parent.mkdir(parents=True, exist_ok=True)
    repo.path().write_text("{bad", encoding="utf-8")
    corrupt = client.get("/api/v1/widget/latest", headers=_auth_headers())
    assert corrupt.status_code == 503
    assert corrupt.json() == {"detail": "Widget data is temporarily unavailable."}
    assert str(tmp_path) not in corrupt.text
    assert "{bad" not in corrupt.text


def test_refresh_success_json_contract(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    provider = FakeMetricsProvider()
    service = _service(tmp_path, clock=FakeClock(now), provider=provider)
    client = _configured_client(tmp_path, service=service)

    response = client.post("/api/v1/widget/refresh", headers=_auth_headers())

    assert response.status_code == 200
    body = response.json()
    assert body["schemaVersion"] == 1
    assert body["refreshStatus"] == "SUCCESS"
    assert body["stale"] is False
    assert body["date"] == "2026-07-28"
    assert body["source"] == "garmin-connect-unofficial"
    assert body["refreshedAt"].endswith("Z")
    assert provider.calls == [date(2026, 7, 28)]


def test_refresh_cooldown_skips_garmin(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    FilesystemWidgetSnapshotRepository(tmp_path).save(
        _sample_snapshot(refreshed_at=now - timedelta(seconds=30))
    )
    provider = FakeMetricsProvider()
    service = _service(tmp_path, clock=FakeClock(now), provider=provider)
    client = _configured_client(tmp_path, service=service)

    response = client.post("/api/v1/widget/refresh", headers=_auth_headers())

    assert response.status_code == 200
    assert response.json()["refreshStatus"] == "COOLDOWN"
    assert response.json()["stale"] is False
    assert provider.calls == []


def test_refresh_stale_fallback_returns_200(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    previous = now - timedelta(seconds=120)
    FilesystemWidgetSnapshotRepository(tmp_path).save(
        _sample_snapshot(refreshed_at=previous, sleep_score=71)
    )
    provider = FakeMetricsProvider(error=RuntimeError("upstream down"))
    service = _service(tmp_path, clock=FakeClock(now), provider=provider)
    client = _configured_client(tmp_path, service=service)

    response = client.post("/api/v1/widget/refresh", headers=_auth_headers())

    assert response.status_code == 200
    body = response.json()
    assert body["refreshStatus"] == "UPSTREAM_UNAVAILABLE"
    assert body["stale"] is True
    assert body["sleepScore"] == 71
    assert "upstream down" not in response.text


def test_refresh_failure_without_cache_returns_503(tmp_path: Path) -> None:
    provider = FakeMetricsProvider(error=RuntimeError("upstream down"))
    service = _service(
        tmp_path,
        clock=FakeClock(datetime(2026, 7, 28, 6, 0, tzinfo=UTC)),
        provider=provider,
    )
    client = _configured_client(tmp_path, service=service)

    response = client.post("/api/v1/widget/refresh", headers=_auth_headers())

    assert response.status_code == 503
    assert response.json() == {
        "detail": "Widget refresh is temporarily unavailable."
    }
    assert "upstream down" not in response.text


def test_persistence_failure_falls_back_without_leaking(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    previous = now - timedelta(seconds=120)
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.save(_sample_snapshot(refreshed_at=previous, sleep_score=71))
    before = repo.path().read_bytes()

    def boom(*_args: object, **_kwargs: object) -> None:
        raise OSError(f"disk full at {tmp_path}")

    monkeypatch.setattr("app.persistence.snapshot.write_text_atomic", boom)
    provider = FakeMetricsProvider()
    service = _service(tmp_path, clock=FakeClock(now), provider=provider)
    client = _configured_client(tmp_path, service=service)

    response = client.post("/api/v1/widget/refresh", headers=_auth_headers())

    assert response.status_code == 200
    assert response.json()["refreshStatus"] == "UPSTREAM_UNAVAILABLE"
    assert response.json()["stale"] is True
    assert repo.path().read_bytes() == before
    assert str(tmp_path) not in response.text
    assert "disk full" not in response.text


def test_simultaneous_api_refresh_calls_garmin_once(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    provider = FakeMetricsProvider()
    provider.started = threading.Event()
    provider.block_until = threading.Event()
    service = _service(tmp_path, clock=FakeClock(now), provider=provider)
    client = _configured_client(tmp_path, service=service)

    results: list[int] = []
    bodies: list[dict[str, object]] = []

    def worker() -> None:
        response = client.post("/api/v1/widget/refresh", headers=_auth_headers())
        results.append(response.status_code)
        bodies.append(response.json())

    first = threading.Thread(target=worker)
    second = threading.Thread(target=worker)
    first.start()
    assert provider.started.wait(timeout=5)
    second.start()
    time.sleep(0.05)
    provider.block_until.set()
    first.join(timeout=5)
    second.join(timeout=5)

    assert results == [200, 200]
    assert len(provider.calls) == 1
    statuses = {body["refreshStatus"] for body in bodies}
    assert "SUCCESS" in statuses
    assert statuses <= {"SUCCESS", "COOLDOWN"}


def test_transient_api_statuses_do_not_mutate_snapshot(tmp_path: Path) -> None:
    now = datetime(2026, 7, 28, 6, 0, tzinfo=UTC)
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.save(_sample_snapshot(refreshed_at=now - timedelta(seconds=10)))
    before = repo.path().read_bytes()
    service = _service(
        tmp_path, clock=FakeClock(now), provider=FakeMetricsProvider()
    )
    client = _configured_client(tmp_path, service=service)

    cooldown = client.post("/api/v1/widget/refresh", headers=_auth_headers())
    latest = client.get("/api/v1/widget/latest", headers=_auth_headers())

    assert cooldown.json()["refreshStatus"] == "COOLDOWN"
    assert latest.json()["refreshStatus"] == "CACHE_HIT"
    assert repo.path().read_bytes() == before
    assert json.loads(before)["payload"]["refreshStatus"] == "SUCCESS"


def test_openapi_documents_bearer_for_widget_not_health(tmp_path: Path) -> None:
    client = _configured_client(tmp_path)
    openapi = client.get("/openapi.json").json()

    assert openapi["components"]["securitySchemes"]["WidgetBearer"]["scheme"] == "bearer"
    assert openapi["paths"]["/api/v1/widget/latest"]["get"]["security"] == [
        {"WidgetBearer": []}
    ]
    assert openapi["paths"]["/api/v1/widget/refresh"]["post"]["security"] == [
        {"WidgetBearer": []}
    ]
    assert "security" not in openapi["paths"]["/health"]["get"]
    assert TOKEN not in json.dumps(openapi)


def test_history_endpoint_absent(tmp_path: Path) -> None:
    client = _configured_client(tmp_path)
    response = client.get("/api/v1/widget/history", headers=_auth_headers())
    assert response.status_code == 404


def test_responses_and_logs_do_not_leak_secrets(
    tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
    client = _configured_client(tmp_path)
    with caplog.at_level("DEBUG"):
        response = client.get(
            "/api/v1/widget/latest",
            headers=_auth_headers(f"{TOKEN}-wrong"),
        )

    joined = "\n".join(record.getMessage() for record in caplog.records)
    assert response.status_code == 401
    assert TOKEN not in response.text
    assert TOKEN not in joined
    assert "Authorization" not in joined

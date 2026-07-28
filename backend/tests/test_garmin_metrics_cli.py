from __future__ import annotations

import json
from datetime import UTC, date, datetime
from pathlib import Path

import pytest
from pydantic import SecretStr

from app.core.config import Settings
from app.garmin import metrics_check
from app.garmin.errors import GarminAuthenticationFailedError
from app.models.domain import DailyMetrics
from app.models.widget import WidgetResponse
from tests.garmin_fakes import FakeGarminClient


def test_metrics_check_cli_prints_normalized_json(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    settings = Settings(
        data_dir=tmp_path,
        timezone="Europe/Bucharest",
        garmin_username="secret-user@example.com",
        garmin_password=SecretStr("super-secret-password"),
    )
    metrics = DailyMetrics(
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
    client = FakeGarminClient(
        username="secret-user@example.com",
        password="super-secret-password",
    )

    class FakeManager:
        def initialize_session(self) -> FakeGarminClient:
            return client

    class FakeAdapter:
        def __init__(self, garmin: object) -> None:
            self.garmin = garmin

        def fetch_daily_metrics(self, metric_date: date) -> DailyMetrics:
            assert metric_date == date(2026, 7, 28)
            return metrics

    monkeypatch.setattr(metrics_check, "get_settings", lambda: settings)
    monkeypatch.setattr(
        metrics_check, "build_session_manager", lambda: FakeManager()
    )
    monkeypatch.setattr(metrics_check, "GarminMetricsAdapter", FakeAdapter)
    monkeypatch.setattr(
        metrics_check,
        "normalize_daily_metrics",
        lambda value: WidgetResponse(
            schemaVersion=1,
            date=value.metric_date,
            sleepScore=value.sleep_score,
            sleepDurationSeconds=value.sleep_duration_seconds,
            overnightHrv=value.overnight_hrv,
            hrvStatus=value.hrv_status,
            bodyBattery=value.body_battery,
            restingHeartRate=value.resting_heart_rate,
            stress=value.stress,
            trainingReadiness=value.training_readiness,
            garminSyncAt=value.garmin_sync_at,
            refreshedAt=datetime(2026, 7, 28, 5, 36, 4, tzinfo=UTC),
            stale=False,
            refreshStatus="SUCCESS",
            source="garmin-connect-unofficial",
        ),
    )

    exit_code = metrics_check.main(["--date", "2026-07-28"])
    captured = capsys.readouterr()
    payload = json.loads(captured.out)

    assert exit_code == 0
    assert payload["schemaVersion"] == 1
    assert payload["sleepScore"] == 84
    assert "super-secret-password" not in captured.out
    assert "secret-user@example.com" not in captured.out
    assert "dailySleepDTO" not in captured.out
    assert captured.err == ""


def test_metrics_check_defaults_to_timezone_today(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        metrics_check,
        "calendar_date_for_timezone",
        lambda timezone_name: date(2026, 7, 28)
        if timezone_name == "Europe/Bucharest"
        else date(2026, 1, 1),
    )
    assert metrics_check.resolve_metric_date(None, "Europe/Bucharest") == date(
        2026, 7, 28
    )


def test_metrics_check_cli_sanitizes_session_failure(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    settings = Settings(timezone="Europe/Bucharest")

    class FakeManager:
        def initialize_session(self) -> object:
            raise GarminAuthenticationFailedError(
                "Garmin authentication failed."
            )

    monkeypatch.setattr(metrics_check, "get_settings", lambda: settings)
    monkeypatch.setattr(
        metrics_check, "build_session_manager", lambda: FakeManager()
    )

    exit_code = metrics_check.main(["--date", "2026-07-28"])
    captured = capsys.readouterr()

    assert exit_code == 1
    assert captured.out.strip() == (
        "Garmin metrics check failed: Garmin authentication failed."
    )
    assert captured.err == ""


def test_metrics_check_cli_sanitizes_config_failure(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    def boom() -> Settings:
        raise ValueError(
            "GARMIN_WIDGET_WIDGET_BEARER_TOKEN=super-secret-password leaked"
        )

    monkeypatch.setattr(metrics_check, "get_settings", boom)

    exit_code = metrics_check.main([])
    captured = capsys.readouterr()

    assert exit_code == 1
    assert captured.out.strip() == (
        "Garmin metrics check failed: invalid configuration or date."
    )
    assert "super-secret-password" not in captured.out
    assert "GARMIN_WIDGET_WIDGET_BEARER_TOKEN" not in captured.out
    assert captured.err == ""


def test_metrics_check_cli_sanitizes_unexpected_failure(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    settings = Settings(timezone="Europe/Bucharest")

    class FakeManager:
        def initialize_session(self) -> object:
            raise RuntimeError(
                "token=abc123 cookie=session-cookie password=super-secret-password"
            )

    monkeypatch.setattr(metrics_check, "get_settings", lambda: settings)
    monkeypatch.setattr(
        metrics_check, "build_session_manager", lambda: FakeManager()
    )

    exit_code = metrics_check.main(["--date", "2026-07-28"])
    captured = capsys.readouterr()

    assert exit_code == 1
    assert captured.out.strip() == (
        "Garmin metrics check failed: unexpected error."
    )
    assert "abc123" not in captured.out
    assert "session-cookie" not in captured.out
    assert "super-secret-password" not in captured.out
    assert "RuntimeError" not in captured.out
    assert captured.err == ""

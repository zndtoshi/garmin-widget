from __future__ import annotations

import json
import math
from datetime import UTC, date, datetime, timedelta, timezone
from pathlib import Path

import pytest
from garminconnect import (
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)
from pydantic import ValidationError

from app.garmin.adapter import GarminMetricsAdapter, _as_int
from app.garmin.dates import calendar_date_for_timezone, parse_metric_date
from app.garmin.errors import (
    GarminAuthenticationFailedError,
    GarminNetworkError,
    GarminRateLimitError,
    GarminUpstreamError,
)
from app.garmin.normalize import normalize_daily_metrics
from app.models.domain import DailyMetrics
from app.models.widget import RefreshStatus, WidgetResponse
from tests.garmin_fakes import FakeMetricsClient

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "garmin"


def _load_fixture(name: str) -> object:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def _complete_client(**overrides: object) -> FakeMetricsClient:
    defaults: dict[str, object] = {
        "sleep": _load_fixture("sleep_complete.json"),
        "hrv": _load_fixture("hrv_complete.json"),
        "body_battery": _load_fixture("body_battery_complete.json"),
        "rhr": _load_fixture("rhr_complete.json"),
        "stress": _load_fixture("stress_complete.json"),
        "stats": _load_fixture("stats_complete.json"),
        "training_readiness": _load_fixture("training_readiness_complete.json"),
        "device_last_used": _load_fixture("device_last_used_complete.json"),
    }
    defaults.update(overrides)
    return FakeMetricsClient(**defaults)


def test_adapter_extracts_complete_metrics() -> None:
    metrics = GarminMetricsAdapter(_complete_client()).fetch_daily_metrics(
        date(2026, 7, 28)
    )

    assert metrics == DailyMetrics(
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


def test_adapter_reads_last_used_device_upload_time() -> None:
    client = _complete_client(
        device_last_used={
            "lastUsedDeviceName": "test-device",
            "lastUsedDeviceUploadTime": "2026-07-28T05:35:00.000Z",
        }
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))
    payload = normalize_daily_metrics(
        metrics,
        refreshed_at=datetime(2026, 7, 28, 5, 36, 4, tzinfo=UTC),
    )

    assert metrics.garmin_sync_at == datetime(2026, 7, 28, 5, 35, tzinfo=UTC)
    assert payload.model_dump(by_alias=True, mode="json")["garminSyncAt"] == (
        "2026-07-28T05:35:00Z"
    )


def test_adapter_still_accepts_legacy_device_timestamp_keys() -> None:
    client = _complete_client(
        device_last_used={
            "lastUsedDeviceDownloadDate": "2026-07-28T04:10:00.000Z",
        }
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))
    assert metrics.garmin_sync_at == datetime(2026, 7, 28, 4, 10, tzinfo=UTC)


def test_adapter_preserves_zero_metric_values() -> None:
    client = _complete_client(sleep=_load_fixture("sleep_zeros.json"))
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))

    assert metrics.sleep_score == 0
    assert metrics.sleep_duration_seconds == 0


def test_adapter_returns_none_for_missing_and_malformed_fields() -> None:
    client = _complete_client(
        sleep=_load_fixture("sleep_malformed.json"),
        hrv={},
        body_battery=[],
        rhr={},
        stress={},
        stats={},
        training_readiness=[],
        device_last_used=_load_fixture("device_last_used_malformed.json"),
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))

    assert metrics.sleep_score is None
    assert metrics.sleep_duration_seconds is None
    assert metrics.overnight_hrv is None
    assert metrics.hrv_status is None
    assert metrics.body_battery is None
    assert metrics.resting_heart_rate is None
    assert metrics.stress is None
    assert metrics.training_readiness is None
    assert metrics.garmin_sync_at is None


def test_adapter_falls_back_to_stats_for_rhr_and_stress() -> None:
    client = _complete_client(
        rhr={"unexpected": True},
        stress={"unexpected": True},
        stats={"restingHeartRate": 51, "averageStressLevel": 22},
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))

    assert metrics.resting_heart_rate == 51
    assert metrics.stress == 22


def test_adapter_uses_latest_body_battery_level_not_charged() -> None:
    client = _complete_client(
        body_battery=[
            {
                "date": "2026-07-28",
                "charged": 90,
                "bodyBatteryValuesArray": [
                    [1753670400000, 40],
                    [1753684800000, "bad"],
                    [1753699200000, 58],
                ],
            }
        ],
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))
    assert metrics.body_battery == 58


def test_adapter_uses_most_recent_body_battery_field() -> None:
    client = _complete_client(
        body_battery=[{"date": "2026-07-28", "bodyBatteryMostRecentValue": 61}],
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))
    assert metrics.body_battery == 61


def test_adapter_ignores_charged_only_body_battery() -> None:
    client = _complete_client(
        body_battery=[
            {
                "date": "2026-07-28",
                "charged": 65,
                "chargedValue": 70,
                "bodyBatteryChargedValue": 75,
            }
        ],
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))
    assert metrics.body_battery is None


def test_adapter_tolerates_one_unavailable_metric() -> None:
    client = _complete_client(
        errors={
            "get_sleep_data": GarminConnectConnectionError("endpoint unavailable"),
        },
        sleep=None,
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))

    assert metrics.sleep_score is None
    assert metrics.body_battery == 72


def test_adapter_allows_successful_empty_responses() -> None:
    client = FakeMetricsClient(
        sleep={},
        hrv=None,
        body_battery=[],
        rhr={},
        stress={},
        stats={},
        training_readiness=[],
        device_last_used={},
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))

    assert metrics == DailyMetrics(metric_date=date(2026, 7, 28))


def test_adapter_raises_when_all_endpoints_unavailable() -> None:
    unavailable = GarminConnectConnectionError("404")
    client = FakeMetricsClient(
        errors={
            "get_sleep_data": unavailable,
            "get_hrv_data": unavailable,
            "get_body_battery": unavailable,
            "get_rhr_day": unavailable,
            "get_stress_data": unavailable,
            "get_stats": unavailable,
            "get_training_readiness": unavailable,
            "get_device_last_used": unavailable,
        }
    )
    with pytest.raises(GarminUpstreamError, match="unavailable"):
        GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))


def test_adapter_raises_on_unexpected_exception() -> None:
    client = _complete_client(
        errors={"get_sleep_data": RuntimeError("boom with raw body xyz")}
    )
    with pytest.raises(GarminUpstreamError, match="Unexpected Garmin upstream failure"):
        GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))


def test_adapter_raises_on_authentication_failure() -> None:
    client = _complete_client(
        errors={"get_sleep_data": GarminConnectAuthenticationError("auth failed")}
    )
    with pytest.raises(GarminAuthenticationFailedError):
        GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))


def test_adapter_raises_on_rate_limit() -> None:
    client = _complete_client(
        errors={"get_stats": GarminConnectTooManyRequestsError("slow down")}
    )
    with pytest.raises(GarminRateLimitError):
        GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))


def test_adapter_raises_on_network_failure() -> None:
    client = _complete_client(
        errors={
            "get_device_last_used": GarminConnectConnectionError(
                "connection timed out"
            )
        }
    )
    with pytest.raises(GarminNetworkError):
        GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))


@pytest.mark.parametrize(
    "value",
    [
        math.nan,
        math.inf,
        -math.inf,
        "inf",
        "-inf",
        "nan",
        "not-a-number",
        "",
        None,
        True,
        {"nested": 1},
    ],
)
def test_as_int_rejects_malformed_values(value: object) -> None:
    assert _as_int(value) is None


def test_as_int_preserves_zero_and_valid_numbers() -> None:
    assert _as_int(0) == 0
    assert _as_int(0.0) == 0
    assert _as_int("0") == 0
    assert _as_int(49) == 49
    assert _as_int("49.0") == 49


def test_adapter_survives_malformed_numeric_fields() -> None:
    client = _complete_client(
        sleep={
            "dailySleepDTO": {
                "sleepTimeSeconds": math.inf,
                "sleepScores": {"overall": {"value": "nan"}},
            }
        },
        hrv={"hrvSummary": {"lastNightAvg": math.nan, "status": "BALANCED"}},
        stress={"overallStressLevel": "inf"},
        stats={"restingHeartRate": 49, "averageStressLevel": "inf"},
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))

    assert metrics.sleep_score is None
    assert metrics.sleep_duration_seconds is None
    assert metrics.overnight_hrv is None
    assert metrics.hrv_status == "BALANCED"
    assert metrics.stress is None
    assert metrics.body_battery == 72


def test_normalize_maps_to_public_camel_case_schema() -> None:
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
    payload = normalize_daily_metrics(
        metrics,
        refreshed_at=datetime(2026, 7, 28, 5, 36, 4, tzinfo=UTC),
    )
    dumped = payload.model_dump(by_alias=True, mode="json")

    assert dumped == {
        "schemaVersion": 1,
        "date": "2026-07-28",
        "sleepScore": 84,
        "sleepDurationSeconds": 22620,
        "overnightHrv": 47,
        "hrvStatus": "BALANCED",
        "bodyBattery": 72,
        "restingHeartRate": 49,
        "stress": 18,
        "trainingReadiness": 81,
        "garminSyncAt": "2026-07-28T05:35:00Z",
        "refreshedAt": "2026-07-28T05:36:04Z",
        "stale": False,
        "refreshStatus": "SUCCESS",
        "source": "garmin-connect-unofficial",
    }
    assert "dailySleepDTO" not in dumped
    assert "lastNightAvg" not in dumped
    assert "bodyBatteryValuesArray" not in dumped


def test_public_and_internal_models_reject_unknown_fields() -> None:
    with pytest.raises(ValidationError):
        DailyMetrics(metric_date=date(2026, 7, 28), unknown=1)
    with pytest.raises(ValidationError):
        WidgetResponse.model_validate({"schemaVersion": 1, "mystery": True})


def test_public_model_rejects_non_version_one_schema_and_source() -> None:
    with pytest.raises(ValidationError):
        WidgetResponse.model_validate({"schemaVersion": 2})
    with pytest.raises(ValidationError):
        WidgetResponse.model_validate(
            {"schemaVersion": 1, "source": "somewhere-else"}
        )


def test_public_timestamps_normalize_and_serialize_with_z() -> None:
    utc_value = datetime(2026, 7, 28, 5, 35, tzinfo=UTC)
    offset_value = datetime(
        2026, 7, 28, 8, 35, tzinfo=timezone(timedelta(hours=3))
    )
    naive_value = datetime(2026, 7, 28, 5, 36, 4)

    payload = WidgetResponse(
        schemaVersion=1,
        garminSyncAt=offset_value,
        refreshedAt=naive_value,
    )
    dumped = payload.model_dump(by_alias=True, mode="json")

    assert payload.garmin_sync_at == utc_value
    assert payload.refreshed_at == datetime(2026, 7, 28, 5, 36, 4, tzinfo=UTC)
    assert dumped["garminSyncAt"] == "2026-07-28T05:35:00Z"
    assert dumped["refreshedAt"] == "2026-07-28T05:36:04Z"


def test_refresh_status_enum_contains_documented_values() -> None:
    assert {status.value for status in RefreshStatus} == {
        "SUCCESS",
        "CACHE_HIT",
        "COOLDOWN",
        "UPSTREAM_UNAVAILABLE",
        "NO_DATA",
    }


def test_calendar_date_uses_configured_timezone_boundary() -> None:
    # 2026-07-27 22:30 UTC is already 2026-07-28 in Europe/Bucharest (UTC+3).
    now = datetime(2026, 7, 27, 22, 30, tzinfo=UTC)
    assert calendar_date_for_timezone("Europe/Bucharest", now=now) == date(2026, 7, 28)
    assert calendar_date_for_timezone("Europe/Athens", now=now) == date(2026, 7, 28)
    assert calendar_date_for_timezone("UTC", now=now) == date(2026, 7, 27)


def test_parse_metric_date_accepts_iso_date() -> None:
    assert parse_metric_date("2026-07-28") == date(2026, 7, 28)


def test_example_widget_response_matches_public_model(
    backend_dir: Path,
) -> None:
    example_path = backend_dir.parent / "shared" / "widget-response.example.json"
    raw = json.loads(example_path.read_text(encoding="utf-8"))
    model = WidgetResponse.model_validate(raw)
    assert model.schema_version == 1
    assert model.model_dump(by_alias=True, mode="json") == raw

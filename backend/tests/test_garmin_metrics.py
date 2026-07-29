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

from app.garmin.adapter import (
    GarminMetricsAdapter,
    _as_gmt_datetime,
    _as_int,
    _as_non_negative_int,
    _downsample,
    _extract_activity_hr_timeline,
    _extract_activity_id,
    _extract_body_battery_timeline,
    _extract_last_activity,
    _extract_sleep_stages,
    _extract_stress_timeline,
    _sort_dedup_and_downsample,
    _sort_dedup_downsample_hr,
)
from app.garmin.dates import calendar_date_for_timezone, parse_metric_date
from app.garmin.errors import (
    GarminAuthenticationFailedError,
    GarminNetworkError,
    GarminRateLimitError,
    GarminUpstreamError,
)
from app.garmin.normalize import normalize_daily_metrics
from app.models.domain import (
    ActivityHeartRatePointInternal,
    DailyMetrics,
    HrvTrendPointInternal,
    SleepStagesInternal,
    TimelinePointInternal,
)
from app.models.widget import (
    ActivityHeartRatePoint,
    HrvTrendPoint,
    LastActivity,
    RefreshStatus,
    TimelinePoint,
    WidgetResponse,
)
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
        "activities": _load_fixture("activities_complete.json"),
    }
    defaults.update(overrides)
    return FakeMetricsClient(**defaults)


def test_adapter_extracts_complete_metrics() -> None:
    metrics = GarminMetricsAdapter(_complete_client()).fetch_daily_metrics(
        date(2026, 7, 28)
    )

    assert metrics.sleep_score == 84
    assert metrics.sleep_duration_seconds == 22620
    assert metrics.overnight_hrv == 47
    assert metrics.hrv_status == "BALANCED"
    assert metrics.body_battery == 72
    assert metrics.resting_heart_rate == 49
    assert metrics.stress == 18
    assert metrics.training_readiness == 81
    assert metrics.garmin_sync_at == datetime(2026, 7, 28, 5, 35, tzinfo=UTC)


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
        activities=[],
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))

    assert metrics.sleep_score is None
    assert metrics.sleep_duration_seconds is None
    assert metrics.sleep_stages is None
    assert metrics.overnight_hrv is None
    assert metrics.hrv_status is None
    assert metrics.body_battery is None
    assert metrics.body_battery_timeline is None
    assert metrics.resting_heart_rate is None
    assert metrics.stress is None
    assert metrics.stress_timeline is None
    assert metrics.training_readiness is None
    assert metrics.last_activity is None
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
        activities=[],
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))

    assert metrics.sleep_score is None
    assert metrics.body_battery is None
    assert metrics.last_activity is None


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
            "get_activities": unavailable,
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

    assert dumped["schemaVersion"] == 1
    assert dumped["sleepScore"] == 84
    assert dumped["sleepDurationSeconds"] == 22620
    assert dumped["sleepStages"] is None
    assert dumped["overnightHrv"] == 47
    assert dumped["hrvStatus"] == "BALANCED"
    assert dumped["hrvTrend"] is None
    assert dumped["bodyBattery"] == 72
    assert dumped["bodyBatteryTimeline"] is None
    assert dumped["stressTimeline"] is None
    assert dumped["lastActivity"] is None
    assert dumped["refreshedAt"] == "2026-07-28T05:36:04Z"
    assert dumped["refreshStatus"] == "SUCCESS"
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


# --- Sleep stages ---


def test_extract_sleep_stages_complete() -> None:
    payload = {
        "dailySleepDTO": {
            "deepSleepSeconds": 5400,
            "lightSleepSeconds": 10800,
            "remSleepSeconds": 4200,
            "awakeSleepSeconds": 2220,
        }
    }
    stages = _extract_sleep_stages(payload)
    assert stages is not None
    assert stages.deep_seconds == 5400
    assert stages.light_seconds == 10800
    assert stages.rem_seconds == 4200
    assert stages.awake_seconds == 2220


def test_extract_sleep_stages_partial() -> None:
    payload = {
        "dailySleepDTO": {
            "deepSleepSeconds": 3600,
        }
    }
    stages = _extract_sleep_stages(payload)
    assert stages is not None
    assert stages.deep_seconds == 3600
    assert stages.light_seconds is None
    assert stages.rem_seconds is None
    assert stages.awake_seconds is None


def test_extract_sleep_stages_all_missing() -> None:
    assert _extract_sleep_stages({"dailySleepDTO": {}}) is None
    assert _extract_sleep_stages({}) is None
    assert _extract_sleep_stages(None) is None


def test_adapter_extracts_sleep_stages_from_complete_fixture() -> None:
    metrics = GarminMetricsAdapter(_complete_client()).fetch_daily_metrics(
        date(2026, 7, 28)
    )
    assert metrics.sleep_stages is not None
    assert metrics.sleep_stages.deep_seconds == 5400
    assert metrics.sleep_stages.light_seconds == 10800
    assert metrics.sleep_stages.rem_seconds == 4200
    assert metrics.sleep_stages.awake_seconds == 2220


# --- HRV trend ---


def test_hrv_initial_backfill() -> None:
    hrv_by_date = {}
    for i in range(7):
        d = date(2026, 7, 22) + timedelta(days=i)
        hrv_by_date[d.isoformat()] = {
            "hrvSummary": {
                "lastNightAvg": 40 + i,
                "weeklyAvg": 42 + i,
                "status": "BALANCED",
            }
        }
    client = _complete_client(hrv_by_date=hrv_by_date)
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(
        date(2026, 7, 28), previous_hrv_trend=None,
    )
    hrv_calls = [c for c in client.calls if c[0] == "get_hrv_data"]
    assert metrics.hrv_trend is not None
    assert len(metrics.hrv_trend) == 7
    assert metrics.hrv_trend[0].date == date(2026, 7, 22)
    assert metrics.hrv_trend[-1].date == date(2026, 7, 28)
    assert len(client.calls) == 15
    assert len(hrv_calls) == 7


def test_hrv_same_day_reuse_no_refetch() -> None:
    existing_trend = [
        HrvTrendPointInternal(
            date=date(2026, 7, 27), overnight_average=45,
            seven_day_average=44, status="BALANCED",
        ),
        HrvTrendPointInternal(
            date=date(2026, 7, 28), overnight_average=40,
            seven_day_average=43, status="BALANCED",
        ),
    ]
    client = _complete_client()
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(
        date(2026, 7, 28), previous_hrv_trend=existing_trend,
    )
    hrv_calls = [c for c in client.calls if c[0] == "get_hrv_data"]
    assert len(client.calls) == 9
    assert len(hrv_calls) == 1
    assert metrics.hrv_trend is not None
    assert len(metrics.hrv_trend) == 2
    assert metrics.hrv_trend[-1].date == date(2026, 7, 28)
    assert metrics.hrv_trend[-1].overnight_average == 47


def test_hrv_seven_day_trimming() -> None:
    old_trend = [
        HrvTrendPointInternal(
            date=date(2026, 7, 20), overnight_average=30,
            seven_day_average=30, status="LOW",
        ),
        HrvTrendPointInternal(
            date=date(2026, 7, 22), overnight_average=35,
            seven_day_average=33, status="BALANCED",
        ),
    ]
    client = _complete_client()
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(
        date(2026, 7, 28), previous_hrv_trend=old_trend,
    )
    assert metrics.hrv_trend is not None
    for p in metrics.hrv_trend:
        assert p.date >= date(2026, 7, 22)
    assert any(p.date == date(2026, 7, 28) for p in metrics.hrv_trend)


def test_hrv_missing_dates_do_not_fail() -> None:
    client = _complete_client(hrv={})
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(
        date(2026, 7, 28), previous_hrv_trend=None,
    )
    assert metrics.hrv_trend is None


# --- Body Battery timeline ---


def test_body_battery_timeline_extraction() -> None:
    payload = [
        {
            "date": "2026-07-28",
            "bodyBatteryValuesArray": [
                [1753660800000, 50],
                [1753664400000, 65],
                [1753668000000, 72],
            ],
        }
    ]
    timeline = _extract_body_battery_timeline(payload)
    assert timeline is not None
    assert len(timeline) == 3
    assert timeline[0].value == 50
    assert timeline[-1].value == 72


def test_body_battery_timeline_filters_invalid() -> None:
    payload = [
        {
            "date": "2026-07-28",
            "bodyBatteryValuesArray": [
                [1753660800000, 50],
                [1753664400000, -1],
                [1753668000000, 101],
                ["bad", 50],
                [1753671600000, "nan"],
                [1753675200000, 80],
            ],
        }
    ]
    timeline = _extract_body_battery_timeline(payload)
    assert timeline is not None
    assert len(timeline) == 2
    assert timeline[0].value == 50
    assert timeline[1].value == 80


def test_body_battery_timeline_downsamples_to_48() -> None:
    values = [[1753660800000 + i * 60000, i % 100] for i in range(100)]
    payload = [{"date": "2026-07-28", "bodyBatteryValuesArray": values}]
    timeline = _extract_body_battery_timeline(payload)
    assert timeline is not None
    assert len(timeline) == 48
    assert timeline[0].value == values[0][1]
    assert timeline[-1].value == values[-1][1]


def test_body_battery_timeline_empty() -> None:
    assert _extract_body_battery_timeline([]) is None
    assert _extract_body_battery_timeline([{"bodyBatteryValuesArray": []}]) is None
    assert _extract_body_battery_timeline(None) is None


# --- Stress timeline ---


def test_stress_timeline_extraction() -> None:
    payload = {
        "overallStressLevel": 20,
        "stressValuesArray": [
            [1753660800000, 15],
            [1753664400000, 22],
        ],
    }
    timeline = _extract_stress_timeline(payload)
    assert timeline is not None
    assert len(timeline) == 2
    assert timeline[0].value == 15


def test_stress_timeline_filters_sentinels() -> None:
    payload = {
        "stressValuesArray": [
            [1753660800000, 15],
            [1753664400000, -1],
            [1753668000000, 101],
            [1753671600000, 50],
        ],
    }
    timeline = _extract_stress_timeline(payload)
    assert timeline is not None
    assert len(timeline) == 2


def test_stress_timeline_empty() -> None:
    assert _extract_stress_timeline({}) is None
    assert _extract_stress_timeline(None) is None
    assert _extract_stress_timeline({"stressValuesArray": []}) is None


# --- Downsample ---


def test_downsample_preserves_first_and_last() -> None:
    base = datetime(2026, 7, 28, 0, 0, tzinfo=UTC)
    points = [
        TimelinePointInternal(
            timestamp=base + timedelta(minutes=i), value=i,
        )
        for i in range(100)
    ]
    result = _downsample(points, max_points=10)
    assert len(result) == 10
    assert result[0].value == 0
    assert result[-1].value == 99


def test_downsample_noop_under_limit() -> None:
    base = datetime(2026, 7, 28, 0, 0, tzinfo=UTC)
    points = [
        TimelinePointInternal(
            timestamp=base + timedelta(minutes=i), value=i,
        )
        for i in range(5)
    ]
    assert _downsample(points, max_points=48) is points


# --- Last activity ---


def test_extract_last_activity_running() -> None:
    payload = [
        {
            "activityName": "Morning Run",
            "activityType": {"typeKey": "running"},
            "startTimeGMT": "2026-07-28T05:00:00.000Z",
            "duration": 2400,
            "movingDuration": 2350,
            "distance": 5120.5,
            "calories": 380,
            "averageHR": 148,
            "maxHR": 172,
            "elevationGain": 45.0,
            "averageSpeed": 2.13,
            "aerobicTrainingEffect": 3.2,
            "anaerobicTrainingEffect": 1.1,
            "activityTrainingLoad": 85.0,
        }
    ]
    activity = _extract_last_activity(payload)
    assert activity is not None
    assert activity.name == "Morning Run"
    assert activity.type_key == "running"
    assert activity.duration_seconds == 2400
    assert activity.distance_meters == 5120.5
    assert activity.average_heart_rate == 148
    assert activity.aerobic_training_effect == 3.2
    assert activity.training_load == 85.0


def test_extract_last_activity_strength_missing_fields() -> None:
    payload = [
        {
            "activityName": "Strength",
            "activityType": {"typeKey": "strength_training"},
            "startTimeGMT": "2026-07-28T06:00:00.000Z",
            "duration": 3600,
            "calories": 250,
            "averageHR": 120,
            "maxHR": 155,
        }
    ]
    activity = _extract_last_activity(payload)
    assert activity is not None
    assert activity.type_key == "strength_training"
    assert activity.distance_meters is None
    assert activity.elevation_gain_meters is None
    assert activity.average_speed_meters_per_second is None


def test_extract_last_activity_empty_list() -> None:
    assert _extract_last_activity([]) is None


def test_extract_last_activity_unavailable() -> None:
    assert _extract_last_activity(None) is None


def test_extract_last_activity_no_sensitive_fields() -> None:
    payload = [
        {
            "activityName": "Run",
            "activityType": {"typeKey": "running"},
            "startTimeGMT": "2026-07-28T05:00:00.000Z",
            "duration": 1200,
            "ownerDisplayName": "secret_user",
            "ownerId": 12345,
            "startLatitude": 44.4268,
            "startLongitude": 26.1025,
            "description": "Private notes about my run",
            "activityId": 9999999,
        }
    ]
    activity = _extract_last_activity(payload)
    assert activity is not None
    dumped = activity.model_dump()
    assert "ownerDisplayName" not in dumped
    assert "ownerId" not in dumped
    assert "startLatitude" not in dumped
    assert "startLongitude" not in dumped
    assert "description" not in dumped
    assert "activityId" not in dumped


# --- Normalize with new fields ---


def test_normalize_with_expanded_fields() -> None:
    metrics = DailyMetrics(
        metric_date=date(2026, 7, 28),
        sleep_score=84,
        sleep_duration_seconds=22620,
        sleep_stages=SleepStagesInternal(
            deep_seconds=5400, light_seconds=10800,
            rem_seconds=4200, awake_seconds=2220,
        ),
        overnight_hrv=47,
        hrv_status="BALANCED",
        hrv_trend=[
            HrvTrendPointInternal(
                date=date(2026, 7, 28), overnight_average=47,
                seven_day_average=46, status="BALANCED",
            ),
        ],
        body_battery=72,
        body_battery_timeline=[
            TimelinePointInternal(
                timestamp=datetime(2026, 7, 28, 0, 0, tzinfo=UTC), value=50,
            ),
        ],
        stress=18,
        stress_timeline=[
            TimelinePointInternal(
                timestamp=datetime(2026, 7, 28, 1, 0, tzinfo=UTC), value=15,
            ),
        ],
        training_readiness=81,
        garmin_sync_at=datetime(2026, 7, 28, 5, 35, tzinfo=UTC),
    )
    payload = normalize_daily_metrics(
        metrics,
        refreshed_at=datetime(2026, 7, 28, 5, 36, 4, tzinfo=UTC),
    )
    dumped = payload.model_dump(by_alias=True, mode="json")

    assert dumped["sleepStages"]["deepSeconds"] == 5400
    assert dumped["hrvTrend"][0]["overnightAverage"] == 47
    assert dumped["bodyBatteryTimeline"][0]["value"] == 50
    assert dumped["stressTimeline"][0]["value"] == 15


# --- Old client compatibility ---


def test_old_client_can_parse_expanded_response() -> None:
    """v0.1.0 Android parser ignores unknown fields via additive contract."""
    expanded = {
        "schemaVersion": 1,
        "date": "2026-07-28",
        "sleepScore": 84,
        "sleepDurationSeconds": 22620,
        "sleepStages": {"deepSeconds": 5400},
        "overnightHrv": 47,
        "hrvStatus": "BALANCED",
        "hrvTrend": [{"date": "2026-07-28", "overnightAverage": 47}],
        "bodyBattery": 72,
        "bodyBatteryTimeline": [{"timestamp": "2026-07-28T00:00:00Z", "value": 50}],
        "restingHeartRate": 49,
        "stress": 18,
        "stressTimeline": None,
        "trainingReadiness": 81,
        "lastActivity": None,
        "garminSyncAt": "2026-07-28T05:35:00Z",
        "refreshedAt": "2026-07-28T05:36:04Z",
        "stale": False,
        "refreshStatus": "SUCCESS",
        "source": "garmin-connect-unofficial",
    }
    model = WidgetResponse.model_validate(expanded)
    assert model.schema_version == 1
    assert model.sleep_score == 84
    assert model.sleep_stages is not None
    assert model.sleep_stages.deep_seconds == 5400


def test_nullable_new_fields_serialize_as_null() -> None:
    payload = WidgetResponse(schemaVersion=1)
    dumped = payload.model_dump(by_alias=True, mode="json")
    assert dumped["sleepStages"] is None
    assert dumped["hrvTrend"] is None
    assert dumped["bodyBatteryTimeline"] is None
    assert dumped["stressTimeline"] is None
    assert dumped["lastActivity"] is None


# --- Negative sleep stages ---


def test_extract_sleep_stages_rejects_negative_durations() -> None:
    payload = {
        "dailySleepDTO": {
            "deepSleepSeconds": -100,
            "lightSleepSeconds": 10800,
            "remSleepSeconds": -1,
            "awakeSleepSeconds": 2220,
        }
    }
    stages = _extract_sleep_stages(payload)
    assert stages is not None
    assert stages.deep_seconds is None
    assert stages.light_seconds == 10800
    assert stages.rem_seconds is None
    assert stages.awake_seconds == 2220


def test_extract_sleep_stages_all_negative_returns_none() -> None:
    payload = {
        "dailySleepDTO": {
            "deepSleepSeconds": -1,
            "lightSleepSeconds": -2,
            "remSleepSeconds": -3,
            "awakeSleepSeconds": -4,
        }
    }
    assert _extract_sleep_stages(payload) is None


def test_as_non_negative_int() -> None:
    assert _as_non_negative_int(0) == 0
    assert _as_non_negative_int(5) == 5
    assert _as_non_negative_int(-1) is None
    assert _as_non_negative_int(-100) is None
    assert _as_non_negative_int(None) is None


# --- Timeline sort/dedup ---


def test_sort_dedup_and_downsample_unsorted_input() -> None:
    base = datetime(2026, 7, 28, 0, 0, tzinfo=UTC)
    points = [
        TimelinePointInternal(timestamp=base + timedelta(minutes=30), value=60),
        TimelinePointInternal(timestamp=base + timedelta(minutes=10), value=40),
        TimelinePointInternal(timestamp=base + timedelta(minutes=20), value=50),
    ]
    result = _sort_dedup_and_downsample(points, max_points=48)
    assert len(result) == 3
    assert result[0].value == 40
    assert result[1].value == 50
    assert result[2].value == 60


def test_sort_dedup_and_downsample_duplicate_timestamps() -> None:
    base = datetime(2026, 7, 28, 0, 0, tzinfo=UTC)
    points = [
        TimelinePointInternal(timestamp=base, value=10),
        TimelinePointInternal(timestamp=base, value=20),
        TimelinePointInternal(timestamp=base + timedelta(minutes=10), value=30),
    ]
    result = _sort_dedup_and_downsample(points, max_points=48)
    assert len(result) == 2
    assert result[0].value == 20
    assert result[1].value == 30


def test_body_battery_timeline_sorts_unsorted_input() -> None:
    payload = [
        {
            "date": "2026-07-28",
            "bodyBatteryValuesArray": [
                [1753668000000, 72],
                [1753660800000, 50],
                [1753664400000, 65],
            ],
        }
    ]
    timeline = _extract_body_battery_timeline(payload)
    assert timeline is not None
    assert timeline[0].value == 50
    assert timeline[1].value == 65
    assert timeline[2].value == 72


# --- Activity timestamp correctness ---


def test_as_gmt_datetime_accepts_naive_gmt_string() -> None:
    """Garmin's startTimeGMT may omit Z but still represents a UTC instant."""
    result = _as_gmt_datetime("2026-07-28T05:00:00.000")
    assert result is not None
    assert result.tzinfo is not None
    assert result == datetime(2026, 7, 28, 5, 0, tzinfo=UTC)


def test_as_gmt_datetime_accepts_offset_or_zulu() -> None:
    result = _as_gmt_datetime("2026-07-28T05:00:00.000Z")
    assert result is not None
    assert result.tzinfo is not None
    assert result == datetime(2026, 7, 28, 5, 0, tzinfo=UTC)
    offset_result = _as_gmt_datetime("2026-07-28T08:00:00+03:00")
    assert offset_result == datetime(2026, 7, 28, 5, 0, tzinfo=UTC)


def test_extract_last_activity_local_only_timestamp_not_mislabeled() -> None:
    """startTimeLocal without startTimeGMT must not produce a Z-suffixed instant."""
    payload = [
        {
            "activityName": "Evening Run",
            "activityType": {"typeKey": "running"},
            "startTimeLocal": "2026-07-28T18:30:00.000",
            "duration": 1800,
            "calories": 200,
        }
    ]
    activity = _extract_last_activity(payload)
    assert activity is not None
    assert activity.started_at is None
    assert activity.name == "Evening Run"


def test_extract_last_activity_null_only_returns_none() -> None:
    """An activity dict with no recognized public fields returns None."""
    payload = [{"unknownField": 123}]
    assert _extract_last_activity(payload) is None


# --- Unavailable activities endpoint ---


def test_adapter_unavailable_activities_other_metrics_succeed() -> None:
    client = _complete_client(
        errors={
            "get_activities": GarminConnectConnectionError("404"),
        },
        activities=None,
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))
    assert metrics.last_activity is None
    assert metrics.sleep_score == 84
    assert metrics.body_battery == 72


# --- Cycling activity ---


def test_extract_last_activity_cycling() -> None:
    payload = [
        {
            "activityName": "Afternoon Ride",
            "activityType": {"typeKey": "cycling"},
            "startTimeGMT": "2026-07-28T14:00:00.000Z",
            "duration": 3600,
            "distance": 25000.0,
            "calories": 500,
            "averageHR": 140,
            "maxHR": 165,
            "averageSpeed": 6.94,
            "elevationGain": 120.0,
        }
    ]
    activity = _extract_last_activity(payload)
    assert activity is not None
    assert activity.type_key == "cycling"
    assert activity.distance_meters == 25000.0
    assert activity.average_speed_meters_per_second == 6.94


# --- Public serialization: no sensitive/raw Garmin keys ---


def test_activity_serialization_excludes_sensitive_fields() -> None:
    payload = [
        {
            "activityName": "Run",
            "activityType": {"typeKey": "running"},
            "startTimeGMT": "2026-07-28T05:00:00.000Z",
            "duration": 1200,
            "activityId": 9999999,
            "ownerId": 12345,
            "ownerDisplayName": "secret_user",
            "startLatitude": 44.4268,
            "startLongitude": 26.1025,
            "endLatitude": 44.4300,
            "endLongitude": 26.1100,
            "description": "Private notes",
            "courseId": 555,
            "locationName": "Home",
        }
    ]
    activity = _extract_last_activity(payload)
    assert activity is not None
    dumped_json = activity.model_dump_json()
    for forbidden in (
        "activityId", "ownerId", "ownerDisplayName",
        "startLatitude", "startLongitude", "endLatitude", "endLongitude",
        "description", "courseId", "locationName",
        "secret_user", "9999999", "44.4268",
    ):
        assert forbidden not in dumped_json


# --- Model validation: overlong trend/timelines, out-of-range values ---


def test_widget_response_rejects_overlong_hrv_trend() -> None:
    trend = [
        HrvTrendPoint(date=date(2026, 7, 22) + timedelta(days=i), overnightAverage=40)
        for i in range(8)
    ]
    with pytest.raises(ValidationError):
        WidgetResponse(schemaVersion=1, hrvTrend=trend)


def test_widget_response_rejects_overlong_timeline() -> None:
    base = datetime(2026, 7, 28, 0, 0, tzinfo=UTC)
    points = [
        TimelinePoint(timestamp=base + timedelta(minutes=i), value=50)
        for i in range(49)
    ]
    with pytest.raises(ValidationError):
        WidgetResponse(schemaVersion=1, bodyBatteryTimeline=points)
    with pytest.raises(ValidationError):
        WidgetResponse(schemaVersion=1, stressTimeline=points)


def test_timeline_point_rejects_out_of_range_values() -> None:
    base = datetime(2026, 7, 28, 0, 0, tzinfo=UTC)
    with pytest.raises(ValidationError):
        TimelinePoint(timestamp=base, value=-1)
    with pytest.raises(ValidationError):
        TimelinePoint(timestamp=base, value=101)


# --- Activity heart-rate timeline ---


def test_activity_hr_timeline_from_details_fixture() -> None:
    activities = _load_fixture("activities_complete.json")
    assert isinstance(activities, list)
    activities[0]["activityId"] = 424242
    client = _complete_client(
        activities=activities,
        activity_details=_load_fixture("activity_details_hr.json"),
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))
    assert metrics.last_activity is not None
    assert metrics.last_activity.heart_rate_timeline is not None
    assert len(metrics.last_activity.heart_rate_timeline) == 10
    assert metrics.last_activity.heart_rate_timeline[0].elapsed_seconds == 0
    assert metrics.last_activity.heart_rate_timeline[0].heart_rate == 112
    assert metrics.last_activity.heart_rate_timeline[-1].heart_rate == 150
    detail_calls = [c for c in client.calls if c[0] == "get_activity_details"]
    assert len(detail_calls) == 1
    assert detail_calls[0][1] == (424242, 2000, 0)
    dumped = normalize_daily_metrics(
        metrics, refreshed_at=datetime(2026, 7, 28, 12, 0, tzinfo=UTC),
        refresh_status=RefreshStatus.SUCCESS, stale=False,
    ).model_dump_json(by_alias=True)
    assert "heartRateTimeline" in dumped
    assert "activityId" not in dumped
    assert "424242" not in dumped
    assert "directLatitude" not in dumped
    assert "44.1" not in dumped


def test_activity_hr_call_budget_initial_backfill_with_details() -> None:
    hrv_by_date = {}
    for i in range(7):
        d = date(2026, 7, 22) + timedelta(days=i)
        hrv_by_date[d.isoformat()] = {
            "hrvSummary": {
                "lastNightAvg": 40 + i,
                "weeklyAvg": 42 + i,
                "status": "BALANCED",
            }
        }
    activities = _load_fixture("activities_complete.json")
    assert isinstance(activities, list)
    activities[0]["activityId"] = 1001
    client = _complete_client(
        hrv_by_date=hrv_by_date,
        activities=activities,
        activity_details=_load_fixture("activity_details_hr.json"),
    )
    GarminMetricsAdapter(client).fetch_daily_metrics(
        date(2026, 7, 28), previous_hrv_trend=None,
    )
    assert len(client.calls) == 16


def test_activity_hr_call_budget_same_day_with_details() -> None:
    existing_trend = [
        HrvTrendPointInternal(
            date=date(2026, 7, 28), overnight_average=40,
            seven_day_average=43, status="BALANCED",
        ),
    ]
    activities = _load_fixture("activities_complete.json")
    assert isinstance(activities, list)
    activities[0]["activityId"] = 1001
    client = _complete_client(
        activities=activities,
        activity_details=_load_fixture("activity_details_hr.json"),
    )
    GarminMetricsAdapter(client).fetch_daily_metrics(
        date(2026, 7, 28), previous_hrv_trend=existing_trend,
    )
    assert len(client.calls) == 10


def test_activity_hr_budget_without_id_remains_15_and_9() -> None:
    hrv_by_date = {
        (date(2026, 7, 22) + timedelta(days=i)).isoformat(): {
            "hrvSummary": {"lastNightAvg": 40 + i, "weeklyAvg": 42, "status": "BALANCED"}
        }
        for i in range(7)
    }
    client = _complete_client(hrv_by_date=hrv_by_date)
    GarminMetricsAdapter(client).fetch_daily_metrics(
        date(2026, 7, 28), previous_hrv_trend=None,
    )
    assert len(client.calls) == 15

    existing = [
        HrvTrendPointInternal(
            date=date(2026, 7, 28), overnight_average=40,
            seven_day_average=43, status="BALANCED",
        ),
    ]
    client2 = _complete_client()
    GarminMetricsAdapter(client2).fetch_daily_metrics(
        date(2026, 7, 28), previous_hrv_trend=existing,
    )
    assert len(client2.calls) == 9


def test_activity_hr_details_unavailable_keeps_summary() -> None:
    activities = _load_fixture("activities_complete.json")
    assert isinstance(activities, list)
    activities[0]["activityId"] = 55
    client = _complete_client(
        activities=activities,
        errors={"get_activity_details": GarminConnectConnectionError("404")},
    )
    metrics = GarminMetricsAdapter(client).fetch_daily_metrics(date(2026, 7, 28))
    assert metrics.last_activity is not None
    assert metrics.last_activity.name == "Morning Run"
    assert metrics.last_activity.heart_rate_timeline is None


def test_activity_hr_malformed_details_returns_none() -> None:
    assert _extract_activity_hr_timeline(None) is None
    assert _extract_activity_hr_timeline({"metricDescriptors": []}) is None
    assert _extract_activity_hr_timeline(
        {
            "metricDescriptors": [{"key": "directHeartRate", "metricsIndex": 0}],
            "activityDetailMetrics": [{"metrics": [10]}],
        }
    ) is None


def test_activity_hr_downsample_preserves_first_last() -> None:
    points = [
        ActivityHeartRatePointInternal(elapsed_seconds=i * 10, heart_rate=100 + (i % 20))
        for i in range(100)
    ]
    result = _sort_dedup_downsample_hr(points, max_points=48)
    assert len(result) == 48
    assert result[0].elapsed_seconds == 0
    assert result[-1].elapsed_seconds == 990


def test_activity_hr_public_model_bounds() -> None:
    with pytest.raises(ValidationError):
        ActivityHeartRatePoint(elapsedSeconds=-1, heartRate=120)
    with pytest.raises(ValidationError):
        ActivityHeartRatePoint(elapsedSeconds=0, heartRate=10)
    with pytest.raises(ValidationError):
        ActivityHeartRatePoint(elapsedSeconds=0, heartRate=260)
    overlong = [
        ActivityHeartRatePoint(elapsedSeconds=i, heartRate=120) for i in range(49)
    ]
    with pytest.raises(ValidationError):
        LastActivity(heartRateTimeline=overlong)


def test_extract_activity_id_variants() -> None:
    assert _extract_activity_id([{"activityId": 12}]) == 12
    assert _extract_activity_id([{"activityId": "34"}]) == 34
    assert _extract_activity_id([{"activityId": 0}]) is None
    assert _extract_activity_id([{}]) is None


def test_activity_hr_timeline_snapshot_round_trip(tmp_path: Path) -> None:
    from app.models.domain import LastActivityInternal
    from app.persistence.models import WidgetSnapshot
    from app.persistence.snapshot import FilesystemWidgetSnapshotRepository

    metrics = DailyMetrics(
        metric_date=date(2026, 7, 28),
        last_activity=LastActivityInternal(
            name="Morning Run",
            type_key="running",
            average_heart_rate=148,
            max_heart_rate=172,
            heart_rate_timeline=[
                ActivityHeartRatePointInternal(elapsed_seconds=0, heart_rate=112),
                ActivityHeartRatePointInternal(elapsed_seconds=30, heart_rate=138),
            ],
        ),
    )
    payload = normalize_daily_metrics(
        metrics,
        refreshed_at=datetime(2026, 7, 28, 12, 0, tzinfo=UTC),
        refresh_status=RefreshStatus.SUCCESS,
        stale=False,
    )
    repo = FilesystemWidgetSnapshotRepository(tmp_path)
    repo.save(
        WidgetSnapshot(
            persistenceFormatVersion=1,
            lastSuccessfulRefreshAt=payload.refreshed_at,
            payload=payload,
        )
    )
    loaded = repo.load()
    timeline = loaded.payload.last_activity.heart_rate_timeline
    assert timeline is not None
    assert len(timeline) == 2
    assert timeline[0].elapsed_seconds == 0
    assert timeline[0].heart_rate == 112
    dumped = loaded.payload.model_dump_json(by_alias=True)
    assert "heartRateTimeline" in dumped
    assert "activityId" not in dumped

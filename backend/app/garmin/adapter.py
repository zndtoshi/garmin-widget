from __future__ import annotations

import math
from datetime import UTC, date, datetime
from typing import Any, Literal, Protocol

from garminconnect import (
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)

from app.garmin.errors import (
    GarminAuthenticationFailedError,
    GarminNetworkError,
    GarminRateLimitError,
    GarminUpstreamError,
)
from app.models.domain import DailyMetrics

EndpointStatus = Literal["ok", "unavailable"]


class GarminMetricsClient(Protocol):
    def get_sleep_data(self, cdate: str) -> Any: ...

    def get_hrv_data(self, cdate: str) -> Any: ...

    def get_body_battery(self, startdate: str, enddate: str | None = None) -> Any: ...

    def get_rhr_day(self, cdate: str) -> Any: ...

    def get_stress_data(self, cdate: str) -> Any: ...

    def get_stats(self, cdate: str) -> Any: ...

    def get_training_readiness(self, cdate: str) -> Any: ...

    def get_device_last_used(self) -> Any: ...


class GarminMetricsAdapter:
    """Fetch Garmin metrics and convert them into internal domain models.

    Raw Garmin dictionary traversal stays inside this adapter.
    """

    def __init__(self, client: GarminMetricsClient) -> None:
        self._client = client

    def fetch_daily_metrics(self, metric_date: date) -> DailyMetrics:
        cdate = metric_date.isoformat()
        outcomes: list[EndpointStatus] = []

        sleep, status = self._call("get_sleep_data", cdate)
        outcomes.append(status)
        hrv, status = self._call("get_hrv_data", cdate)
        outcomes.append(status)
        body_battery, status = self._call("get_body_battery", cdate)
        outcomes.append(status)
        rhr, status = self._call("get_rhr_day", cdate)
        outcomes.append(status)
        stress, status = self._call("get_stress_data", cdate)
        outcomes.append(status)
        stats, status = self._call("get_stats", cdate)
        outcomes.append(status)
        readiness, status = self._call("get_training_readiness", cdate)
        outcomes.append(status)
        device, status = self._call("get_device_last_used")
        outcomes.append(status)

        if outcomes and all(item == "unavailable" for item in outcomes):
            raise GarminUpstreamError("Garmin metric endpoints were unavailable.")

        sleep_score, sleep_duration = _extract_sleep(sleep)
        overnight_hrv, hrv_status = _extract_hrv(hrv)
        body_battery_value = _extract_body_battery(body_battery)
        resting_hr = _extract_resting_heart_rate(rhr, stats)
        stress_value = _extract_stress(stress, stats)
        training_readiness = _extract_training_readiness(readiness)
        garmin_sync_at = _extract_garmin_sync_at(device)

        return DailyMetrics(
            metric_date=metric_date,
            sleep_score=sleep_score,
            sleep_duration_seconds=sleep_duration,
            overnight_hrv=overnight_hrv,
            hrv_status=hrv_status,
            body_battery=body_battery_value,
            resting_heart_rate=resting_hr,
            stress=stress_value,
            training_readiness=training_readiness,
            garmin_sync_at=garmin_sync_at,
        )

    def _call(self, method_name: str, *args: Any) -> tuple[Any | None, EndpointStatus]:
        method = getattr(self._client, method_name)
        try:
            return method(*args), "ok"
        except GarminConnectAuthenticationError as exc:
            raise GarminAuthenticationFailedError(
                "Garmin authentication failed."
            ) from exc
        except GarminConnectTooManyRequestsError as exc:
            raise GarminRateLimitError(
                "Garmin temporarily rate-limited metric fetching."
            ) from exc
        except GarminConnectConnectionError as exc:
            if _looks_like_network_error(exc):
                raise GarminNetworkError(
                    "Garmin could not be reached due to a network or timeout error."
                ) from exc
            # Recognized unavailable endpoint/metric for this date.
            return None, "unavailable"
        except Exception as exc:
            raise GarminUpstreamError(
                "Unexpected Garmin upstream failure while fetching metrics."
            ) from exc


def _looks_like_network_error(exc: GarminConnectConnectionError) -> bool:
    text = str(exc).lower()
    indicators = (
        "timeout",
        "timed out",
        "connection",
        "dns",
        "temporar",
        "unreachable",
        "reset by peer",
    )
    return any(indicator in text for indicator in indicators)


def _extract_sleep(payload: Any) -> tuple[int | None, int | None]:
    if not isinstance(payload, dict):
        return None, None
    daily = payload.get("dailySleepDTO")
    if not isinstance(daily, dict):
        return None, None
    score = _as_int(
        ((daily.get("sleepScores") or {}).get("overall") or {}).get("value")
        if isinstance(daily.get("sleepScores"), dict)
        else None
    )
    duration = _as_int(daily.get("sleepTimeSeconds"))
    return score, duration


def _extract_hrv(payload: Any) -> tuple[int | None, str | None]:
    if not isinstance(payload, dict):
        return None, None
    hrv_summary = payload.get("hrvSummary")
    source = hrv_summary if isinstance(hrv_summary, dict) else payload
    overnight = _as_int(source.get("lastNightAvg"))
    status = source.get("status")
    status_text = (
        status.strip().upper() if isinstance(status, str) and status.strip() else None
    )
    return overnight, status_text


def _extract_body_battery(payload: Any) -> int | None:
    """Return the latest/current Body Battery level, never charged totals."""
    if isinstance(payload, list) and payload:
        day = payload[0] if isinstance(payload[0], dict) else None
    elif isinstance(payload, dict):
        day = payload
    else:
        day = None
    if day is None:
        return None

    values = day.get("bodyBatteryValuesArray")
    if isinstance(values, list):
        for entry in reversed(values):
            if isinstance(entry, (list, tuple)) and len(entry) >= 2:
                parsed = _as_int(entry[1])
                if parsed is not None:
                    return parsed

    for key in ("bodyBatteryMostRecentValue", "bodyBatteryCurrentValue"):
        parsed = _as_int(day.get(key))
        if parsed is not None:
            return parsed
    return None


def _extract_resting_heart_rate(rhr_payload: Any, stats_payload: Any) -> int | None:
    if isinstance(rhr_payload, dict):
        metrics_map = ((rhr_payload.get("allMetrics") or {}).get("metricsMap") or {})
        if isinstance(metrics_map, dict):
            entries = metrics_map.get("WELLNESS_RESTING_HEART_RATE")
            if isinstance(entries, list) and entries:
                first = entries[0]
                if isinstance(first, dict):
                    parsed = _as_int(first.get("value"))
                    if parsed is not None:
                        return parsed
        parsed = _as_int(rhr_payload.get("restingHeartRate"))
        if parsed is not None:
            return parsed

    if isinstance(stats_payload, dict):
        return _as_int(stats_payload.get("restingHeartRate"))
    return None


def _extract_stress(stress_payload: Any, stats_payload: Any) -> int | None:
    if isinstance(stress_payload, dict):
        for key in ("overallStressLevel", "avgStressLevel", "averageStressLevel"):
            parsed = _as_int(stress_payload.get(key))
            if parsed is not None:
                return parsed
    if isinstance(stats_payload, dict):
        return _as_int(stats_payload.get("averageStressLevel"))
    return None


def _extract_training_readiness(payload: Any) -> int | None:
    if isinstance(payload, list):
        snapshots = [item for item in payload if isinstance(item, dict)]
        if not snapshots:
            return None
        snapshots.sort(key=_training_readiness_sort_key, reverse=True)
        return _as_int(snapshots[0].get("score"))
    if isinstance(payload, dict):
        return _as_int(payload.get("score"))
    return None


def _training_readiness_sort_key(item: dict[str, Any]) -> str:
    for key in ("timestamp", "timestampLocal"):
        value = item.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _extract_garmin_sync_at(payload: Any) -> datetime | None:
    if not isinstance(payload, dict):
        return None
    for key in (
        "lastUsedDeviceUploadTime",
        "lastUsedDeviceDownloadDate",
        "lastUsedDeviceUploadDate",
        "lastSyncTimestampGMT",
        "syncDateGMT",
    ):
        parsed = _as_datetime_utc(payload.get(key))
        if parsed is not None:
            return parsed
    return None


def _as_int(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        if math.isnan(value) or math.isinf(value):
            return None
        try:
            return int(value)
        except (OverflowError, ValueError):
            return None
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        try:
            parsed = float(text)
        except ValueError:
            return None
        if math.isnan(parsed) or math.isinf(parsed):
            return None
        try:
            return int(parsed)
        except (OverflowError, ValueError):
            return None
    return None


def _as_datetime_utc(value: Any) -> datetime | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)
    if isinstance(value, (int, float)):
        # Garmin sometimes returns epoch milliseconds.
        try:
            ts = float(value)
        except (OverflowError, ValueError):
            return None
        if math.isnan(ts) or math.isinf(ts):
            return None
        if ts > 1_000_000_000_000:
            ts = ts / 1000.0
        try:
            return datetime.fromtimestamp(ts, tz=UTC)
        except (OverflowError, OSError, ValueError):
            return None
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        if text.endswith("Z"):
            text = text[:-1] + "+00:00"
        try:
            parsed = datetime.fromisoformat(text)
        except ValueError:
            return None
        if parsed.tzinfo is None:
            return parsed.replace(tzinfo=UTC)
        return parsed.astimezone(UTC)
    return None

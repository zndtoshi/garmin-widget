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
from app.models.domain import (
    DailyMetrics,
    HrvTrendPointInternal,
    LastActivityInternal,
    SleepStagesInternal,
    TimelinePointInternal,
)

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

    def get_activities(self, start: int, limit: int) -> Any: ...


class GarminMetricsAdapter:
    """Fetch Garmin metrics and convert them into internal domain models.

    Raw Garmin dictionary traversal stays inside this adapter.
    """

    def __init__(self, client: GarminMetricsClient) -> None:
        self._client = client

    def fetch_daily_metrics(
        self,
        metric_date: date,
        *,
        previous_hrv_trend: list[HrvTrendPointInternal] | None = None,
    ) -> DailyMetrics:
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
        activities, status = self._call("get_activities", 0, 1)
        outcomes.append(status)

        if outcomes and all(item == "unavailable" for item in outcomes):
            raise GarminUpstreamError("Garmin metric endpoints were unavailable.")

        sleep_score, sleep_duration = _extract_sleep(sleep)
        sleep_stages = _extract_sleep_stages(sleep)
        overnight_hrv, hrv_status = _extract_hrv(hrv)
        hrv_trend = _build_hrv_trend(
            metric_date, hrv, previous_hrv_trend, self,
        )
        body_battery_value = _extract_body_battery(body_battery)
        body_battery_timeline = _extract_body_battery_timeline(body_battery)
        resting_hr = _extract_resting_heart_rate(rhr, stats)
        stress_value = _extract_stress(stress, stats)
        stress_timeline = _extract_stress_timeline(stress)
        training_readiness = _extract_training_readiness(readiness)
        last_activity = _extract_last_activity(activities)
        garmin_sync_at = _extract_garmin_sync_at(device)

        return DailyMetrics(
            metric_date=metric_date,
            sleep_score=sleep_score,
            sleep_duration_seconds=sleep_duration,
            sleep_stages=sleep_stages,
            overnight_hrv=overnight_hrv,
            hrv_status=hrv_status,
            hrv_trend=hrv_trend,
            body_battery=body_battery_value,
            body_battery_timeline=body_battery_timeline,
            resting_heart_rate=resting_hr,
            stress=stress_value,
            stress_timeline=stress_timeline,
            training_readiness=training_readiness,
            last_activity=last_activity,
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


def _extract_sleep_stages(payload: Any) -> SleepStagesInternal | None:
    if not isinstance(payload, dict):
        return None
    daily = payload.get("dailySleepDTO")
    if not isinstance(daily, dict):
        return None
    deep = _as_non_negative_int(daily.get("deepSleepSeconds"))
    light = _as_non_negative_int(daily.get("lightSleepSeconds"))
    rem = _as_non_negative_int(daily.get("remSleepSeconds"))
    awake = _as_non_negative_int(daily.get("awakeSleepSeconds"))
    if deep is None and light is None and rem is None and awake is None:
        return None
    return SleepStagesInternal(
        deep_seconds=deep,
        light_seconds=light,
        rem_seconds=rem,
        awake_seconds=awake,
    )


def _extract_hrv_point(
    hrv_date: date, payload: Any,
) -> HrvTrendPointInternal | None:
    if not isinstance(payload, dict):
        return None
    hrv_summary = payload.get("hrvSummary")
    source = hrv_summary if isinstance(hrv_summary, dict) else payload
    overnight = _as_int(source.get("lastNightAvg"))
    weekly = _as_int(source.get("weeklyAvg"))
    status_raw = source.get("status")
    status = (
        status_raw.strip().upper()
        if isinstance(status_raw, str) and status_raw.strip()
        else None
    )
    if overnight is None and weekly is None and status is None:
        return None
    return HrvTrendPointInternal(
        date=hrv_date,
        overnight_average=overnight,
        seven_day_average=weekly,
        status=status,
    )


def _build_hrv_trend(
    metric_date: date,
    today_hrv_payload: Any,
    previous_trend: list[HrvTrendPointInternal] | None,
    adapter: GarminMetricsAdapter,
) -> list[HrvTrendPointInternal] | None:
    from datetime import timedelta

    today_point = _extract_hrv_point(metric_date, today_hrv_payload)

    if previous_trend is not None and len(previous_trend) > 0:
        last_date = max(p.date for p in previous_trend)
        if last_date == metric_date:
            trend = [p for p in previous_trend if p.date != metric_date]
        else:
            trend = list(previous_trend)
        if today_point is not None:
            trend.append(today_point)
        cutoff = metric_date - timedelta(days=6)
        trend = [p for p in trend if p.date >= cutoff]
        trend.sort(key=lambda p: p.date)
        return trend if trend else None

    trend: list[HrvTrendPointInternal] = []
    for days_back in range(6, 0, -1):
        past_date = metric_date - timedelta(days=days_back)
        past_payload, status = adapter._call("get_hrv_data", past_date.isoformat())
        if status == "ok":
            point = _extract_hrv_point(past_date, past_payload)
            if point is not None:
                trend.append(point)
    if today_point is not None:
        trend.append(today_point)
    return trend if trend else None


def _extract_body_battery_timeline(
    payload: Any,
) -> list[TimelinePointInternal] | None:
    if isinstance(payload, list) and payload:
        day = payload[0] if isinstance(payload[0], dict) else None
    elif isinstance(payload, dict):
        day = payload
    else:
        return None
    if day is None:
        return None

    values = day.get("bodyBatteryValuesArray")
    if not isinstance(values, list):
        return None

    points: list[TimelinePointInternal] = []
    for entry in values:
        if not isinstance(entry, (list, tuple)) or len(entry) < 2:
            continue
        ts = _as_datetime_utc(entry[0])
        val = _as_int(entry[1])
        if ts is not None and val is not None and 0 <= val <= 100:
            points.append(TimelinePointInternal(timestamp=ts, value=val))

    if not points:
        return None
    return _sort_dedup_and_downsample(points, max_points=48)


def _extract_stress_timeline(payload: Any) -> list[TimelinePointInternal] | None:
    if not isinstance(payload, dict):
        return None

    values = payload.get("stressValuesArray")
    if not isinstance(values, list):
        return None

    points: list[TimelinePointInternal] = []
    for entry in values:
        if not isinstance(entry, (list, tuple)) or len(entry) < 2:
            continue
        ts = _as_datetime_utc(entry[0])
        val = _as_int(entry[1])
        if ts is not None and val is not None and 0 <= val <= 100:
            points.append(TimelinePointInternal(timestamp=ts, value=val))

    if not points:
        return None
    return _sort_dedup_and_downsample(points, max_points=48)


def _sort_dedup_and_downsample(
    points: list[TimelinePointInternal], *, max_points: int,
) -> list[TimelinePointInternal]:
    points.sort(key=lambda p: p.timestamp)
    deduped: dict[datetime, TimelinePointInternal] = {}
    for p in points:
        deduped[p.timestamp] = p
    sorted_points = sorted(deduped.values(), key=lambda p: p.timestamp)
    if not sorted_points:
        return []
    return _downsample(sorted_points, max_points=max_points)


def _downsample(
    points: list[TimelinePointInternal], *, max_points: int,
) -> list[TimelinePointInternal]:
    if len(points) <= max_points:
        return points
    first = points[0]
    last = points[-1]
    middle = points[1:-1]
    picks = max_points - 2
    if picks <= 0:
        return [first, last]
    step = len(middle) / picks
    result: list[TimelinePointInternal] = [first]
    for i in range(picks):
        idx = int(i * step)
        result.append(middle[idx])
    result.append(last)
    return result


def _extract_last_activity(payload: Any) -> LastActivityInternal | None:
    if isinstance(payload, list):
        if not payload:
            return None
        activity = payload[0] if isinstance(payload[0], dict) else None
    elif isinstance(payload, dict):
        activity = payload
    else:
        return None
    if activity is None:
        return None

    started_at = _as_gmt_datetime(activity.get("startTimeGMT"))

    result = LastActivityInternal(
        name=_extract_activity_name(activity),
        type_key=_extract_activity_type_key(activity),
        started_at=started_at,
        duration_seconds=_as_int(activity.get("duration")),
        moving_duration_seconds=_as_int(activity.get("movingDuration")),
        distance_meters=_as_float(activity.get("distance")),
        calories=_as_int(activity.get("calories")),
        average_heart_rate=_as_int(activity.get("averageHR")),
        max_heart_rate=_as_int(activity.get("maxHR")),
        elevation_gain_meters=_as_float(activity.get("elevationGain")),
        average_speed_meters_per_second=_as_float(activity.get("averageSpeed")),
        aerobic_training_effect=_as_float(activity.get("aerobicTrainingEffect")),
        anaerobic_training_effect=_as_float(activity.get("anaerobicTrainingEffect")),
        training_load=_as_float(activity.get("activityTrainingLoad")),
    )
    if _is_empty_activity(result):
        return None
    return result


def _as_gmt_datetime(value: Any) -> datetime | None:
    """Parse Garmin's GMT field as UTC, including naive ISO strings."""
    if value is None:
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
    if isinstance(value, (int, float)):
        return _as_datetime_utc(value)
    return None


def _is_empty_activity(activity: LastActivityInternal) -> bool:
    return all(
        v is None
        for v in (
            activity.name,
            activity.type_key,
            activity.started_at,
            activity.duration_seconds,
            activity.moving_duration_seconds,
            activity.distance_meters,
            activity.calories,
            activity.average_heart_rate,
            activity.max_heart_rate,
            activity.elevation_gain_meters,
            activity.average_speed_meters_per_second,
            activity.aerobic_training_effect,
            activity.anaerobic_training_effect,
            activity.training_load,
        )
    )


def _extract_activity_name(activity: dict[str, Any]) -> str | None:
    name = activity.get("activityName")
    return name if isinstance(name, str) else None


def _extract_activity_type_key(activity: dict[str, Any]) -> str | None:
    at = activity.get("activityType")
    if isinstance(at, dict):
        key = at.get("typeKey")
        if isinstance(key, str) and key.strip():
            return key.strip()
    if isinstance(at, str) and at.strip():
        return at.strip()
    return None


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


def _as_non_negative_int(value: Any) -> int | None:
    result = _as_int(value)
    if result is not None and result < 0:
        return None
    return result


def _as_float(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
            return None
        return float(value)
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
        return parsed
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

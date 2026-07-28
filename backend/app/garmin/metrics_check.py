from __future__ import annotations

import argparse
import json
from datetime import date

from pydantic import ValidationError

from app.core.config import get_settings
from app.garmin.adapter import GarminMetricsAdapter
from app.garmin.auth_check import build_session_manager
from app.garmin.dates import calendar_date_for_timezone, parse_metric_date
from app.garmin.errors import GarminSessionError
from app.garmin.normalize import normalize_daily_metrics


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Fetch and normalize Garmin metrics for a calendar date."
    )
    parser.add_argument(
        "--date",
        dest="metric_date",
        help="Metric date as YYYY-MM-DD. Defaults to today's date in GARMIN_WIDGET_TIMEZONE.",
    )
    return parser


def resolve_metric_date(raw_date: str | None, timezone_name: str) -> date:
    if raw_date:
        return parse_metric_date(raw_date)
    return calendar_date_for_timezone(timezone_name)


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        settings = get_settings()
        settings.validate_runtime()
        metric_date = resolve_metric_date(args.metric_date, settings.timezone)
        garmin = build_session_manager().initialize_session()
        metrics = GarminMetricsAdapter(garmin).fetch_daily_metrics(metric_date)
        payload = normalize_daily_metrics(metrics)
        print(
            json.dumps(
                payload.model_dump(by_alias=True, mode="json"),
                separators=(",", ":"),
                sort_keys=False,
            )
        )
        return 0
    except GarminSessionError as exc:
        print(f"Garmin metrics check failed: {exc}")
        return 1
    except (ValueError, ValidationError):
        print("Garmin metrics check failed: invalid configuration or date.")
        return 1
    except Exception:
        print("Garmin metrics check failed: unexpected error.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

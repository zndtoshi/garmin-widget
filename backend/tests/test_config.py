import os
import re

import pytest
from fastapi.testclient import TestClient

from app.core.config import (
    WIDGET_BEARER_TOKEN_MIN_LENGTH,
    AppEnvironment,
    Settings,
)
from app.main import create_app


def test_production_requires_widget_token() -> None:
    os.environ["GARMIN_WIDGET_APP_ENV"] = AppEnvironment.PRODUCTION
    settings = Settings()

    with pytest.raises(ValueError, match="GARMIN_WIDGET_WIDGET_BEARER_TOKEN"):
        settings.validate_runtime()


def test_empty_production_widget_token_is_rejected() -> None:
    os.environ["GARMIN_WIDGET_APP_ENV"] = AppEnvironment.PRODUCTION
    os.environ["GARMIN_WIDGET_WIDGET_BEARER_TOKEN"] = ""
    settings = Settings()

    with pytest.raises(ValueError, match="must not be empty"):
        settings.validate_runtime()


def test_whitespace_production_widget_token_is_rejected() -> None:
    os.environ["GARMIN_WIDGET_APP_ENV"] = AppEnvironment.PRODUCTION
    os.environ["GARMIN_WIDGET_WIDGET_BEARER_TOKEN"] = "   "
    settings = Settings()

    with pytest.raises(ValueError, match="must not be empty"):
        settings.validate_runtime()


def test_production_rejects_known_placeholder_widget_token() -> None:
    os.environ["GARMIN_WIDGET_APP_ENV"] = AppEnvironment.PRODUCTION
    os.environ["GARMIN_WIDGET_WIDGET_BEARER_TOKEN"] = (
        "replace-me-before-enabling-widget-endpoints"
    )
    settings = Settings()

    with pytest.raises(ValueError, match="known placeholder") as exc_info:
        settings.validate_runtime()

    assert "replace-me-before-enabling-widget-endpoints" not in str(exc_info.value)


def test_production_rejects_short_widget_token() -> None:
    short_token = "a" * (WIDGET_BEARER_TOKEN_MIN_LENGTH - 1)
    os.environ["GARMIN_WIDGET_APP_ENV"] = AppEnvironment.PRODUCTION
    os.environ["GARMIN_WIDGET_WIDGET_BEARER_TOKEN"] = short_token
    settings = Settings()

    with pytest.raises(ValueError, match="at least") as exc_info:
        settings.validate_runtime()

    assert short_token not in str(exc_info.value)


def test_production_accepts_generated_style_widget_token() -> None:
    token = "x" * WIDGET_BEARER_TOKEN_MIN_LENGTH
    os.environ["GARMIN_WIDGET_APP_ENV"] = AppEnvironment.PRODUCTION
    os.environ["GARMIN_WIDGET_WIDGET_BEARER_TOKEN"] = token
    settings = Settings()

    settings.validate_runtime()
    assert settings.widget_bearer_token is not None


def test_partial_garmin_credentials_are_rejected() -> None:
    os.environ["GARMIN_WIDGET_GARMIN_USERNAME"] = "user@example.com"
    settings = Settings()

    with pytest.raises(ValueError, match="must be set together"):
        settings.validate_runtime()


def test_empty_garmin_username_is_rejected() -> None:
    os.environ["GARMIN_WIDGET_GARMIN_USERNAME"] = ""
    os.environ["GARMIN_WIDGET_GARMIN_PASSWORD"] = "secret"
    settings = Settings()

    with pytest.raises(ValueError, match="GARMIN_WIDGET_GARMIN_USERNAME"):
        settings.validate_runtime()


def test_whitespace_garmin_username_is_rejected() -> None:
    os.environ["GARMIN_WIDGET_GARMIN_USERNAME"] = "   "
    os.environ["GARMIN_WIDGET_GARMIN_PASSWORD"] = "secret"
    settings = Settings()

    with pytest.raises(ValueError, match="GARMIN_WIDGET_GARMIN_USERNAME"):
        settings.validate_runtime()


def test_empty_garmin_password_is_rejected() -> None:
    os.environ["GARMIN_WIDGET_GARMIN_USERNAME"] = "user@example.com"
    os.environ["GARMIN_WIDGET_GARMIN_PASSWORD"] = ""
    settings = Settings()

    with pytest.raises(ValueError, match="GARMIN_WIDGET_GARMIN_PASSWORD"):
        settings.validate_runtime()


def test_whitespace_garmin_password_is_rejected() -> None:
    os.environ["GARMIN_WIDGET_GARMIN_USERNAME"] = "user@example.com"
    os.environ["GARMIN_WIDGET_GARMIN_PASSWORD"] = "   "
    settings = Settings()

    with pytest.raises(ValueError, match="GARMIN_WIDGET_GARMIN_PASSWORD"):
        settings.validate_runtime()


def test_env_example_is_valid_local_configuration_without_active_token(
    env_example_path,
) -> None:
    raw = env_example_path.read_text(encoding="utf-8")
    assert not re.search(
        r"(?m)^GARMIN_WIDGET_WIDGET_BEARER_TOKEN=",
        raw,
    )
    assert "secrets.token_urlsafe(32)" in raw

    settings = Settings(_env_file=env_example_path)
    settings.validate_runtime()
    assert settings.app_env == AppEnvironment.LOCAL
    assert settings.widget_bearer_token is None
    assert settings.garmin_username is None
    assert settings.garmin_password is None


def test_local_defaults_are_valid() -> None:
    settings = Settings()
    settings.validate_runtime()
    assert settings.app_env == AppEnvironment.LOCAL
    assert settings.refresh_cooldown_seconds == 60
    assert settings.timezone == "Europe/Bucharest"
    assert settings.widget_bearer_token is None


def test_invalid_timezone_is_rejected() -> None:
    settings = Settings(timezone="Not/A_Real_Zone")

    with pytest.raises(ValueError, match="GARMIN_WIDGET_TIMEZONE"):
        settings.validate_runtime()


def test_local_app_starts_without_token_health_ok_widget_503(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    os.environ.pop("GARMIN_WIDGET_WIDGET_BEARER_TOKEN", None)
    os.environ["GARMIN_WIDGET_DATA_DIR"] = str(tmp_path)
    client = TestClient(create_app())

    health = client.get("/health")
    latest = client.get(
        "/api/v1/widget/latest",
        headers={"Authorization": "Bearer unused-client-token"},
    )

    assert health.status_code == 200
    assert latest.status_code == 503
    assert latest.json() == {
        "detail": "Widget authentication is not configured."
    }
    assert "unused-client-token" not in latest.text

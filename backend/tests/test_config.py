import os

import pytest

from app.core.config import AppEnvironment, Settings


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


def test_env_example_is_valid_local_configuration(env_example_path) -> None:
    settings = Settings(_env_file=env_example_path)
    settings.validate_runtime()
    assert settings.app_env == AppEnvironment.LOCAL
    assert settings.garmin_username is None
    assert settings.garmin_password is None


def test_local_defaults_are_valid() -> None:
    settings = Settings()
    settings.validate_runtime()
    assert settings.app_env == AppEnvironment.LOCAL
    assert settings.refresh_cooldown_seconds == 60

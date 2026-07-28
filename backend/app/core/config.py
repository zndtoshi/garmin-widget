from __future__ import annotations

from enum import StrEnum
from functools import lru_cache
from pathlib import Path

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class AppEnvironment(StrEnum):
    LOCAL = "local"
    TEST = "test"
    PRODUCTION = "production"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="GARMIN_WIDGET_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_env: AppEnvironment = AppEnvironment.LOCAL
    service_name: str = "garmin-widget-backend"
    app_version: str = "0.1.0"
    log_level: str = "INFO"
    widget_bearer_token: SecretStr | None = None
    data_dir: Path = Path("./data")
    refresh_cooldown_seconds: int = 60
    garmin_username: str | None = None
    garmin_password: SecretStr | None = None

    def validate_runtime(self) -> None:
        if self.refresh_cooldown_seconds < 0:
            raise ValueError("GARMIN_WIDGET_REFRESH_COOLDOWN_SECONDS must be >= 0")

        if self.app_env == AppEnvironment.PRODUCTION:
            token = _strip_secret(self.widget_bearer_token)
            if token is None:
                raise ValueError(
                    "GARMIN_WIDGET_WIDGET_BEARER_TOKEN is required when "
                    "GARMIN_WIDGET_APP_ENV=production"
                )
            if not token:
                raise ValueError(
                    "GARMIN_WIDGET_WIDGET_BEARER_TOKEN must not be empty when "
                    "GARMIN_WIDGET_APP_ENV=production"
                )

        username = _strip_nullable(self.garmin_username)
        password = _strip_secret(self.garmin_password)

        if (username is None) ^ (password is None):
            raise ValueError(
                "GARMIN_WIDGET_GARMIN_USERNAME and "
                "GARMIN_WIDGET_GARMIN_PASSWORD must be set together"
            )

        if username is not None and not username:
            raise ValueError("GARMIN_WIDGET_GARMIN_USERNAME must not be empty")

        if password is not None and not password:
            raise ValueError("GARMIN_WIDGET_GARMIN_PASSWORD must not be empty")


@lru_cache
def get_settings() -> Settings:
    return Settings()


def clear_settings_cache() -> None:
    get_settings.cache_clear()


def _strip_nullable(value: str | None) -> str | None:
    if value is None:
        return None
    return value.strip()


def _strip_secret(value: SecretStr | None) -> str | None:
    if value is None:
        return None
    return value.get_secret_value().strip()

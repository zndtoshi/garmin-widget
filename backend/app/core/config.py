from __future__ import annotations

from enum import StrEnum
from functools import lru_cache
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

# Production bearer tokens must be at least this long. Prefer
# secrets.token_urlsafe(32) (32 random bytes) when generating values.
WIDGET_BEARER_TOKEN_MIN_LENGTH = 32

# Never accept these known example/placeholder values in production.
_KNOWN_PLACEHOLDER_WIDGET_TOKENS = frozenset(
    {
        "replace-me-before-enabling-widget-endpoints",
        "your-generated-token-here",
        "your-widget-bearer-token",
        "changeme",
        "change-me",
        "placeholder",
        "secret",
        "password",
    }
)


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
    # Used only to choose the Garmin calendar date; timestamps remain UTC.
    timezone: str = "Europe/Bucharest"
    garmin_username: str | None = None
    garmin_password: SecretStr | None = None

    def validate_runtime(self) -> None:
        if self.refresh_cooldown_seconds < 0:
            raise ValueError("GARMIN_WIDGET_REFRESH_COOLDOWN_SECONDS must be >= 0")

        try:
            ZoneInfo(self.timezone)
        except ZoneInfoNotFoundError as exc:
            raise ValueError(
                "GARMIN_WIDGET_TIMEZONE must be a valid IANA timezone name"
            ) from exc

        if self.app_env == AppEnvironment.PRODUCTION:
            _validate_production_widget_token(self.widget_bearer_token)

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
    # Avoid circular import at module load; clear lazily when settings reset.
    from app.services.factory import clear_service_caches

    clear_service_caches()


def _validate_production_widget_token(value: SecretStr | None) -> None:
    token = _strip_secret(value)
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
    if token.lower() in _KNOWN_PLACEHOLDER_WIDGET_TOKENS:
        raise ValueError(
            "GARMIN_WIDGET_WIDGET_BEARER_TOKEN must not use a known placeholder "
            "value when GARMIN_WIDGET_APP_ENV=production"
        )
    if len(token) < WIDGET_BEARER_TOKEN_MIN_LENGTH:
        raise ValueError(
            "GARMIN_WIDGET_WIDGET_BEARER_TOKEN must be at least "
            f"{WIDGET_BEARER_TOKEN_MIN_LENGTH} characters when "
            "GARMIN_WIDGET_APP_ENV=production"
        )


def _strip_nullable(value: str | None) -> str | None:
    if value is None:
        return None
    return value.strip()


def _strip_secret(value: SecretStr | None) -> str | None:
    if value is None:
        return None
    return value.get_secret_value().strip()

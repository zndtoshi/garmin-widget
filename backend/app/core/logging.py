from __future__ import annotations

import logging
import time
from collections.abc import Iterable

from app.core.config import Settings

REDACTED = "***REDACTED***"


class UtcFormatter(logging.Formatter):
    converter = time.gmtime


class SecretRedactionFilter(logging.Filter):
    def __init__(self, secrets: Iterable[str]) -> None:
        super().__init__()
        self._garmin_widget_owned = True
        self._secrets = tuple(secret for secret in secrets if secret)

    def filter(self, record: logging.LogRecord) -> bool:
        record.msg = _redact_text(str(record.msg), self._secrets)
        if record.args:
            if isinstance(record.args, dict):
                record.args = {
                    key: _redact_value(value, self._secrets)
                    for key, value in record.args.items()
                }
            else:
                record.args = tuple(
                    _redact_value(value, self._secrets) for value in record.args
                )
        return True


def _redact_text(text: str, secrets: Iterable[str]) -> str:
    redacted = text
    for secret in secrets:
        redacted = redacted.replace(secret, REDACTED)
    return redacted


def _redact_value(value: object, secrets: Iterable[str]) -> object:
    if isinstance(value, str):
        return _redact_text(value, secrets)
    return value


def configure_logging(settings: Settings) -> None:
    level = getattr(logging, settings.log_level.upper(), logging.INFO)
    secrets = [
        settings.widget_bearer_token.get_secret_value()
        if settings.widget_bearer_token is not None
        else "",
        settings.garmin_password.get_secret_value() if settings.garmin_password is not None else "",
        settings.garmin_username or "",
    ]

    root_logger = logging.getLogger()
    root_logger.setLevel(level)

    formatter = UtcFormatter(
        fmt="%(asctime)s %(levelname)s %(name)s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%SZ",
    )

    if not root_logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(formatter)
        root_logger.addHandler(handler)

    for handler in root_logger.handlers:
        handler.setLevel(level)
        handler.setFormatter(formatter)
        _install_secret_filter(handler, secrets)


def _install_secret_filter(
    handler: logging.Handler, secrets: Iterable[str]
) -> None:
    preserved_filters = [
        existing_filter
        for existing_filter in handler.filters
        if not getattr(existing_filter, "_garmin_widget_owned", False)
    ]
    preserved_filters.append(SecretRedactionFilter(secrets))

    for existing_filter in list(handler.filters):
        if getattr(existing_filter, "_garmin_widget_owned", False):
            handler.removeFilter(existing_filter)

    for existing_filter in preserved_filters:
        if existing_filter not in handler.filters:
            handler.addFilter(existing_filter)

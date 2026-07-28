from __future__ import annotations

import logging
from typing import Any

from garminconnect import (
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)

from app.core.config import Settings
from app.garmin.client import GarminClientFactoryProtocol, GarminFactory
from app.garmin.errors import (
    GarminAuthenticationFailedError,
    GarminCorruptSessionError,
    GarminMfaRequiredError,
    GarminNetworkError,
    GarminRateLimitError,
    GarminSessionError,
    GarminUpstreamError,
    MissingGarminCredentialsError,
)
from app.garmin.store import FilesystemSessionStore, SessionStore, TokenPersistenceClient

logger = logging.getLogger(__name__)


class GarminSessionManager:
    def __init__(
        self,
        settings: Settings,
        session_store: SessionStore | None = None,
        client_factory: GarminClientFactoryProtocol | None = None,
    ) -> None:
        self._settings = settings
        self._session_store = session_store or FilesystemSessionStore(settings.data_dir)
        self._client_factory = client_factory or GarminFactory()

    def initialize_session(self) -> Any:
        username = _normalized_username(self._settings.garmin_username)
        password = _normalized_password(self._settings.garmin_password)
        garmin = self._client_factory.create(username, password)

        if self._session_store.exists():
            self._validate_saved_session(garmin.client)
            return self._resume_saved_session(garmin)

        self._require_credentials(username, password)
        return self._login_and_persist(garmin)

    def _validate_saved_session(self, client: TokenPersistenceClient) -> None:
        try:
            self._session_store.validate_readable(client)
        except FileNotFoundError as exc:
            raise GarminCorruptSessionError(
                "Saved Garmin session data is missing or corrupt."
            ) from exc
        except GarminCorruptSessionError:
            logger.warning("Saved Garmin session data could not be parsed.")
            raise

    def _resume_saved_session(self, garmin: Any) -> Any:
        return self._login_with_optional_tokenstore(
            garmin,
            str(self._session_store.path()),
            persist_after_success=True,
        )

    def _login_and_persist(self, garmin: Any) -> Any:
        return self._login_with_optional_tokenstore(
            garmin,
            None,
            persist_after_success=True,
        )

    def _login_with_optional_tokenstore(
        self,
        garmin: Any,
        tokenstore: str | None,
        *,
        persist_after_success: bool,
    ) -> Any:
        try:
            needs_mfa, _ = garmin.login(tokenstore)
            if needs_mfa is not None:
                raise GarminMfaRequiredError(
                    "Garmin requires interactive MFA or another unsupported login step."
                )

            if persist_after_success:
                self._session_store.save(garmin.client)
            return garmin
        except GarminSessionError:
            raise
        except GarminConnectTooManyRequestsError as exc:
            raise GarminRateLimitError(
                "Garmin temporarily rate-limited authentication."
            ) from exc
        except GarminConnectAuthenticationError as exc:
            raise GarminAuthenticationFailedError(
                "Garmin authentication failed."
            ) from exc
        except GarminConnectConnectionError as exc:
            if _looks_like_network_error(exc):
                raise GarminNetworkError(
                    "Garmin could not be reached due to a network or timeout error."
                ) from exc
            raise GarminUpstreamError("Garmin session initialization failed.") from exc
        except Exception as exc:
            raise GarminUpstreamError("Garmin session initialization failed.") from exc

    @staticmethod
    def _require_credentials(username: str | None, password: str | None) -> None:
        if username and password:
            return
        raise MissingGarminCredentialsError(
            "Garmin username and password are required when no reusable session exists."
        )


def _normalized_username(username: str | None) -> str | None:
    if username is None:
        return None
    normalized = username.strip()
    return normalized or None


def _normalized_password(password: object) -> str | None:
    if password is None:
        return None
    secret_value = password.get_secret_value().strip()
    return secret_value or None


def _looks_like_network_error(exc: GarminConnectConnectionError) -> bool:
    text = str(exc).lower()
    network_indicators = (
        "timeout",
        "timed out",
        "connection",
        "dns",
        "temporar",
        "unreachable",
        "reset by peer",
    )
    return any(indicator in text for indicator in network_indicators)

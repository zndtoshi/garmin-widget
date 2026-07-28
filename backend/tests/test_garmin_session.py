from __future__ import annotations

from pathlib import Path

import pytest
from garminconnect import (
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)
from pydantic import SecretStr

from app.core.config import Settings
from app.garmin.errors import (
    GarminAuthenticationFailedError,
    GarminCorruptSessionError,
    GarminMfaRequiredError,
    GarminNetworkError,
    GarminRateLimitError,
    GarminUpstreamError,
    MissingGarminCredentialsError,
)
from app.garmin.session import GarminSessionManager
from app.garmin.store import FilesystemSessionStore
from tests.garmin_fakes import FakeGarminClient, FakeGarminFactory, FakeTokenClient


def _settings(
    tmp_path: Path,
    *,
    username: str | None = "user@example.com",
    password: str | None = "s3cret",
) -> Settings:
    return Settings(
        data_dir=tmp_path,
        garmin_username=username,
        garmin_password=None if password is None else SecretStr(password),
    )


def test_missing_session_triggers_credential_login_and_persistence(tmp_path: Path) -> None:
    store = FilesystemSessionStore(tmp_path)
    client = FakeGarminClient(username="user@example.com", password="s3cret")
    factory = FakeGarminFactory(client)
    manager = GarminSessionManager(
        settings=_settings(tmp_path),
        session_store=store,
        client_factory=factory,
    )

    result = manager.initialize_session()

    assert result is client
    assert factory.created_with == [("user@example.com", "s3cret")]
    assert client.login_calls == [None]
    assert store.exists()
    assert '"di_token": "access-token"' in store.path().read_text(encoding="utf-8")


def test_saved_session_is_reused_without_credential_login(tmp_path: Path) -> None:
    store = FilesystemSessionStore(tmp_path)
    token_client = FakeTokenClient({"di_token": "saved", "di_refresh_token": "saved-r"})
    store.save(token_client)

    client = FakeGarminClient(
        username=None,
        password=None,
        token_client=FakeTokenClient(),
    )
    factory = FakeGarminFactory(client)
    manager = GarminSessionManager(
        settings=_settings(tmp_path, username=None, password=None),
        session_store=store,
        client_factory=factory,
    )

    result = manager.initialize_session()

    assert result is client
    assert factory.created_with == [(None, None)]
    assert client.login_calls == [str(store.path())]
    assert client.client.payload["di_token"] == "saved"


def test_corrupt_saved_session_raises_typed_error(tmp_path: Path) -> None:
    store = FilesystemSessionStore(tmp_path)
    store.path().parent.mkdir(parents=True, exist_ok=True)
    store.path().write_text("not-json", encoding="utf-8")
    manager = GarminSessionManager(
        settings=_settings(tmp_path),
        session_store=store,
        client_factory=FakeGarminFactory(),
    )

    with pytest.raises(GarminCorruptSessionError):
        manager.initialize_session()


def test_absent_credentials_without_session_fail(tmp_path: Path) -> None:
    manager = GarminSessionManager(
        settings=_settings(tmp_path, username=None, password=None),
        session_store=FilesystemSessionStore(tmp_path),
        client_factory=FakeGarminFactory(),
    )

    with pytest.raises(MissingGarminCredentialsError):
        manager.initialize_session()


def test_partial_credentials_without_session_fail(tmp_path: Path) -> None:
    manager = GarminSessionManager(
        settings=_settings(tmp_path, username="user@example.com", password=None),
        session_store=FilesystemSessionStore(tmp_path),
        client_factory=FakeGarminFactory(),
    )

    with pytest.raises(MissingGarminCredentialsError):
        manager.initialize_session()


def test_authentication_failure_is_classified(tmp_path: Path) -> None:
    client = FakeGarminClient(
        username="user@example.com",
        password="s3cret",
        login_error=GarminConnectAuthenticationError("bad credentials"),
    )
    manager = GarminSessionManager(
        settings=_settings(tmp_path),
        session_store=FilesystemSessionStore(tmp_path),
        client_factory=FakeGarminFactory(client),
    )

    with pytest.raises(GarminAuthenticationFailedError):
        manager.initialize_session()
    assert not FilesystemSessionStore(tmp_path).exists()


def test_mfa_required_result_is_classified(tmp_path: Path) -> None:
    client = FakeGarminClient(
        username="user@example.com",
        password="s3cret",
        login_result=("needs_mfa", None),
    )
    manager = GarminSessionManager(
        settings=_settings(tmp_path),
        session_store=FilesystemSessionStore(tmp_path),
        client_factory=FakeGarminFactory(client),
    )

    with pytest.raises(GarminMfaRequiredError):
        manager.initialize_session()


def test_rate_limit_is_classified(tmp_path: Path) -> None:
    client = FakeGarminClient(
        username="user@example.com",
        password="s3cret",
        login_error=GarminConnectTooManyRequestsError("slow down"),
    )
    manager = GarminSessionManager(
        settings=_settings(tmp_path),
        session_store=FilesystemSessionStore(tmp_path),
        client_factory=FakeGarminFactory(client),
    )

    with pytest.raises(GarminRateLimitError):
        manager.initialize_session()


def test_network_timeout_is_classified(tmp_path: Path) -> None:
    client = FakeGarminClient(
        username="user@example.com",
        password="s3cret",
        login_error=GarminConnectConnectionError("Connection timed out"),
    )
    manager = GarminSessionManager(
        settings=_settings(tmp_path),
        session_store=FilesystemSessionStore(tmp_path),
        client_factory=FakeGarminFactory(client),
    )

    with pytest.raises(GarminNetworkError):
        manager.initialize_session()


def test_unexpected_upstream_failure_is_classified(tmp_path: Path) -> None:
    client = FakeGarminClient(
        username="user@example.com",
        password="s3cret",
        login_error=GarminConnectConnectionError("API Error 503 - unavailable"),
    )
    manager = GarminSessionManager(
        settings=_settings(tmp_path),
        session_store=FilesystemSessionStore(tmp_path),
        client_factory=FakeGarminFactory(client),
    )

    with pytest.raises(GarminUpstreamError):
        manager.initialize_session()

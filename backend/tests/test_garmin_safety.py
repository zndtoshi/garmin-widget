from __future__ import annotations

import logging
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.core.config import Settings
from app.garmin import auth_check
from app.garmin.client import GarminFactory
from app.garmin.errors import GarminAuthenticationFailedError
from app.garmin.session import GarminSessionManager
from app.garmin.store import FilesystemSessionStore
from app.main import create_app
from tests.garmin_fakes import FakeGarminClient, FakeGarminFactory


def test_health_performs_no_garmin_or_session_store_access(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []

    def boom_exists(self: FilesystemSessionStore) -> bool:
        calls.append("exists")
        raise AssertionError("session store must not be touched by /health")

    def boom_create(
        self: GarminFactory, username: str | None, password: str | None
    ) -> object:
        calls.append("create")
        raise AssertionError("Garmin factory must not be used by /health")

    def boom_initialize(self: GarminSessionManager) -> object:
        calls.append("initialize")
        raise AssertionError("session manager must not run during /health")

    monkeypatch.setattr(FilesystemSessionStore, "exists", boom_exists)
    monkeypatch.setattr(GarminFactory, "create", boom_create)
    monkeypatch.setattr(GarminSessionManager, "initialize_session", boom_initialize)

    response = TestClient(create_app()).get("/health")

    assert response.status_code == 200
    assert calls == []


def test_cli_output_does_not_expose_secrets(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    settings = Settings(
        data_dir=tmp_path,
        garmin_username="secret-user@example.com",
        garmin_password=SecretStr("super-secret-password"),
    )
    client = FakeGarminClient(
        username="secret-user@example.com",
        password="super-secret-password",
        login_error=GarminAuthenticationFailedError(
            "Garmin authentication failed."
        ),
    )
    manager = GarminSessionManager(
        settings=settings,
        session_store=FilesystemSessionStore(tmp_path),
        client_factory=FakeGarminFactory(client),
    )
    monkeypatch.setattr(auth_check, "build_session_manager", lambda: manager)

    exit_code = auth_check.main()
    captured = capsys.readouterr()

    assert exit_code == 1
    assert "Garmin authentication check failed:" in captured.out
    assert "super-secret-password" not in captured.out
    assert "secret-user@example.com" not in captured.out


def test_logs_do_not_expose_configured_secrets(
    tmp_path: Path,
    caplog: pytest.LogCaptureFixture,
) -> None:
    settings = Settings(
        data_dir=tmp_path,
        garmin_username="secret-user@example.com",
        garmin_password=SecretStr("super-secret-password"),
    )
    store = FilesystemSessionStore(tmp_path)
    store.path().parent.mkdir(parents=True, exist_ok=True)
    store.path().write_text("broken", encoding="utf-8")
    manager = GarminSessionManager(
        settings=settings,
        session_store=store,
        client_factory=FakeGarminFactory(),
    )

    with caplog.at_level(logging.WARNING), pytest.raises(Exception):
        manager.initialize_session()

    joined = "\n".join(record.getMessage() for record in caplog.records)
    assert "super-secret-password" not in joined
    assert "secret-user@example.com" not in joined


def test_cli_configuration_failure_is_safe(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    def boom() -> GarminSessionManager:
        raise ValueError(
            "GARMIN_WIDGET_WIDGET_BEARER_TOKEN=super-secret-password leaked"
        )

    monkeypatch.setattr(auth_check, "build_session_manager", boom)

    exit_code = auth_check.main()
    captured = capsys.readouterr()

    assert exit_code == 1
    assert captured.out.strip() == (
        "Garmin authentication check failed: invalid configuration."
    )
    assert "super-secret-password" not in captured.out
    assert "GARMIN_WIDGET_WIDGET_BEARER_TOKEN" not in captured.out
    assert captured.err == ""


def test_cli_unexpected_failure_is_generic(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    def boom() -> GarminSessionManager:
        raise RuntimeError(
            "token=abc123 cookie=session-cookie password=super-secret-password"
        )

    monkeypatch.setattr(auth_check, "build_session_manager", boom)

    exit_code = auth_check.main()
    captured = capsys.readouterr()

    assert exit_code == 1
    assert captured.out.strip() == (
        "Garmin authentication check failed: unexpected error."
    )
    assert "abc123" not in captured.out
    assert "session-cookie" not in captured.out
    assert "super-secret-password" not in captured.out
    assert "RuntimeError" not in captured.out
    assert captured.err == ""

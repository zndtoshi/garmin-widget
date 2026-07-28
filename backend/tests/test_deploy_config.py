from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
import yaml
from fastapi.testclient import TestClient

from app.core.config import clear_settings_cache
from app.main import create_app
from app.server import (
    DEFAULT_HOST,
    DEFAULT_PORT,
    DEFAULT_WORKERS,
    build_uvicorn_kwargs,
    resolve_port,
)

SECRET_ENV_KEYS = {
    "GARMIN_WIDGET_WIDGET_BEARER_TOKEN",
    "GARMIN_WIDGET_GARMIN_USERNAME",
    "GARMIN_WIDGET_GARMIN_PASSWORD",
}


@pytest.fixture()
def repo_root(backend_dir: Path) -> Path:
    return backend_dir.parent


@pytest.fixture()
def render_blueprint(repo_root: Path) -> dict[str, Any]:
    raw = (repo_root / "render.yaml").read_text(encoding="utf-8")
    loaded = yaml.safe_load(raw)
    assert isinstance(loaded, dict)
    return loaded


def test_resolve_port_defaults_and_env() -> None:
    assert resolve_port(environ={}) == DEFAULT_PORT
    assert resolve_port(environ={"PORT": "10000"}) == 10000
    assert resolve_port("8080") == 8080


@pytest.mark.parametrize(
    "raw",
    ["", "abc", "0", "65536", "-1", "80.5"],
)
def test_resolve_port_rejects_invalid_values(raw: str) -> None:
    with pytest.raises(ValueError, match="PORT"):
        resolve_port(raw)


def test_uvicorn_kwargs_are_single_worker_without_proxy_trust() -> None:
    kwargs = build_uvicorn_kwargs(port=10000)
    assert kwargs["host"] == DEFAULT_HOST
    assert kwargs["port"] == 10000
    assert kwargs["workers"] == DEFAULT_WORKERS == 1
    assert kwargs["proxy_headers"] is False
    assert "forwarded_allow_ips" not in kwargs
    assert kwargs["app"] == "app.main:app"


def test_render_blueprint_structure(render_blueprint: dict[str, Any]) -> None:
    services = render_blueprint["services"]
    assert isinstance(services, list)
    assert len(services) == 1

    service = services[0]
    assert service["type"] == "web"
    assert service["runtime"] == "docker"
    assert service["dockerfilePath"] == "./backend/Dockerfile"
    assert service["dockerContext"] == "./backend"
    assert service["healthCheckPath"] == "/health"
    assert service["numInstances"] == 1

    disk = service["disk"]
    assert disk["name"] == "garmin-widget-data"
    assert disk["mountPath"] == "/var/data"
    assert disk["sizeGB"] == 1

    env_vars = {item["key"]: item for item in service["envVars"]}
    assert env_vars["GARMIN_WIDGET_APP_ENV"]["value"] == "production"
    assert env_vars["GARMIN_WIDGET_DATA_DIR"]["value"] == "/var/data"
    assert env_vars["GARMIN_WIDGET_TIMEZONE"]["value"] == "Europe/Bucharest"
    assert env_vars["GARMIN_WIDGET_REFRESH_COOLDOWN_SECONDS"]["value"] == "60"
    assert env_vars["GARMIN_WIDGET_LOG_LEVEL"]["value"] == "INFO"

    for key in SECRET_ENV_KEYS:
        entry = env_vars[key]
        assert entry.get("sync") is False
        assert "value" not in entry
        assert "generateValue" not in entry


def test_render_blueprint_contains_no_secret_or_personal_values(
    repo_root: Path,
    render_blueprint: dict[str, Any],
) -> None:
    raw = (repo_root / "render.yaml").read_text(encoding="utf-8")
    service = render_blueprint["services"][0]

    assert "plan" not in service
    assert "region" not in service
    assert "repo" not in service
    assert "domains" not in service

    forbidden_snippets = [
        "replace-me-before-enabling-widget-endpoints",
        "Bearer ",
        "@gmail.com",
        ".onrender.com",
        "github.com/",
        "password=",
    ]
    for item in forbidden_snippets:
        assert item not in raw

    for entry in service["envVars"]:
        if entry["key"] in SECRET_ENV_KEYS:
            assert "value" not in entry
        value = entry.get("value")
        if isinstance(value, str):
            assert "token" not in value.lower()
            assert "secret" not in value.lower()
            assert "@" not in value
            assert ".onrender.com" not in value


def test_app_starts_with_representative_render_env(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    clear_settings_cache()
    monkeypatch.chdir(tmp_path)
    token = "r" * 32
    monkeypatch.setenv("GARMIN_WIDGET_APP_ENV", "production")
    monkeypatch.setenv("GARMIN_WIDGET_DATA_DIR", str(tmp_path / "var-data"))
    monkeypatch.setenv("GARMIN_WIDGET_TIMEZONE", "Europe/Bucharest")
    monkeypatch.setenv("GARMIN_WIDGET_REFRESH_COOLDOWN_SECONDS", "60")
    monkeypatch.setenv("GARMIN_WIDGET_LOG_LEVEL", "INFO")
    monkeypatch.setenv("GARMIN_WIDGET_WIDGET_BEARER_TOKEN", token)
    monkeypatch.setenv("PORT", "10000")
    (tmp_path / "var-data").mkdir()

    client = TestClient(create_app())
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    unauthorized = client.get("/api/v1/widget/latest")
    assert unauthorized.status_code == 401
    assert token not in unauthorized.text


def test_health_independent_under_render_like_data_dir(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from app.persistence.snapshot import FilesystemWidgetSnapshotRepository

    calls: list[str] = []

    def boom_load(self: FilesystemWidgetSnapshotRepository) -> object:
        calls.append("load")
        raise AssertionError("health must not read snapshot")

    monkeypatch.setattr(FilesystemWidgetSnapshotRepository, "load", boom_load)
    monkeypatch.setenv("GARMIN_WIDGET_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GARMIN_WIDGET_WIDGET_BEARER_TOKEN", "t" * 32)
    clear_settings_cache()

    response = TestClient(create_app()).get("/health")

    assert response.status_code == 200
    assert calls == []


def test_dockerfile_uses_venv_python_entrypoint(backend_dir: Path) -> None:
    dockerfile = (backend_dir / "Dockerfile").read_text(encoding="utf-8")
    assert 'CMD ["/app/.venv/bin/python", "-m", "app.server"]' in dockerfile
    assert "uv sync --frozen --no-dev" in dockerfile
    assert "uv:0.11.32" in dockerfile
    assert "python:3.12.11-slim-bookworm" in dockerfile
    assert "USER appuser" in dockerfile
    assert "PORT" in dockerfile
    assert "uv run" not in dockerfile.split("CMD", 1)[-1]

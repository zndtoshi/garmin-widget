import os
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import clear_settings_cache
from app.main import create_app


@pytest.fixture(autouse=True)
def reset_settings_cache() -> Iterator[None]:
    clear_settings_cache()
    yield
    clear_settings_cache()


@pytest.fixture(autouse=True)
def restore_environment() -> Iterator[None]:
    original = os.environ.copy()
    yield
    os.environ.clear()
    os.environ.update(original)


@pytest.fixture()
def client() -> TestClient:
    return TestClient(create_app())


@pytest.fixture()
def backend_dir() -> Path:
    return Path(__file__).resolve().parent.parent


@pytest.fixture()
def env_example_path(backend_dir: Path) -> Path:
    return backend_dir / ".env.example"

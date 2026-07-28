from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.garmin.errors import GarminCorruptSessionError
from app.garmin.store import FilesystemSessionStore
from tests.garmin_fakes import FakeTokenClient


def test_save_writes_final_json_atomically(tmp_path: Path) -> None:
    store = FilesystemSessionStore(tmp_path)
    client = FakeTokenClient({"di_token": "abc", "di_refresh_token": "def"})

    store.save(client)

    assert store.path() == tmp_path / "garmin" / "garmin_tokens.json"
    assert store.exists()
    assert json.loads(store.path().read_text(encoding="utf-8")) == client.payload
    leftovers = list((tmp_path / "garmin").glob(".garmin_tokens_*.json"))
    assert leftovers == []


def test_save_replaces_previous_file_only_after_successful_dump(tmp_path: Path) -> None:
    store = FilesystemSessionStore(tmp_path)
    first = FakeTokenClient({"di_token": "old", "di_refresh_token": "old-refresh"})
    store.save(first)
    original = store.path().read_text(encoding="utf-8")

    failing = FakeTokenClient({"di_token": "new", "di_refresh_token": "new-refresh"})

    def boom() -> str:
        raise RuntimeError("dump failed")

    failing.dumps = boom  # type: ignore[method-assign]

    with pytest.raises(RuntimeError, match="dump failed"):
        store.save(failing)

    assert store.path().read_text(encoding="utf-8") == original
    leftovers = list((tmp_path / "garmin").glob(".garmin_tokens_*.json"))
    assert leftovers == []


def test_temp_files_are_cleaned_up_after_persistence_failure(tmp_path: Path) -> None:
    store = FilesystemSessionStore(tmp_path)
    client = FakeTokenClient()

    def boom() -> str:
        raise OSError("disk full")

    client.dumps = boom  # type: ignore[method-assign]

    with pytest.raises(OSError, match="disk full"):
        store.save(client)

    assert list((tmp_path / "garmin").glob(".garmin_tokens_*.json")) == []
    assert not store.exists()


def test_validate_readable_rejects_corrupt_session(tmp_path: Path) -> None:
    store = FilesystemSessionStore(tmp_path)
    store.path().parent.mkdir(parents=True, exist_ok=True)
    store.path().write_text("{not-json", encoding="utf-8")
    client = FakeTokenClient()

    with pytest.raises(GarminCorruptSessionError):
        store.validate_readable(client)

from __future__ import annotations

import os
import uuid
from contextlib import suppress
from pathlib import Path
from typing import Protocol

from app.garmin.errors import GarminCorruptSessionError


class TokenPersistenceClient(Protocol):
    def dump(self, path: str) -> None:
        ...

    def load(self, path: str) -> None:
        ...

    def dumps(self) -> str:
        ...


class SessionStore(Protocol):
    def exists(self) -> bool:
        ...

    def validate_readable(self, client: TokenPersistenceClient) -> None:
        ...

    def save(self, client: TokenPersistenceClient) -> None:
        ...

    def path(self) -> Path:
        ...


class FilesystemSessionStore:
    """Persist Garmin session tokens under DATA_DIR using atomic replace."""

    def __init__(self, data_dir: Path) -> None:
        self._session_dir = data_dir / "garmin"
        self._session_path = self._session_dir / "garmin_tokens.json"

    def path(self) -> Path:
        return self._session_path

    def exists(self) -> bool:
        return self._session_path.is_file()

    def validate_readable(self, client: TokenPersistenceClient) -> None:
        try:
            client.load(str(self._session_path))
        except FileNotFoundError:
            raise
        except Exception as exc:
            raise GarminCorruptSessionError(
                "Saved Garmin session data is missing or corrupt."
            ) from exc

    def save(self, client: TokenPersistenceClient) -> None:
        self._session_dir.mkdir(parents=True, exist_ok=True)
        self._set_restrictive_permissions(self._session_dir, is_directory=True)

        # garminconnect treats non-.json paths as directories, so temp must end in .json.
        tmp_path = self._session_dir / f".garmin_tokens_{uuid.uuid4().hex}.json"
        try:
            self._write_token_payload(tmp_path, client)
            self._set_restrictive_permissions(tmp_path, is_directory=False)
            os.replace(tmp_path, self._session_path)
            self._set_restrictive_permissions(self._session_path, is_directory=False)
        finally:
            if tmp_path.exists():
                tmp_path.unlink(missing_ok=True)

    def _write_token_payload(self, path: Path, client: TokenPersistenceClient) -> None:
        payload = client.dumps()
        flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        fd = os.open(path, flags, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())

    @staticmethod
    def _set_restrictive_permissions(path: Path, *, is_directory: bool) -> None:
        mode = 0o700 if is_directory else 0o600
        with suppress(OSError):
            path.chmod(mode)

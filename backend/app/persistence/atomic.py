from __future__ import annotations

import os
import uuid
from contextlib import suppress
from pathlib import Path


def ensure_restrictive_permissions(path: Path, *, is_directory: bool) -> None:
    mode = 0o700 if is_directory else 0o600
    with suppress(OSError):
        path.chmod(mode)


def write_text_atomic(path: Path, content: str, *, temp_prefix: str) -> None:
    """Write UTF-8 text via a same-directory temp file, fsync, then atomic replace."""
    directory = path.parent
    directory.mkdir(parents=True, exist_ok=True)
    ensure_restrictive_permissions(directory, is_directory=True)

    tmp_path = directory / f".{temp_prefix}_{uuid.uuid4().hex}.json"
    try:
        flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        fd = os.open(tmp_path, flags, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        ensure_restrictive_permissions(tmp_path, is_directory=False)
        os.replace(tmp_path, path)
        ensure_restrictive_permissions(path, is_directory=False)
    finally:
        if tmp_path.exists():
            tmp_path.unlink(missing_ok=True)

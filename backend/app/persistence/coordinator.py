from __future__ import annotations

import threading
from pathlib import Path

_LOCKS_GUARD = threading.Lock()
_LOCKS_BY_DATA_DIR: dict[str, threading.Lock] = {}


def refresh_lock_for(data_dir: Path) -> threading.Lock:
    """Return the process-scoped refresh lock for a resolved data directory.

    Separate ``WidgetRefreshService`` instances that share the same data directory
    therefore share one lock. This still does not coordinate multiple OS processes
    or Render instances.
    """
    key = str(data_dir.expanduser().resolve())
    with _LOCKS_GUARD:
        lock = _LOCKS_BY_DATA_DIR.get(key)
        if lock is None:
            lock = threading.Lock()
            _LOCKS_BY_DATA_DIR[key] = lock
        return lock


def clear_refresh_locks() -> None:
    """Test helper: drop process-scoped locks."""
    with _LOCKS_GUARD:
        _LOCKS_BY_DATA_DIR.clear()

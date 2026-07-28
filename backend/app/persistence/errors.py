class PersistenceError(Exception):
    """Base class for safe persistence failures (no stored content in messages)."""


class NoCachedWidgetError(PersistenceError):
    """No successful widget cache is available."""


class CorruptCacheError(PersistenceError):
    """Cached widget data or refresh metadata is missing, malformed, or unsupported."""


class PersistenceWriteError(PersistenceError):
    """Persisting widget cache or refresh metadata failed."""


class RefreshFailedError(Exception):
    """Live refresh failed and no valid cache fallback exists."""

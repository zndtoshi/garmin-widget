from __future__ import annotations

import os
from collections.abc import Mapping

DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8000
DEFAULT_WORKERS = 1


def resolve_port(
    raw: str | None = None,
    *,
    environ: Mapping[str, str] | None = None,
) -> int:
    """Resolve a TCP port from ``raw`` or the ``PORT`` environment variable."""
    env = environ if environ is not None else os.environ
    value = (env.get("PORT", str(DEFAULT_PORT)) if raw is None else raw).strip()
    if not value:
        raise ValueError("PORT must not be empty")
    try:
        port = int(value)
    except ValueError as exc:
        raise ValueError("PORT must be an integer") from exc
    if not (1 <= port <= 65535):
        raise ValueError("PORT must be between 1 and 65535")
    return port


def build_uvicorn_kwargs(
    *,
    port: int | None = None,
    environ: Mapping[str, str] | None = None,
) -> dict[str, object]:
    """Return Uvicorn settings for the single-worker container/Render entrypoint.

    Proxy/forwarded header processing is disabled. Current routes do not need
    client IP or original scheme from ``X-Forwarded-*``, and trusting arbitrary
    peers for those headers is unsafe on an internet-reachable service.
    """
    resolved_port = resolve_port(None if port is None else str(port), environ=environ)
    return {
        "app": "app.main:app",
        "host": DEFAULT_HOST,
        "port": resolved_port,
        "workers": DEFAULT_WORKERS,
        "proxy_headers": False,
    }


def main() -> None:
    import uvicorn

    kwargs = build_uvicorn_kwargs()
    uvicorn.run(**kwargs)


if __name__ == "__main__":
    main()

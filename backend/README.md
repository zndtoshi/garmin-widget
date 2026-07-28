# Backend (FastAPI)

Private FastAPI service for the `garmin-widget` Android client.

**Status:** Phase 4 persistent cache and refresh coordination are available in-process. The backend still exposes only `GET /health` over HTTP. Authenticated widget HTTP endpoints are not implemented yet.

## Implemented now

- FastAPI app initialization in `app/main.py`
- Typed settings module backed by environment variables (including IANA timezone)
- Structured logging with secret redaction
- Safe centralized exception handlers
- Typed Pydantic response model for health response
- `GET /health` endpoint (no auth, no Garmin calls, no persistent storage access)
- Local Garmin authentication/session lifecycle under `app/garmin/`
- Filesystem session persistence under `DATA_DIR/garmin/garmin_tokens.json`
- Garmin metrics adapter + internal/public models + normalization
- Filesystem persistence for one atomic latest-widget snapshot under `DATA_DIR/widget/`
- In-process refresh orchestration with cooldown, process-scoped lock/deduplication, and stale-cache fallback
- Local CLI auth check: `uv run python -m app.garmin.auth_check`
- Local CLI metrics check: `uv run python -m app.garmin.metrics_check [--date YYYY-MM-DD]`
- Local tests for health, config, logging, errors, Garmin session, metric normalization, and refresh/cache behavior
- `uv` dependency management (`pyproject.toml` + `uv.lock`)
- Production-oriented Dockerfile and `.dockerignore`
- GitHub Actions workflow for lint and tests
- Placeholder-only `.env.example`

## Backend layout

```text
backend/
|- app/
|  |- __init__.py
|  |- main.py
|  |- api/
|  |  |- __init__.py
|  |  `- health.py
|  |- core/
|  |  |- __init__.py
|  |  |- config.py
|  |  |- errors.py
|  |  `- logging.py
|  |- garmin/
|  |  |- __init__.py
|  |  |- adapter.py
|  |  |- auth_check.py
|  |  |- client.py
|  |  |- dates.py
|  |  |- errors.py
|  |  |- metrics_check.py
|  |  |- normalize.py
|  |  |- session.py
|  |  `- store.py
|  |- persistence/
|  |  |- __init__.py
|  |  |- atomic.py
|  |  |- coordinator.py
|  |  |- errors.py
|  |  |- models.py
|  |  `- snapshot.py
|  |- services/
|  |  |- __init__.py
|  |  `- refresh.py
|  `- models/
|     |- __init__.py
|     |- domain.py
|     |- health.py
|     `- widget.py
|- tests/
|  |- conftest.py
|  |- fixtures/
|  |- garmin_fakes.py
|  |- test_config.py
|  |- test_errors.py
|  |- test_garmin_metrics.py
|  |- test_garmin_metrics_cli.py
|  |- test_garmin_safety.py
|  |- test_garmin_session.py
|  |- test_garmin_store.py
|  |- test_health.py
|  |- test_logging.py
|  `- test_widget_refresh.py
|- .env.example
|- .dockerignore
|- Dockerfile
|- pyproject.toml
|- uv.lock
`- README.md
```

## Configuration

Environment variables use the `GARMIN_WIDGET_` prefix.

Copy the placeholder file when configuring locally:

```powershell
Copy-Item .env.example .env
```

Edit the ignored `.env` file only. Keep real Garmin credentials out of source control, chat, logs, and committed files.

Current settings include:

- `GARMIN_WIDGET_APP_ENV`
- `GARMIN_WIDGET_SERVICE_NAME`
- `GARMIN_WIDGET_APP_VERSION`
- `GARMIN_WIDGET_LOG_LEVEL`
- `GARMIN_WIDGET_DATA_DIR`
- `GARMIN_WIDGET_REFRESH_COOLDOWN_SECONDS`
- `GARMIN_WIDGET_TIMEZONE` (IANA name; used only to choose the Garmin calendar date)
- `GARMIN_WIDGET_WIDGET_BEARER_TOKEN`
- `GARMIN_WIDGET_GARMIN_USERNAME` (optional; comment out unless needed)
- `GARMIN_WIDGET_GARMIN_PASSWORD` (optional; comment out unless needed)

Production validation currently requires a widget bearer token and rejects half-configured Garmin credentials. Timezone values must be valid IANA names.

## Local Garmin authentication check

1. Copy `.env.example` to `.env`.
2. Uncomment and set Garmin credentials only in the ignored `.env` file.
3. From `backend`, run:

```powershell
uv run python -m app.garmin.auth_check
```

Successful runs create reusable session material at:

```text
<GARMIN_WIDGET_DATA_DIR>/garmin/garmin_tokens.json
```

Later runs reuse that session when it remains valid. The CLI prints only a safe success or failure message. It never prints credentials, tokens, cookies, or raw Garmin responses.

## Local Garmin metrics check

After a successful auth check (or with valid credentials configured), fetch and normalize metrics:

```powershell
uv run python -m app.garmin.metrics_check
uv run python -m app.garmin.metrics_check --date 2026-07-28
```

When `--date` is omitted, the command uses today's calendar date in `GARMIN_WIDGET_TIMEZONE`. Output is normalized widget-shaped JSON only (camelCase, `schemaVersion=1`). Failures print a single safe line and exit nonzero. Raw Garmin payloads are never printed.

## Persistent widget cache and refresh coordination

Phase 4 adds filesystem persistence and an in-process refresh service. HTTP widget routes are still not exposed.

Persisted under `DATA_DIR` as **one atomic snapshot file**:

```text
<GARMIN_WIDGET_DATA_DIR>/widget/latest_snapshot.json
```

The snapshot envelope contains:

- `persistenceFormatVersion` (persistence format, separate from public widget `schemaVersion`)
- `lastSuccessfulRefreshAt` (cooldown metadata from the same successful refresh)
- `payload` (the last successful normalized version-one `WidgetResponse`)

Writes use a same-directory temporary file, `fsync`, and `os.replace`, so a failed write leaves the previous complete snapshot unchanged. Raw Garmin responses, credentials, cookies, tokens, and exception text are never persisted.

`WidgetRefreshService`:

- `get_latest()` returns cache with `refreshStatus=CACHE_HIT` and `stale=false` (never contacts Garmin)
- `refresh()` honors cooldown from the last **successful** live refresh timestamp in the same snapshot
- Independently constructed services that share one `DATA_DIR` share one process-scoped refresh lock
- Failed live refresh or failed persistence with a valid snapshot returns `UPSTREAM_UNAVAILABLE` and `stale=true` without modifying the stored snapshot
- Failed live refresh/persistence with no valid snapshot raises a typed safe error for the future API layer
- Transient `CACHE_HIT` / `COOLDOWN` / `UPSTREAM_UNAVAILABLE` responses stay in memory only

### Single-process locking assumption

Refresh deduplication uses a process-scoped lock keyed by data directory. It coordinates multiple service objects in one OS process, but **not** multiple OS processes or Render instances. The first Render deployment must run a **single worker/instance**. Scaling requires a cross-process or distributed lock before that change.

There is still **no** public Garmin-backed HTTP endpoint. `/api/v1/widget/latest` and `/api/v1/widget/refresh` are not implemented yet.

## Requirements

- Python 3.12+
- `uv`

## Install uv (PowerShell)

```powershell
powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"
```

Restart your shell after installing `uv` so it is on `PATH`.

## Sync dependencies

From the `backend` directory:

```powershell
uv sync
```

## Run locally

From the `backend` directory:

```powershell
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

## Run tests

From the `backend` directory:

```powershell
uv run pytest
```

## Run lint

From the `backend` directory:

```powershell
uv run ruff check .
```

## Build Docker image

From the repository root:

```powershell
docker build -t garmin-widget-backend:0.1.0 ./backend
```

## Run Docker container

```powershell
docker run --rm -p 8000:8000 --name garmin-widget-backend garmin-widget-backend:0.1.0
```

## Call `/health`

```powershell
Invoke-RestMethod -Method GET -Uri http://127.0.0.1:8000/health
```

Expected response:

```json
{
  "status": "ok",
  "service": "garmin-widget-backend",
  "version": "0.1.0"
}
```

## Security notes

- Never paste Garmin credentials or session tokens into source, logs, chat, or commits.
- Keep `.env` and `DATA_DIR` session files local and ignored.
- `/health` does not contact Garmin or read the session store.
- No widget-authenticated API endpoints exist yet.
- Client-facing errors are sanitized and should not expose stack traces or secrets.
- Widget snapshot files under `DATA_DIR/widget/` must stay local and ignored.

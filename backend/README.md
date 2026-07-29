# Backend (FastAPI)

Private FastAPI service for the `garmin-widget` Android client.

**Status:** Backend deployed and verified at `https://garmin.zndtoshi.com`. Phase 8A expanded data contract implemented locally (not yet deployed). Single worker/instance with persistent disk at `/var/data`.

## Implemented now

- FastAPI app initialization in `app/main.py`
- Typed settings module backed by environment variables (including IANA timezone)
- Structured logging with secret redaction
- Safe centralized exception handlers
- Typed Pydantic response models for health and widget payloads
- `GET /health` endpoint (no auth, no Garmin calls, no persistent storage access)
- Authenticated widget endpoints:
  - `GET /api/v1/widget/latest`
  - `POST /api/v1/widget/refresh`
- Bearer-token authentication (OpenAPI `WidgetBearer`, timing-safe compare)
- Local Garmin authentication/session lifecycle under `app/garmin/`
- Filesystem session persistence under `DATA_DIR/garmin/garmin_tokens.json`
- Garmin metrics adapter + internal/public models + normalization
- **Expanded response contract (Phase 8A)**: additive nullable fields — `sleepStages`, `hrvTrend` (bounded 7-day, initial backfill + same-day reuse), `bodyBatteryTimeline`, `stressTimeline` (sorted/deduped, values 0–100, max 48), `lastActivity` (`startTimeGMT` is the trusted UTC source, including naive GMT strings; local-only timestamps are ignored). Optional additive `lastActivity.heartRateTimeline` (max 48 `{elapsedSeconds, heartRate}` points) is fetched via a transient activity-details call when an activity ID exists; IDs/GPS/raw details are never exposed. All backward-compatible with `schemaVersion=1`.
- **Call budget**: without activity details, initial expanded refresh ≤ 15 Garmin calls and ordinary same-day refresh = 9 calls. With a usable latest-activity ID, budgets become ≤ **16** / **10**.
- Filesystem persistence for one atomic latest-widget snapshot under `DATA_DIR/widget/`
- Process-scoped refresh orchestration with cooldown, lock/deduplication, and stale-cache fallback
- Render-compatible container entrypoint (`/app/.venv/bin/python -m app.server`) honoring `PORT` with one Uvicorn worker and proxy headers disabled
- Root `render.yaml` Blueprint and operator runbook (`docs/render-deployment.md`)
- Local CLI auth check: `uv run python -m app.garmin.auth_check`
- Local CLI metrics check: `uv run python -m app.garmin.metrics_check [--date YYYY-MM-DD]`
- Local tests for health, auth/API, config, logging, errors, Garmin session, metric normalization, and refresh/cache behavior
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
|  |  |- auth.py
|  |  |- health.py
|  |  `- widget.py
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
|  |  |- factory.py
|  |  |- metrics_provider.py
|  |  `- refresh.py
|  `- models/
|     |- __init__.py
|     |- domain.py
|     |- health.py
|     `- widget.py
|- tests/
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

Edit the ignored `.env` file only. Keep real Garmin credentials and the widget bearer token out of source control, chat, logs, screenshots, and committed files.

### Generate a private widget bearer token

Do **not** use example placeholders. Generate a cryptographically random token of at least 32 random bytes:

```powershell
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

Put the result only in:

- ignored local `.env` as `GARMIN_WIDGET_WIDGET_BEARER_TOKEN=...`
- Render secrets later
- Android secure storage later

Never put a real token in source control, chat, screenshots, logs, or `.env.example`.

Current settings include:

- `GARMIN_WIDGET_APP_ENV`
- `GARMIN_WIDGET_SERVICE_NAME`
- `GARMIN_WIDGET_APP_VERSION`
- `GARMIN_WIDGET_LOG_LEVEL`
- `GARMIN_WIDGET_DATA_DIR`
- `GARMIN_WIDGET_REFRESH_COOLDOWN_SECONDS`
- `GARMIN_WIDGET_TIMEZONE` (IANA name; used only to choose the Garmin calendar date)
- `GARMIN_WIDGET_WIDGET_BEARER_TOKEN` (optional locally; required in production and for widget endpoints)
- `GARMIN_WIDGET_GARMIN_USERNAME` (optional; comment out unless needed)
- `GARMIN_WIDGET_GARMIN_PASSWORD` (optional; comment out unless needed)

Production validation requires a non-empty widget bearer token that is not a known placeholder and is at least 32 characters. Prefer `secrets.token_urlsafe(32)`. Locally the app may start without a token so `/health` works; widget endpoints return `503` until a real token is configured.

## Widget HTTP API

All `/api/v1/widget/*` routes require:

```http
Authorization: Bearer <private widget token>
```

`/health` remains unauthenticated.

| Method | Path | Auth | Contacts Garmin | Success body |
|--------|------|------|-----------------|--------------|
| `GET` | `/health` | No | No | health status |
| `GET` | `/api/v1/widget/latest` | Bearer | No | `WidgetResponse` (`CACHE_HIT`) |
| `POST` | `/api/v1/widget/refresh` | Bearer | Maybe | `WidgetResponse` (`SUCCESS` / `COOLDOWN` / stale fallback) |
| `GET` | `/api/v1/widget/history` | — | — | Not implemented (future) |

Stable error mapping:

| Situation | HTTP |
|-----------|------|
| Missing/malformed/wrong bearer token | `401` + `WWW-Authenticate: Bearer` |
| No snapshot for `/latest` | `404` |
| Server bearer token unset/empty | `503` |
| Corrupt snapshot / refresh failure without fallback | `503` |
| Upstream failure with valid snapshot | `200` with `UPSTREAM_UNAVAILABLE`, `stale=true` |

Example calls (PowerShell), after setting a local token and running the server:

```powershell
Invoke-RestMethod -Method GET -Uri http://127.0.0.1:8000/health
Invoke-RestMethod -Method GET -Uri http://127.0.0.1:8000/api/v1/widget/latest -Headers @{ Authorization = "Bearer $env:GARMIN_WIDGET_WIDGET_BEARER_TOKEN" }
Invoke-RestMethod -Method POST -Uri http://127.0.0.1:8000/api/v1/widget/refresh -Headers @{ Authorization = "Bearer $env:GARMIN_WIDGET_WIDGET_BEARER_TOKEN" }
```

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

Persisted under `DATA_DIR` as **one atomic snapshot file**:

```text
<GARMIN_WIDGET_DATA_DIR>/widget/latest_snapshot.json
```

The snapshot envelope contains:

- `persistenceFormatVersion` (persistence format, separate from public widget `schemaVersion`)
- `lastSuccessfulRefreshAt` (cooldown metadata from the same successful refresh)
- `payload` (the last successful normalized version-one `WidgetResponse`)

Writes use a same-directory temporary file, `fsync`, and `os.replace`, so a failed write leaves the previous complete snapshot unchanged. Raw Garmin responses, credentials, cookies, tokens, and exception text are never persisted.

`WidgetRefreshService` (used by the HTTP layer):

- `get_latest()` returns cache with `refreshStatus=CACHE_HIT` and `stale=false` (never contacts Garmin)
- `refresh()` honors cooldown from the last **successful** live refresh timestamp in the same snapshot
- Independently constructed services that share one `DATA_DIR` share one process-scoped refresh lock
- Failed live refresh or failed persistence with a valid snapshot returns `UPSTREAM_UNAVAILABLE` and `stale=true` without modifying the stored snapshot
- Transient `CACHE_HIT` / `COOLDOWN` / `UPSTREAM_UNAVAILABLE` responses stay in memory only

### Single-process locking assumption

Refresh deduplication uses a process-scoped lock keyed by data directory. It coordinates multiple service objects in one OS process, but **not** multiple OS processes or Render instances. The first Render deployment must run a **single worker/instance**. Scaling requires a cross-process or distributed lock before that change.

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

## Docker image pins

- Python base: `python:3.12.11-slim-bookworm` (bump the patch tag intentionally when upgrading)
- `uv`: copied from `ghcr.io/astral-sh/uv:0.11.32` (bump when upgrading the package manager)
- Runtime install uses `uv sync --frozen --no-dev`
- Runtime start uses `/app/.venv/bin/python -m app.server` (no `uv run` at startup)
- Proxy/forwarded header trust is disabled; routes do not depend on client IP/scheme from `X-Forwarded-*`

Build from the repository root:

```powershell
docker build -t garmin-widget-backend:0.1.0 ./backend
```

The image does not include `.env`, tests, local data, tokens, or Git metadata. Container health checks call `/health` on `$PORT` (default `8000`).

Local Docker runtime validation depends on Docker being installed on the operator machine; CI builds the image without pushing. If Docker is unavailable locally, rely on the `docker-build` CI job.

## Deployment

See [docs/render-deployment.md](../docs/render-deployment.md) for the Render Blueprint, persistent disk, secret handling, session bootstrap, and checklists. Configuration is prepared in-repo; creating the live Render service and DNS is an operator step outside this repository change.

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

- Never paste Garmin credentials, session tokens, or the widget bearer token into source, logs, chat, or commits.
- Keep `.env` and `DATA_DIR` session/snapshot files local and ignored.
- `/health` does not contact Garmin or read the session/snapshot stores.
- Widget endpoints require a private bearer token; missing/wrong tokens receive a generic `401`.
- Client-facing errors are sanitized and should not expose stack traces or secrets.
- Widget snapshot files under `DATA_DIR/widget/` must stay local and ignored.

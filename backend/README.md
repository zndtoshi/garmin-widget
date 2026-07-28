# Backend (FastAPI)

Private FastAPI service for the `garmin-widget` Android client.

**Status:** Phase 2 local Garmin authentication is available. The backend still exposes only `GET /health` over HTTP. There is no Garmin-backed widget endpoint yet.

## Implemented now

- FastAPI app initialization in `app/main.py`
- Typed settings module backed by environment variables
- Structured logging with secret redaction
- Safe centralized exception handlers
- Typed Pydantic response model for health response
- `GET /health` endpoint (no auth, no Garmin calls, no persistent storage access)
- Local Garmin authentication/session lifecycle under `app/garmin/`
- Filesystem session persistence under `DATA_DIR/garmin/garmin_tokens.json`
- Local CLI auth check: `uv run python -m app.garmin.auth_check`
- Local tests for health, config, logging, errors, and Garmin session behavior
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
|  |  |- auth_check.py
|  |  |- client.py
|  |  |- errors.py
|  |  |- session.py
|  |  `- store.py
|  `- models/
|     |- __init__.py
|     `- health.py
|- tests/
|  |- conftest.py
|  |- garmin_fakes.py
|  |- test_config.py
|  |- test_errors.py
|  |- test_garmin_safety.py
|  |- test_garmin_session.py
|  |- test_garmin_store.py
|  |- test_health.py
|  `- test_logging.py
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
- `GARMIN_WIDGET_WIDGET_BEARER_TOKEN`
- `GARMIN_WIDGET_GARMIN_USERNAME` (optional; comment out unless needed)
- `GARMIN_WIDGET_GARMIN_PASSWORD` (optional; comment out unless needed)

Production validation currently requires a widget bearer token and rejects half-configured Garmin credentials.

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

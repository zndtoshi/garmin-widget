# Backend (FastAPI)

Private FastAPI service for the `garmin-widget` Android client.

**Status:** Milestone 2 in progress. This backend currently implements only `GET /health`.

## Implemented now

- FastAPI app initialization in `app/main.py`
- Typed Pydantic response model for health response
- `GET /health` endpoint (no auth, no Garmin calls, no persistent storage access)
- Local tests for health contract
- `uv` dependency management (`pyproject.toml` + `uv.lock`)
- Production-oriented Dockerfile and `.dockerignore`

## Backend layout

```text
backend/
├── app/
│   ├── __init__.py
│   ├── main.py
│   ├── api/
│   │   ├── __init__.py
│   │   └── health.py
│   └── models/
│       ├── __init__.py
│       └── health.py
├── tests/
│   └── test_health.py
├── pyproject.toml
├── uv.lock
├── Dockerfile
├── .dockerignore
└── README.md
```

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

- No Garmin integration yet.
- No authentication yet beyond future plans.
- Do not commit Garmin credentials, bearer tokens, session files, `.env` files, or local caches.

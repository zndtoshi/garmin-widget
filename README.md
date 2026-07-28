# garmin-widget

Private personal-use project: an Android home-screen widget that shows selected Garmin Connect metrics through a private FastAPI backend.

## Goal

The Android widget talks only to the backend. The backend owns Garmin access, normalizes the data, and eventually serves cached or refreshed widget payloads over HTTPS.

## High-level architecture

```text
Android home-screen widget
  -> HTTPS bearer authentication
Private FastAPI service on Render
  -> reusable unofficial Garmin session
Garmin Connect
```

## Current status

Backend foundation through local metric normalization is in progress. `GET /health`, configuration, logging, sanitized errors, Garmin session management, metric fetch/normalization, tests, CI, and Docker scaffolding are implemented. Widget-authenticated endpoints, caching, cooldown logic, Android, and Render deployment are not complete yet.

## Repository layout

```text
garmin-widget/
|- backend/
|  |- app/
|  |- tests/
|  |- .env.example
|  |- .dockerignore
|  |- Dockerfile
|  |- README.md
|  |- pyproject.toml
|  `- uv.lock
|- android/
|- docs/
|  |- architecture.md
|  `- implementation-plan.md
|- shared/
|  `- widget-response.example.json
|- .github/
|  `- workflows/
|- .gitignore
|- README.md
`- PROJECT.md
```

## Dependency management

Backend Python dependencies use **uv** with:

- `backend/pyproject.toml`
- `backend/uv.lock`

Do **not** use `requirements.txt`.

## Planned API overview

| Method | Path | Notes |
|--------|------|-------|
| `GET` | `/health` | Service status only; no Garmin contact |
| `GET` | `/api/v1/widget/latest` | Cached payload only |
| `POST` | `/api/v1/widget/refresh` | Manual refresh with cooldown and fallback |
| `GET` | `/api/v1/widget/history` | Future only; out of scope for version one |

## Important warnings

- Unofficial Garmin access may break if Garmin changes its private APIs.
- Never commit Garmin credentials, session tokens, widget bearer tokens, local caches, or `.env` files.
- Version one persistent storage is limited to session material, the latest normalized cache, and minimal refresh metadata.

## Further reading

- [PROJECT.md](PROJECT.md)
- [docs/architecture.md](docs/architecture.md)
- [docs/implementation-plan.md](docs/implementation-plan.md)
- [backend/README.md](backend/README.md)

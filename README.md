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

Backend foundation through Phase 6 deployment **preparation** is implemented. Health + authenticated widget API, Garmin session/metrics, atomic snapshot cache, Render Blueprint (`render.yaml`), container entrypoint, CI Docker build validation, and the [Render runbook](docs/render-deployment.md) are in place. A live Render service, custom domain/DNS, and Android are **not** complete yet. First deployment must remain one worker/instance with a persistent disk.

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
|  |- implementation-plan.md
|  `- render-deployment.md
|- shared/
|  `- widget-response.example.json
|- .github/
|  `- workflows/
|- render.yaml
|- .gitignore
|- README.md
`- PROJECT.md
```

## Dependency management

Backend Python dependencies use **uv** with:

- `backend/pyproject.toml`
- `backend/uv.lock`

Do **not** use `requirements.txt`.

## API overview

| Method | Path | Notes |
|--------|------|-------|
| `GET` | `/health` | Unauthenticated service status; no Garmin contact |
| `GET` | `/api/v1/widget/latest` | Bearer auth; cached payload only |
| `POST` | `/api/v1/widget/refresh` | Bearer auth; manual refresh with cooldown and fallback |
| `GET` | `/api/v1/widget/history` | Future only; out of scope for version one |

## Important warnings

- Unofficial Garmin access may break if Garmin changes its private APIs.
- Never commit Garmin credentials, session tokens, widget bearer tokens, local caches, or `.env` files.
- Generate the widget bearer token with `python -c "import secrets; print(secrets.token_urlsafe(32))"` and store it only in ignored `.env`, Render secrets later, and Android secure storage later—never in source control, chat, screenshots, logs, or example files.
- Version one persistent storage is limited to session material, the latest normalized cache, and minimal refresh metadata.

## Further reading

- [PROJECT.md](PROJECT.md)
- [docs/architecture.md](docs/architecture.md)
- [docs/implementation-plan.md](docs/implementation-plan.md)
- [docs/render-deployment.md](docs/render-deployment.md)
- [backend/README.md](backend/README.md)

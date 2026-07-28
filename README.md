# garmin-widget

Private personal-use project: an Android home-screen widget that shows a small set of Garmin Connect metrics through a private FastAPI backend.

This repository is **not** a public product. It is intended for a single private deployment.

## Goal

Tap refresh on an Android widget → private backend fetches (or returns cached) normalized Garmin metrics → widget displays them. No automatic frequent polling.

## High-level architecture

```
Android home-screen widget
     ↓ HTTPS bearer authentication
Private FastAPI service on Render
     ↓ reusable unofficial Garmin session
Garmin Connect
```

The widget never talks to Garmin directly and never receives Garmin credentials or session tokens. Details: [PROJECT.md](PROJECT.md) and [docs/architecture.md](docs/architecture.md).

## Current status

**Milestone 1 in progress:** specification, repository structure, and architecture documentation only.

There is **no production code yet**. The `backend/` and `android/` trees are structural placeholders. Do not expect a running API or installable app from this commit set.

## Planned milestones

| Milestone | Scope |
|-----------|--------|
| **1** | Specification, repository structure, architecture documentation |
| **2** | Minimal FastAPI backend, `GET /health`, local tests, Dockerfile, Render deployment |
| **3** | Local Garmin authentication, reusable session-token persistence, fetch/inspect Garmin data locally (no public Garmin-backed endpoint yet) |
| **4** | Metric normalization, persistent cache, authenticated latest/refresh endpoints, cooldown and error fallback |
| **5** | Kotlin Android app, Jetpack Glance widget, local cache, manual refresh, open Garmin Connect from widget body |
| **6** | Adaptive widget sizes, configurable displayed metrics, polish and reliability testing |

## Repository layout

```
garmin-widget/
├── backend/                 # FastAPI service (not implemented yet)
│   ├── app/
│   ├── tests/
│   ├── Dockerfile
│   ├── README.md
│   └── pyproject.toml       # uv-managed; uv.lock added when deps exist
├── android/                 # Kotlin / Jetpack Glance app (not implemented yet)
├── docs/
│   └── architecture.md
├── shared/
│   └── widget-response.example.json
├── .github/
│   └── workflows/           # CI placeholders (not configured yet)
├── .gitignore
├── README.md
└── PROJECT.md
```

## Dependency management (backend)

Python dependencies will be managed with **[uv](https://github.com/astral-sh/uv)** using:

- `backend/pyproject.toml`
- `backend/uv.lock` (generated later; commit the lockfile when dependencies exist)

Do **not** use `requirements.txt`.

## Planned public API (v1)

| Method | Path | Notes |
|--------|------|--------|
| `GET` | `/health` | Service status; does **not** contact Garmin |
| `GET` | `/api/v1/widget/latest` | Last successful cache; does **not** contact Garmin |
| `POST` | `/api/v1/widget/refresh` | Manual refresh (cooldown, lock, cache, fallback) |
| `GET` | `/api/v1/widget/history` | **Future only** — out of scope for version one |

Widget endpoints require `Authorization: Bearer <private widget token>`. `/health` may be unauthenticated.

## Important warnings

- **Unofficial Garmin access may break** if Garmin changes its private APIs. This project depends on an unofficial client (`python-garminconnect`) and is inherently fragile.
- **Never commit** Garmin credentials, session tokens, the private widget bearer token, local caches, or `.env` files. See `.gitignore`.
- Persistent Render storage in v1 holds only session tokens, the latest normalized widget cache, and minimal refresh metadata — **not** long-term health history.

## Further reading

- [PROJECT.md](PROJECT.md) — full specification and milestones
- [docs/architecture.md](docs/architecture.md) — diagrams and component boundaries
- [shared/widget-response.example.json](shared/widget-response.example.json) — example widget payload

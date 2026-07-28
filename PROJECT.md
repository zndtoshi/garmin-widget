# garmin-widget - Project Specification

Private personal-use project. This document defines architecture, API, payload schema, backend responsibilities, and milestones.

Related docs: [README.md](README.md), [docs/architecture.md](docs/architecture.md), [shared/widget-response.example.json](shared/widget-response.example.json).

## Architecture

```text
Android home-screen widget
     ↓ HTTPS bearer authentication
Private FastAPI service on Render
     ↓ reusable unofficial Garmin session
Garmin Connect
```

The Android widget never talks to Garmin directly. It only calls the private backend over HTTPS with a bearer token. The backend alone holds Garmin session material and talks to Garmin Connect through an unofficial client.

See [docs/architecture.md](docs/architecture.md) for Mermaid diagrams (system, refresh flows, auth boundaries, deployment).

## Current implementation state

Milestone 2 is in progress.

Implemented now:

- FastAPI app skeleton and route wiring
- `GET /health` endpoint returning typed response model
- Local tests for the health endpoint
- `uv` project configuration and lockfile
- Dockerfile and `.dockerignore`

Not implemented yet:

- Garmin authentication/session handling
- Garmin client
- Metric normalization logic
- Cache/persistence
- API authentication for widget endpoints
- Widget endpoints beyond `/health`
- Render deployment completion

## Goals

- Show a small set of daily Garmin metrics on an Android home-screen widget.
- Keep the surface area minimal and private (personal use only).
- Refresh Garmin only on explicit user action (widget refresh tap).
- Prefer last-known-good cached data when Garmin is unavailable or cooldown applies.
- Never expose Garmin credentials, session tokens, stack traces, or sensitive upstream responses to the client.
- Keep the REST API independent of raw Garmin response structures.

## Non-goals (version one)

- Public multi-user SaaS
- Full Garmin Connect feature parity
- Automatic frequent background polling of Garmin
- Exposing raw Garmin API responses to the widget
- Long-term health history storage (`GET /api/v1/widget/history` is documented as future-only)
- Committing secrets, tokens, or local persistent data

## Backend

### Stack

- Python 3.12+
- FastAPI
- Pydantic
- Uvicorn
- Dependency management with **[uv](https://github.com/astral-sh/uv)** via `backend/pyproject.toml` and `backend/uv.lock`
- Unofficial [`python-garminconnect`](https://github.com/cyberjunky/python-garminconnect) library (planned for Milestone 3)
- **Do not use `requirements.txt`.**

### Backend responsibilities (separated)

| Responsibility | Role |
|----------------|------|
| **Configuration** | Load env-based settings (bearer token, data directory, cooldown, ports, etc.). No secrets in code. |
| **API authentication** | Validate `Authorization: Bearer <private widget token>` on widget endpoints. Distinct from Garmin auth. |
| **REST API** | HTTP surface only: accept requests, return **normalized** widget payloads / health status. Must **not** depend on raw Garmin response structures. |
| **Garmin authentication / session management** | Bootstrap and renew reusable Garmin session tokens; persist them under the data directory. |
| **Garmin client** | Call unofficial Garmin APIs and return **normalized internal models** (not raw upstream JSON). |
| **Metric normalization** | Map Garmin client models into the public widget payload schema (`schemaVersion`, camelCase fields, nullability, status enums). |
| **Cache / persistence** | Store latest successful normalized payload and minimal refresh metadata; serve cache for `/latest`, cooldown hits, and failure fallback. |
| **Error handling** | Map failures to safe client messages; set `stale` / `refreshStatus`; never leak credentials, tokens, stack traces, or sensitive upstream bodies. |

Layering rule: **REST API -> normalized models <- metric normalization <- Garmin client <- session management**. The API never consumes raw Garmin shapes.

### Data directory (persistent storage)

Configurable directory (for example `DATA_DIR`). On Render, persistent storage in version one holds **only**:

- Garmin session tokens
- Latest normalized widget cache
- Minimal refresh metadata (e.g. last successful refresh time, cooldown bookkeeping)

Do **not** store long-term health history in version one.

### Refresh behavior (precise)

| Endpoint | Contacts Garmin? | Behavior |
|----------|------------------|----------|
| `GET /health` | **No** | Returns service status only. |
| `GET /api/v1/widget/latest` | **No** | Returns the last successful cached payload. |
| `POST /api/v1/widget/refresh` | **Maybe** | User-initiated refresh path (see below). |

`POST /api/v1/widget/refresh` rules:

1. Called when the user taps refresh on the widget.
2. If the last **successful** Garmin refresh is younger than the configured cooldown, **do not** contact Garmin; return the cached payload (with an appropriate `refreshStatus`, e.g. cooldown / cache hit).
3. Otherwise contact Garmin, normalize metrics, update the persistent cache, and return the updated payload (`stale: false`, `refreshStatus: SUCCESS` on success).
4. **Concurrent refresh requests must be deduplicated or locked** so they do not trigger duplicate Garmin requests.
5. If Garmin refresh fails and a last successful cache exists: retain and return that cache with:
   - `stale: true`
   - an appropriate `refreshStatus` (e.g. `ERROR` / `UPSTREAM_UNAVAILABLE`)
   - a **safe** error message suitable for clients (no secrets, tokens, stack traces, or sensitive upstream payloads)
6. Garmin credentials, session tokens, stack traces, and sensitive upstream responses must **never** be returned.

Exact `refreshStatus` enum values are finalized at implementation time; documentation and the example payload use illustrative values such as `SUCCESS`.

## Planned API

### Version one (implement)

| Method | Path | Auth | Contacts Garmin | Purpose |
|--------|------|------|-----------------|---------|
| `GET` | `/health` | Optional (may be unauthenticated) | No | Service status / liveness |
| `GET` | `/api/v1/widget/latest` | Required | No | Last successful cached widget payload |
| `POST` | `/api/v1/widget/refresh` | Required | Only if cooldown elapsed | Manual refresh with cooldown, lock, cache update, failure fallback |

### Future (not version one)

| Method | Path | Status |
|--------|------|--------|
| `GET` | `/api/v1/widget/history` | **Out of scope for the first version.** Documented only so the API namespace stays coherent. Do not implement or store history yet. |

### Authentication

- Scheme: `Authorization: Bearer <private widget token>`
- `/health` may be unauthenticated.
- All `/api/v1/widget/*` endpoints **must** require authentication.
- The widget bearer token is a private shared secret for this personal deployment. It is **not** a Garmin credential.

## Widget payload schema

Public JSON uses camelCase. Fields that Garmin may omit are **nullable**. Example: [shared/widget-response.example.json](shared/widget-response.example.json).

| Field | Type (conceptual) | Notes |
|-------|-------------------|--------|
| `schemaVersion` | number | Payload schema version (start at `1`) |
| `date` | string \| null | Calendar date for the metrics (`YYYY-MM-DD`) |
| `sleepScore` | number \| null | Sleep score |
| `sleepDurationSeconds` | number \| null | Sleep duration in seconds |
| `overnightHrv` | number \| null | Overnight HRV |
| `hrvStatus` | string \| null | e.g. `BALANCED` |
| `bodyBattery` | number \| null | Body battery |
| `restingHeartRate` | number \| null | Resting HR |
| `stress` | number \| null | Stress |
| `trainingReadiness` | number \| null | Training readiness |
| `garminSyncAt` | string \| null | Last known Garmin sync time (ISO-8601 UTC) |
| `refreshedAt` | string \| null | When the backend last successfully refreshed the cache (ISO-8601 UTC) |
| `stale` | boolean | `true` when serving cache after failed/skipped live refresh as applicable |
| `refreshStatus` | string | e.g. `SUCCESS`, cooldown, or error statuses |
| `source` | string | e.g. `garmin-connect-unofficial` |

## Deployment (planned)

- Backend hosted as a private FastAPI service on **Render**
- HTTPS only for widget <-> backend
- Environment-based configuration for bearer token, data directory, cooldown, and Garmin session material (never committed)
- Persistent disk/volume for session tokens + latest cache + minimal refresh metadata only

## Milestone plan

### Milestone 1 - Specification and structure

- Specification (`PROJECT.md`, `README.md`)
- Repository structure (monorepo placeholders)
- Architecture documentation (`docs/architecture.md`)

### Milestone 2 - Minimal deployable API shell (in progress)

- Minimal FastAPI backend
- `GET /health`
- Local tests
- Dockerfile
- Render deployment (not complete yet)

### Milestone 3 - Local Garmin access (private)

- Local Garmin authentication
- Reusable session-token persistence
- Fetch and inspect Garmin data locally
- **No** public Garmin-backed endpoint yet

### Milestone 4 - Normalized widget API

- Metric normalization
- Persistent cache
- Authenticated `GET /api/v1/widget/latest` and `POST /api/v1/widget/refresh`
- Cooldown, concurrent-request locking/deduplication, and error fallback

### Milestone 5 - Android widget

- Kotlin Android app
- Jetpack Glance widget
- Local cache
- Manual refresh interaction
- Open Garmin Connect from widget body

### Milestone 6 - Polish

- Adaptive widget sizes
- Configurable displayed metrics
- Polish and reliability testing

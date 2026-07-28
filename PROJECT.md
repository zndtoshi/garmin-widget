# garmin-widget - Project Specification

Private personal-use project. This document defines architecture, API, payload schema, backend responsibilities, and milestones.

Related docs: [README.md](README.md), [docs/architecture.md](docs/architecture.md), [docs/implementation-plan.md](docs/implementation-plan.md), [shared/widget-response.example.json](shared/widget-response.example.json).

## Architecture

```text
Android home-screen widget
  -> HTTPS bearer authentication
Private FastAPI service on Render
  -> reusable unofficial Garmin session
Garmin Connect
```

The Android widget never talks to Garmin directly. It only calls the private backend over HTTPS with a bearer token. The backend alone holds Garmin session material and talks to Garmin Connect through an unofficial client.

## Current implementation state

Backend foundation is in progress.

Implemented now:

- FastAPI app skeleton and route wiring
- `GET /health` endpoint returning a typed response model
- typed environment-based settings
- structured logging with secret redaction
- safe centralized exception handling
- local tests for health, config, logging, and sanitized errors
- `uv` project configuration and lockfile
- Dockerfile, `.dockerignore`, and backend CI workflow

Not implemented yet:

- Garmin authentication and session handling
- Garmin client
- metric normalization logic
- cache/persistence
- API authentication for widget endpoints
- widget endpoints beyond `/health`
- Render deployment completion

Delivery phases and acceptance criteria live in [`docs/implementation-plan.md`](docs/implementation-plan.md).

## Goals

- Show a small set of daily Garmin metrics on an Android home-screen widget.
- Keep the surface area minimal and private for a single personal deployment.
- Refresh Garmin only on explicit user action from the widget.
- Prefer last-known-good cached data when Garmin is unavailable or cooldown applies.
- Keep the REST API stable, normalized, and independent from raw Garmin response structures.
- Never expose Garmin credentials, session tokens, stack traces, or sensitive upstream responses.

## Non-goals (version one)

- Public multi-user SaaS
- Full Garmin Connect feature parity
- Automatic frequent background polling of Garmin
- Exposing raw Garmin API responses to the client
- Long-term health history storage
- Multi-worker distributed refresh coordination

## Backend responsibilities

| Responsibility | Role |
|----------------|------|
| **Configuration** | Load env-based settings (bearer token, data directory, cooldown, IANA timezone for calendar-date selection, Garmin bootstrap credentials, logging level). No secrets in code. |
| **API authentication** | Validate `Authorization: Bearer <private widget token>` on widget endpoints. Distinct from Garmin auth. |
| **REST API** | HTTP surface only: accept requests, return normalized widget payloads / health status. Must not depend on raw Garmin response structures. |
| **Garmin authentication / session management** | Bootstrap and renew reusable Garmin session tokens; persist them under the data directory. |
| **Garmin client** | Call unofficial Garmin APIs and return normalized internal models (not raw upstream JSON). |
| **Metric normalization** | Map Garmin client models into the public widget payload schema. |
| **Cache / persistence** | Store latest successful normalized payload and minimal refresh metadata; serve cache for `/latest`, cooldown hits, and failure fallback. |
| **Error handling** | Map failures to safe client messages; never leak credentials, tokens, stack traces, or sensitive upstream bodies. |

## Persistent-data boundaries

Version one persistent storage may contain only:

- Garmin session tokens
- latest normalized widget cache
- minimal refresh metadata

Version one does **not** store long-term health history.

## Planned API

| Method | Path | Auth | Contacts Garmin | Purpose |
|--------|------|------|-----------------|---------|
| `GET` | `/health` | Optional | No | Service status / liveness |
| `GET` | `/api/v1/widget/latest` | Required | No | Return the last successful cached widget payload |
| `POST` | `/api/v1/widget/refresh` | Required | Maybe | Refresh on explicit user action |
| `GET` | `/api/v1/widget/history` | Future | No | Out of scope for version one |

## Authentication requirements

- `GET /health` may be unauthenticated.
- All `/api/v1/widget/*` endpoints must require `Authorization: Bearer <private widget token>`.
- Widget authentication is separate from Garmin authentication.

## Refresh and cooldown rules

- `GET /health` must not contact Garmin and must not read persistent storage.
- `GET /api/v1/widget/latest` returns the last successful cached payload and must not contact Garmin.
- `POST /api/v1/widget/refresh` is triggered by a user refresh action.
- If the last successful Garmin refresh is younger than the configured cooldown, return cached data instead of contacting Garmin.
- Cooldown is based on the **last successful refresh**, not the last attempt.

## Concurrency and deduplication

- Concurrent eligible refresh requests must be deduplicated or locked so one backend process triggers at most one live Garmin request.
- Failed refreshes must never overwrite the last-known-good payload.
- The initial deployment assumes a single backend process; cross-process locking can be added later if deployment changes.

## Failure fallback behavior

If Garmin refresh fails and a successful cache exists, return the last successful payload with:

- `stale: true`
- an appropriate refresh status such as `UPSTREAM_UNAVAILABLE`
- a safe client error message

If no successful cache exists, return a controlled no-data or failure response without leaking upstream details.

## Widget payload schema

Version-one public JSON uses camelCase and explicit nullability for metrics that Garmin may omit.

| Field | Type | Notes |
|-------|------|-------|
| `schemaVersion` | number | Fixed at `1` for version one |
| `date` | string \| null | `YYYY-MM-DD` metric date |
| `sleepScore` | number \| null | Sleep score |
| `sleepDurationSeconds` | number \| null | Sleep duration |
| `overnightHrv` | number \| null | Overnight HRV |
| `hrvStatus` | string \| null | Example: `BALANCED` |
| `bodyBattery` | number \| null | Body Battery value |
| `restingHeartRate` | number \| null | Resting HR |
| `stress` | number \| null | Stress value |
| `trainingReadiness` | number \| null | Training readiness |
| `garminSyncAt` | string \| null | ISO-8601 UTC timestamp |
| `refreshedAt` | string \| null | ISO-8601 UTC timestamp |
| `stale` | boolean | Indicates stale cached data |
| `refreshStatus` | string | One of `SUCCESS`, `CACHE_HIT`, `COOLDOWN`, `UPSTREAM_UNAVAILABLE`, `NO_DATA` |
| `source` | string | Fixed at `garmin-connect-unofficial` |

See [`shared/widget-response.example.json`](shared/widget-response.example.json) for the example payload.

Local backend helpers for Phase 3 metric work:

- `uv run python -m app.garmin.auth_check` — verify reusable Garmin session
- `uv run python -m app.garmin.metrics_check [--date YYYY-MM-DD]` — fetch and print normalized widget-shaped JSON (defaults to today's date in `GARMIN_WIDGET_TIMEZONE`)

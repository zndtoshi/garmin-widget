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

Backend Phases 1–5 are implemented for local/private use. Android and Render deployment are not complete.

Implemented now:

- FastAPI app skeleton and route wiring
- `GET /health` (unauthenticated; no Garmin/persistence access)
- Authenticated widget API:
  - `GET /api/v1/widget/latest`
  - `POST /api/v1/widget/refresh`
- Bearer-token authentication with timing-safe comparison
- Typed environment-based settings (including timezone and cooldown)
- Structured logging with secret redaction
- Safe centralized exception handling
- Garmin authentication/session lifecycle and filesystem token store
- Metric adapter, normalization, and public `WidgetResponse` model
- Atomic latest-widget snapshot persistence and refresh coordination
- Local CLI auth/metrics checks
- Local tests, `uv` lockfile, Dockerfile, backend CI

Not implemented yet:

- Android/Glance widget
- Render deployment and custom domain
- Multi-process/distributed refresh locking
- `/api/v1/widget/history` (future only)

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

## Version-one API

| Method | Path | Auth | Contacts Garmin | Purpose |
|--------|------|------|-----------------|---------|
| `GET` | `/health` | No | No | Service status / liveness |
| `GET` | `/api/v1/widget/latest` | Required | No | Return the last successful cached widget payload |
| `POST` | `/api/v1/widget/refresh` | Required | Maybe | Refresh on explicit user action |
| `GET` | `/api/v1/widget/history` | Future | No | Out of scope for version one |

### HTTP status behavior

| Situation | Status | Notes |
|-----------|--------|-------|
| Valid bearer auth + successful payload | `200` | Body is `WidgetResponse` |
| Missing/malformed/incorrect bearer token | `401` | Generic `Unauthorized`; `WWW-Authenticate: Bearer` |
| No successful snapshot for `/latest` | `404` | Generic no-data detail |
| Server widget token unset/empty (non-production misconfig) | `503` | Generic configuration detail |
| Corrupt/unreadable snapshot | `503` | Generic unavailable detail |
| Live refresh failed with no fallback | `503` | Generic unavailable detail |
| Upstream failure with valid snapshot | `200` | `refreshStatus=UPSTREAM_UNAVAILABLE`, `stale=true` |

## Authentication requirements

- `GET /health` may be unauthenticated.
- All `/api/v1/widget/*` endpoints must require `Authorization: Bearer <private widget token>`.
- Widget authentication is separate from Garmin authentication.
- Generate the private widget token with at least 32 random bytes, for example `python -c "import secrets; print(secrets.token_urlsafe(32))"`.
- Store that value only in ignored local `.env`, Render secrets later, and Android secure storage later—never in source control, chat, screenshots, logs, or example files.
- Production rejects missing, empty, known-placeholder, and short (`< 32` character) widget bearer tokens without echoing the token value.

## Refresh and cooldown rules

- `GET /health` must not contact Garmin and must not read persistent storage.
- `GET /api/v1/widget/latest` returns the last successful cached payload and must not contact Garmin.
- `POST /api/v1/widget/refresh` is triggered by a user refresh action.
- If the last successful Garmin refresh is younger than the configured cooldown, return cached data instead of contacting Garmin.
- Cooldown is based on the **last successful refresh**, not the last attempt.

## Concurrency and deduplication

- Concurrent eligible refresh requests must be deduplicated or locked so one backend process triggers at most one live Garmin request.
- Failed refreshes must never overwrite the last-known-good payload.
- The initial deployment assumes a single backend process/worker; refresh locking is process-scoped per data directory and does not coordinate multiple OS processes or Render instances. Multiple workers require a cross-process/distributed lock before scaling.

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

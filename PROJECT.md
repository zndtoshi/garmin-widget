# garmin-widget - Project Specification

Private personal-use project. This document defines architecture, API, payload schema, backend responsibilities, and milestones.

Related docs: [README.md](README.md), [docs/architecture.md](docs/architecture.md), [docs/implementation-plan.md](docs/implementation-plan.md), [docs/render-frankfurt-migration.md](docs/render-frankfurt-migration.md), [shared/widget-response.example.json](shared/widget-response.example.json).

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

Backend Phases 1–6 are implemented and deployed for private use. The Render service, persistent disk, custom domain, DNS, TLS, Garmin refresh, and unauthenticated-route protection have been verified. Phase 7 Android is complete (buildable app + Glance widget). Phase 8A (expanded backend data contract) is merged, deployed, and live-verified. Phase 8B (premium responsive widget UI with Compact/Wide/Large picker presets, exact-size adaptive layout, and optional activity HR chart) is in progress; device polish and visual verification remain pending. The additive `lastActivity.heartRateTimeline` backend extension is implemented locally and is not claimed deployed until live-verified.

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
- **Phase 8A expanded data contract** — additive nullable fields for premium widgets:
  - `sleepStages` (deep/light/REM/awake durations, negative values filtered)
  - `hrvTrend` (rolling 7-day, oldest-first, bounded to 7 entries max; initial backfill then same-day reuse)
  - `bodyBatteryTimeline` and `stressTimeline` (intraday points, sorted/deduped, values 0–100, max 48 after downsampling)
  - `lastActivity` (name, type, duration, distance, HR, etc.; `startTimeGMT` is the trusted UTC source, including naive GMT strings, while local-only timestamps are never mislabeled). Optional private-safe `heartRateTimeline` (max 48) may be present after a transient activity-details fetch; activity IDs/GPS/raw details are never exposed.
  - All new fields are nullable and backward-compatible with `schemaVersion=1`
- Atomic latest-widget snapshot persistence and refresh coordination
- Render-oriented container entrypoint (`PORT`, one worker; proxy headers disabled)
- Root `render.yaml` Blueprint and [`docs/render-deployment.md`](docs/render-deployment.md) runbook
- Local CLI auth/metrics checks
- Local tests, `uv` lockfile, Dockerfile, backend CI (including Docker build validation)
- Live Render deployment at `https://garmin.zndtoshi.com`
- Kotlin/Jetpack Glance Android app with encrypted bearer-token storage
- Local widget payload cache, manual WorkManager refresh, and Garmin Connect launch action
- Glance-managed observable state for deterministic UI updates
- Android unit tests (including expanded-response backward-compat), lint validation, Gradle wrapper, and debug APK build

Not implemented yet:

- Device/emulator verification of the Android app and Glance widget
- Android release signing and final private distribution
- Phase 8B: Device visual verification and polish for premium responsive UI
- Phase 8C: Approved 30-minute periodic background refresh (not screen-on-triggered)
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

## HRV trend and call budget

The `hrvTrend` field is a bounded rolling 7-day window. On initial refresh (no cached snapshot or no existing trend), the adapter backfills up to 6 historical HRV calls plus the current day's call, for a total of up to **15 Garmin endpoint calls** (9 standard + 6 historical HRV) when no activity-details fetch is needed. On subsequent same-day refreshes where a cached trend exists, only the current day's HRV is re-fetched, keeping the total to **9 Garmin endpoint calls**.

When the latest activity has a usable Garmin activity ID, the adapter may add one transient `get_activity_details(..., maxpoly=0)` call to build `lastActivity.heartRateTimeline`. Conditional budgets:

- initial HRV backfill + activity details: maximum **16** calls
- ordinary cached-trend refresh + activity details: maximum **10** calls
- without a usable activity ID, the existing **15** / **9** budgets remain

Activity IDs, GPS/route polylines, and raw detail descriptors are never written to the public payload, snapshots, or logs.
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
| `sleepStages` | object \| null | `{deepSeconds, lightSeconds, remSeconds, awakeSeconds}` (all nullable ints) |
| `hrvTrend` | array \| null | Rolling 7-day trend, oldest first. Each: `{date, overnightAverage, sevenDayAverage, status}` |
| `bodyBatteryTimeline` | array \| null | Intraday points (max 48): `{timestamp, value}` |
| `stress` | number \| null | Stress value |
| `stressTimeline` | array \| null | Intraday points (max 48): `{timestamp, value}` |
| `trainingReadiness` | number \| null | Training readiness |
| `lastActivity` | object \| null | Most recent activity summary. Optional additive `heartRateTimeline` (max 48): `{elapsedSeconds, heartRate}` from a transient details fetch; never includes activity ID/GPS/raw details. |
| `garminSyncAt` | string \| null | ISO-8601 UTC timestamp |
| `refreshedAt` | string \| null | ISO-8601 UTC timestamp |
| `stale` | boolean | Indicates stale cached data |
| `refreshStatus` | string | One of `SUCCESS`, `CACHE_HIT`, `COOLDOWN`, `UPSTREAM_UNAVAILABLE`, `NO_DATA` |
| `source` | string | Fixed at `garmin-connect-unofficial` |

See [`shared/widget-response.example.json`](shared/widget-response.example.json) for the example payload.

Local backend helpers for Phase 3 metric work:

- `uv run python -m app.garmin.auth_check` — verify reusable Garmin session
- `uv run python -m app.garmin.metrics_check [--date YYYY-MM-DD]` — fetch and print normalized widget-shaped JSON (defaults to today's date in `GARMIN_WIDGET_TIMEZONE`)

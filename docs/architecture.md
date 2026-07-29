# Architecture

Private personal-use garmin-widget system. Concise diagrams and component boundaries. Spec details live in [PROJECT.md](../PROJECT.md).

**Status:** Backend Phases 1–6 deployed and verified. Phase 7 Android app and widget complete. Phase 8A expanded data contract implemented locally (not yet deployed to Render).

## System architecture

```mermaid
flowchart TD
  widget[Android home-screen widget]
  api[Private FastAPI service on Render]
  garmin[Garmin Connect]
  store[Persistent storage on Render]

  widget -->|"HTTPS bearer authentication"| api
  api -->|"reusable unofficial Garmin session"| garmin
  api --> store

  store --- tokens[Garmin session tokens]
  store --- cache[Latest normalized widget cache]
  store --- meta[Minimal refresh metadata]
```

Version one does **not** store long-term health history.

## Backend component boundaries

```mermaid
flowchart LR
  subgraph http [REST API]
    routes[Routes]
    apiAuth[API authentication]
  end

  subgraph domain [Domain]
    norm[Metric normalization]
    cache[Cache / persistence]
    errors[Error handling]
  end

  subgraph garminLayer [Garmin]
    session[Garmin auth / session management]
    client[Garmin client]
  end

  cfg[Configuration]

  cfg --> routes
  cfg --> session
  cfg --> cache
  routes --> apiAuth
  routes --> cache
  routes --> norm
  routes --> errors
  norm --> client
  client --> session
```

Rules:

- The **REST API** returns only **normalized** widget payloads (and health status). It must not depend on raw Garmin response structures.
- The **Garmin client** returns normalized internal models; **metric normalization** maps those into the public widget schema.
- **API authentication** (widget bearer token) is separate from **Garmin authentication / session management**.
- **Configuration**, **cache/persistence**, and **error handling** are distinct responsibilities.

## Successful refresh flow

```mermaid
sequenceDiagram
  actor User
  participant Widget as Android widget
  participant API as FastAPI
  participant Lock as Refresh lock / dedupe
  participant Client as Garmin client
  participant GC as Garmin Connect
  participant Cache as Persistent cache

  User->>Widget: Tap refresh
  Widget->>API: POST /api/v1/widget/refresh (Bearer)
  API->>API: Validate widget bearer token
  API->>Lock: Acquire / join in-flight refresh
  API->>Cache: Read last successful refresh time
  Note over API: Cooldown elapsed
  API->>Client: Fetch metrics
  Client->>GC: Unofficial session request
  GC-->>Client: Upstream data
  Client-->>API: Normalized internal models
  API->>API: Map to widget payload
  API->>Cache: Persist normalized payload + metadata
  API-->>Widget: Payload stale=false refreshStatus=SUCCESS
```

## Cooldown / cache-hit flow

```mermaid
sequenceDiagram
  actor User
  participant Widget as Android widget
  participant API as FastAPI
  participant Cache as Persistent cache

  User->>Widget: Tap refresh
  Widget->>API: POST /api/v1/widget/refresh (Bearer)
  API->>API: Validate widget bearer token
  API->>Cache: Last successful refresh younger than cooldown
  Note over API: Do not contact Garmin
  API-->>Widget: Cached payload (appropriate refreshStatus)
```

`GET /api/v1/widget/latest` always returns the last successful cache and **never** contacts Garmin. `GET /health` returns service status and **never** contacts Garmin. Widget routes require `Authorization: Bearer <private widget token>`; `/health` does not.

Refresh coordination currently uses a **process-scoped** lock keyed by data directory. Separately constructed services in one process that share `DATA_DIR` still deduplicate refreshes. This does **not** coordinate multiple OS processes or Render instances. Deploy the first Render instance with a single worker and a persistent disk (see [render-deployment.md](render-deployment.md)). Configuration is prepared via root `render.yaml`; live Render/domain setup is not completed by the repository alone.

## Garmin failure fallback flow

```mermaid
sequenceDiagram
  actor User
  participant Widget as Android widget
  participant API as FastAPI
  participant Client as Garmin client
  participant GC as Garmin Connect
  participant Cache as Persistent cache

  User->>Widget: Tap refresh
  Widget->>API: POST /api/v1/widget/refresh (Bearer)
  API->>Client: Fetch metrics (cooldown elapsed)
  Client->>GC: Unofficial session request
  GC-->>Client: Failure / unavailable
  Client-->>API: Error
  API->>Cache: Load last successful payload
  API-->>Widget: Cached payload stale=true + refreshStatus + safe error message
```

Never return Garmin credentials, session tokens, stack traces, or sensitive upstream responses.

## Authentication boundaries

```mermaid
flowchart TB
  subgraph clientSide [Android]
    widget[Widget]
    localToken[Private widget bearer token stored on device]
  end

  subgraph serverSide [Render FastAPI]
    apiAuth[API auth: Bearer widget token]
    health["GET /health may be unauthenticated"]
    widgetRoutes["/api/v1/widget/* require auth"]
    gSession[Garmin session tokens on persistent disk]
    gClient[Unofficial Garmin client]
  end

  subgraph upstream [Garmin]
    gc[Garmin Connect account]
  end

  localToken --> widget
  widget -->|"Authorization: Bearer"| apiAuth
  apiAuth --> widgetRoutes
  health -.-> apiAuth
  gSession --> gClient
  gClient --> gc
```

- Widget bearer token ≠ Garmin credentials or Garmin session tokens.
- Clients never receive Garmin session material.

## Deployment layout

```mermaid
flowchart LR
  phone[Android device]
  render[Render private web service]
  disk[Render persistent disk]
  garmin[Garmin Connect]

  phone -->|"HTTPS + Bearer"| render
  render --> disk
  render -->|"unofficial session"| garmin

  subgraph diskContents [Disk contents v1 only]
    t[Session tokens]
    c[Latest normalized cache]
    m[Minimal refresh metadata]
  end

  disk --- diskContents
```

## Public endpoints (documented)

| Method | Path | Version one |
|--------|------|-------------|
| `GET` | `/health` | Yes — unauthenticated; no Garmin contact |
| `GET` | `/api/v1/widget/latest` | Yes — bearer auth; cache only |
| `POST` | `/api/v1/widget/refresh` | Yes — bearer auth; cooldown, lock, Garmin, fallback |
| `GET` | `/api/v1/widget/history` | **No** — future only; out of scope for v1 |

## Expanded normalized snapshot (Phase 8A)

The widget snapshot now includes additive nullable premium fields alongside the original metrics. All new fields use `schemaVersion=1` and are backward-compatible:

- **`sleepStages`**: deep/light/REM/awake durations (negative values rejected at extraction).
- **`hrvTrend`**: bounded rolling 7-day window (max 7 entries, oldest-first). Initial backfill fetches up to 6 historical HRV calls; same-day refreshes reuse the cached trend and fetch only the current day.
- **`bodyBatteryTimeline`** / **`stressTimeline`**: intraday sorted/deduped points with values clamped to 0–100 and downsampled to max 48 entries. Duplicate timestamps prefer the last valid sample.
- **`lastActivity`**: most recent activity. `startTimeGMT` is the trusted UTC source and may be parsed from either a naive GMT string or an offset-bearing timestamp. `startTimeLocal` is ignored when GMT is absent, so local-only timestamps are never mislabeled with `Z`. Activities with no recognized public fields return `null`. Optional `heartRateTimeline` (max 48 chronological `{elapsedSeconds, heartRate}` points) is derived from a transient `get_activity_details(..., maxpoly=0)` fetch when a usable activity ID exists; activity IDs, GPS/route polylines, and raw detail channels are never serialized. Without a usable ID, summary activity still succeeds and the timeline is omitted. Call budgets become 16/10 with details, otherwise 15/9.

The snapshot persists the complete expanded payload including trend/timelines/activity, surviving repository reload and service restarts. The activity HR timeline extension is implemented locally for Phase 8B and is **not claimed deployed** until merged and live-verified.

## Operational risk

Unofficial Garmin Connect access may break without notice if Garmin changes private APIs. Treat upstream breakage as expected operational risk for a personal project.

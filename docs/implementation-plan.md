# Implementation Plan

This plan turns the project specification into an ordered delivery path for the private Garmin Android widget. It keeps Garmin credentials on the backend, limits Garmin requests to explicit refreshes, and delivers usable increments that can be tested independently.

## Guiding principles

- The Android app communicates only with the private backend, never directly with Garmin.
- Widget endpoints require a private bearer token; Garmin credentials and session tokens never leave the backend.
- Public API models are stable, normalized, camelCase models and do not expose raw Garmin response shapes.
- A refresh is user initiated. Cooldown and request deduplication protect Garmin from repeated requests.
- The latest successful payload remains available when Garmin is unavailable.
- Persistent storage contains only session material, the latest normalized payload, and minimal refresh metadata. Version one stores no health history.
- Each phase must pass its tests and security checks before work proceeds to the next phase.

## Phase 1: Harden the backend foundation

### Work

1. Add a typed configuration module using environment variables for:
   - widget bearer token;
   - data directory;
   - refresh cooldown;
   - Garmin username and password for session bootstrapping;
   - application version and logging level.
2. Validate configuration at startup and fail with a clear server-side message when required production settings are absent.
3. Add consistent structured logging with secret redaction.
4. Add safe exception handlers that return generic client errors without stack traces or upstream response bodies.
5. Keep `/health` independent of Garmin and persistent storage.
6. Make the Docker build reproducible by pinning the `uv` image and ensuring production startup excludes development dependencies.
7. Add continuous integration for linting and tests on supported Python versions.

### Deliverables

- Configuration and logging modules.
- Central error-handling policy.
- Reproducible production container.
- CI workflow for backend checks.
- Updated backend setup documentation and `.env.example` containing placeholders only.

### Acceptance criteria

- `GET /health` returns the documented response without contacting Garmin or reading the data directory.
- Missing or invalid configuration is detected predictably.
- Logs and API errors contain no configured secrets.
- Tests and lint checks pass locally and in CI.
- The container starts as a non-root user and becomes healthy.

## Phase 2: Implement Garmin session management locally

This phase proves Garmin access locally. It must not expose a Garmin-backed public endpoint.

### Work

1. Add the unofficial Garmin client library as a locked dependency.
2. Define a session-store interface and a filesystem implementation rooted under `DATA_DIR`.
3. Implement session loading, initial login, token persistence, and session renewal.
4. Ensure session files are written atomically and with the narrowest practical permissions.
5. Add a local command that authenticates and performs a small, read-only Garmin request.
6. Classify authentication, rate-limit, timeout, malformed-response, and general upstream failures.
7. Redact credentials, tokens, cookies, and upstream payloads from logs and errors.

### Deliverables

- Garmin session manager.
- Filesystem session store.
- Local authentication/diagnostic command.
- Unit tests using fakes or fixtures; tests must not call real Garmin services.
- Manual verification notes for the private Garmin account.

### Acceptance criteria

- A successful local login creates reusable session material under `DATA_DIR`.
- A subsequent run reuses the session without requiring credentials when the session remains valid.
- Expired sessions renew or fail with a classified, safe error.
- No credentials or session material appear in Git, logs, test fixtures, or command output.

## Phase 3: Fetch and normalize Garmin metrics

### Work

1. Define internal typed models for the required metrics:
   - sleep score and duration;
   - overnight HRV and HRV status;
   - Body Battery;
   - resting heart rate;
   - stress;
   - training readiness;
   - Garmin synchronization time when available.
2. Implement a Garmin adapter that converts library responses into those internal models.
3. Keep all raw Garmin field names and response traversal inside the adapter.
4. Implement public widget models with camelCase serialization, explicit nullability, schema version `1`, and a constrained refresh-status enum.
5. Normalize dates and timestamps consistently; emit timestamps as ISO-8601 UTC.
6. Define behavior for partial or missing Garmin data without failing the entire payload.
7. Add fixture-based tests for complete, partial, missing, and changed upstream shapes.

### Deliverables

- Internal metric models.
- Garmin adapter and normalization service.
- Version-one widget response model.
- Sanitized Garmin fixtures and normalization tests.
- Updated example payload and schema documentation.

### Acceptance criteria

- REST-facing code does not inspect raw Garmin responses.
- Missing optional metrics serialize as `null`.
- Output matches `shared/widget-response.example.json` and its documented schema.
- Upstream shape changes fail inside the adapter with safe, diagnosable server-side errors.

## Phase 4: Add persistent cache and refresh coordination

### Work

1. Define cache and refresh-metadata interfaces.
2. Implement filesystem persistence under `DATA_DIR` using atomic replace operations.
3. Store only the latest successful normalized payload and minimal refresh metadata.
4. Implement a refresh service that:
   - returns cached data during cooldown without contacting Garmin;
   - performs a live refresh after cooldown;
   - updates the cache only after successful normalization;
   - returns stale cached data after an upstream failure;
   - reports a safe error when no cache exists;
   - deduplicates concurrent refreshes within one backend process.
5. Define recovery behavior for missing, corrupt, or partially written cache files.
6. Document the single-process assumption. If deployment later uses multiple workers, replace the in-process lock with a cross-process lock.

### Suggested refresh statuses

- `SUCCESS`: live Garmin refresh succeeded.
- `CACHE_HIT`: latest payload returned without a live request.
- `COOLDOWN`: refresh skipped because the successful cache is still inside the cooldown window.
- `UPSTREAM_UNAVAILABLE`: Garmin failed and stale cache was returned.
- `NO_DATA`: no successful cache exists.

### Deliverables

- Cache repository and refresh-metadata repository.
- Refresh orchestration service and lock/deduplication mechanism.
- Unit and concurrency tests using a fake clock and fake Garmin adapter.

### Acceptance criteria

- Cooldown is calculated from the last successful refresh, not the last attempt.
- Concurrent eligible requests cause at most one Garmin call.
- Failed refreshes never overwrite the last-known-good payload.
- Corrupt cache data produces a controlled error and no secret leakage.
- Persistence survives a backend restart.

## Phase 5: Expose the authenticated widget API

### Work

1. Implement bearer-token validation for all `/api/v1/widget/*` routes.
2. Compare bearer tokens using a timing-safe comparison.
3. Implement:
   - `GET /api/v1/widget/latest`, which reads cache only;
   - `POST /api/v1/widget/refresh`, which invokes the refresh service.
4. Decide and document HTTP behavior for an empty cache, invalid authentication, and total refresh failure.
5. Add request timeouts and safe response mappings.
6. Update OpenAPI descriptions without exposing example secrets.
7. Add API contract tests, including proof that `/latest` cannot contact Garmin.

### Deliverables

- Authentication dependency.
- Versioned widget router.
- Latest and refresh endpoints.
- API integration and security tests.

### Acceptance criteria

- Missing, malformed, or incorrect credentials receive `401` without disclosing why a token differed.
- `/health` remains available according to the deployment policy.
- `/latest` never invokes Garmin.
- `/refresh` follows cooldown, locking, cache update, and fallback rules.
- No endpoint returns credentials, tokens, stack traces, or raw Garmin data.

## Phase 6: Deploy and validate the backend

### Progress note

**Phase 6 is verified and complete.** The Render service is deployed at `https://garmin.zndtoshi.com` with persistent disk, custom domain, DNS, TLS, Garmin session bootstrap, and private end-to-end verification all confirmed.

### Work

1. Create the Render service with HTTPS and one backend worker initially.
2. Attach persistent storage and point `DATA_DIR` to it.
3. Configure secrets through Render environment settings, never repository files.
4. Bootstrap the Garmin session securely on persistent storage.
5. Configure health checks, restart behavior, and basic operational alerts.
6. Perform a private end-to-end API test for health, authentication, first refresh, cooldown, and stale fallback.
7. Document backup/recovery expectations for session and cache files.

### Deliverables

- Running private backend URL.
- Persistent storage and environment configuration.
- Deployment and recovery runbook.
- End-to-end verification record with sensitive values removed.

### Acceptance criteria

- The service restarts without losing the Garmin session or latest cache.
- HTTPS is enforced for widget traffic.
- An unauthorized request cannot access widget data.
- A simulated Garmin failure returns the last-known-good payload marked stale.

## Phase 7: Build the Android app and widget

### Progress note

**Phase 7 is verified and complete.** Implemented: Kotlin/Compose configuration UI, Android Keystore-encrypted bearer token, typed widget payload parsing, app-private payload cache, responsive Glance widget with `PreferencesGlanceStateDefinition` observable state, deduplicated manual WorkManager refresh (no network constraint), Garmin Connect launch with fallback, unit tests, lint, and debug APK build. Device/emulator interaction verification, release signing, and final private distribution remain pending for Phase 8.

### Work

1. Create the Kotlin Android application and choose minimum/target SDK versions supported by Jetpack Glance.
2. Add a typed network client for the version-one backend API.
3. Store the backend URL and private widget token using Android-appropriate secure storage; exclude them from source control and backups where appropriate.
4. Implement a local widget-data repository so rendering never depends on a live request.
5. Build the initial fixed-layout Glance widget showing the primary metrics and last-refresh/stale state.
6. Add a refresh action that starts supported background work, calls `POST /refresh`, updates local data, and refreshes the widget.
7. Make tapping the widget body open Garmin Connect, with a safe fallback when it is not installed.
8. Display loading, authentication failure, no-data, stale-data, and general failure states clearly.
9. Add unit tests for mapping and state transitions plus device/emulator tests for widget interaction.

### Deliverables

- Installable private Android application.
- Initial Jetpack Glance widget.
- Secure configuration and local payload cache.
- Manual refresh and Garmin Connect launch behavior.
- Android test suite and setup documentation.

### Acceptance criteria

- The widget displays cached data immediately after process or device restart.
- Refresh updates the widget and cannot generate repeated accidental requests.
- Network failure preserves the last-known-good display and marks it stale.
- Secrets are absent from logs, screenshots, resources, build artifacts, and version control.
- The widget behaves correctly across supported Android versions and common launcher conditions.

## Phase 8: Polish and reliability

### Phase 8A: Expanded backend data contract (deployed and live-verified)

Implemented additive nullable fields for premium widgets: `sleepStages`, `hrvTrend` (bounded 7-day rolling with backfill), `bodyBatteryTimeline`, `stressTimeline` (sorted/deduped, values 0–100, max 48 after downsampling), and `lastActivity` (`startTimeGMT` is the trusted UTC source, including naive GMT strings; null-only objects are filtered). All fields maintain `schemaVersion=1` backward compatibility. Android backward-compat JVM tests confirm original fields still parse from expanded payloads.

### Phase 8B/8D: Android premium widget UI (in progress)

Phase 8B/8D consolidates to **one** widget-picker entry with a fixed two-row adaptive layout (`SizeMode.Exact`): equal Sleep / HRV / Body Battery panels on top and a full-width activity card with heart-rate chart below. Sleep stage legends and intervening full-width health charts/metrics rows are removed. The HR timeline backend extension is deployed and live-verified with 48 real cached points; repaired Android layout verification across launchers/resizing remains pending.

### Phase 8C: Approved 30-minute periodic background refresh (pending)

Implement a periodic background refresh using WorkManager with a 30-minute minimum interval. This is the approved scope — not screen-on-triggered refresh, which is unreliable and was explicitly rejected.

### Work

1. Add responsive layouts for supported widget sizes.
2. Add metric-selection configuration while keeping sensible defaults.
3. Improve accessibility, contrast, typography, content descriptions, and touch targets.
4. Add retry guidance and clearer stale/cooldown indicators without automatic frequent polling.
5. Test session expiry, Garmin outages, backend restarts, corrupt cache files, time-zone boundaries, daylight-saving changes, and concurrent refresh taps.
6. Review dependencies, permissions, log output, and stored data before release.
7. Document the upgrade process for backend schema changes and Android compatibility.

### Acceptance criteria

- Layouts remain readable at every supported size and font scale.
- Date selection and timestamps behave correctly in the configured user timezone and UTC storage model.
- Operational failures degrade to understandable cached or no-data states.
- The final security review finds no committed or client-exposed Garmin secrets.

## Testing strategy

Use a test pyramid that avoids real Garmin calls in automated tests:

- **Unit tests:** configuration, token validation, normalization, cooldown calculations, cache behavior, error classification, and Android state mapping.
- **Contract tests:** public JSON field names, nullability, enum values, schema version, status codes, and authentication behavior.
- **Integration tests:** FastAPI routes with fake Garmin/session implementations and temporary filesystem storage.
- **Concurrency tests:** simultaneous refreshes, lock release after failure, and single-upstream-call guarantees.
- **Container tests:** image startup, non-root execution, health check, restart, and persistent-volume behavior.
- **Android tests:** local-cache rendering, refresh action, stale/error states, resizing, and process/device restart.
- **Manual private checks:** real Garmin login and metric validation, Render deployment, and end-to-end widget refresh.

## Security checklist

- [ ] No Garmin credentials, sessions, bearer tokens, `.env` files, caches, or persistent data are tracked by Git.
- [ ] Secrets are supplied only through local secure configuration or deployment environment settings.
- [ ] Widget tokens use timing-safe validation and are never logged.
- [ ] All client-facing errors are sanitized.
- [ ] Raw Garmin responses remain inside the Garmin adapter and are not persisted by default.
- [ ] Session and cache writes are atomic and use restricted permissions where supported.
- [ ] Backend traffic from the widget uses HTTPS.
- [ ] Android release logs do not contain request headers, tokens, or health data beyond what the widget intentionally displays.

## Recommended delivery order

Complete phases sequentially because each phase establishes an interface needed by the next:

```text
Backend foundation
  -> Garmin session management
  -> Metric fetching and normalization
  -> Cache and refresh coordination
  -> Authenticated API
  -> Backend deployment
  -> Android widget
  -> Polish and reliability
```

The first end-to-end usable release is complete when Phase 7 passes its acceptance criteria. Phase 8 prepares the project for dependable ongoing personal use.

## Deferred beyond version one

- Long-term health history and `/api/v1/widget/history`.
- Multiple users or Garmin accounts.
- Public account registration or OAuth for widget users.
- Frequent automatic Garmin polling.
- Multi-worker distributed refresh coordination unless deployment requirements make it necessary.
- A general-purpose Garmin API proxy.

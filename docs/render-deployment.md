# Render deployment runbook (garmin-widget backend)

This runbook prepares and operates the FastAPI backend on Render. The service is an **internet-reachable Render web service**; privacy comes from the application-layer private bearer token on `/api/v1/widget/*`, not from Render making the web service network-private. Repository configuration is ready; a live Render service, custom domain, and DNS records are **not** completed by this documentation alone.

Related: [PROJECT.md](../PROJECT.md), [architecture](architecture.md), [backend README](../backend/README.md), root [`render.yaml`](../render.yaml).

## Prerequisites

- Private GitHub repository access for this project
- Render account with permission to create a Docker web service
- A paid/disk-capable Render service tier that supports **persistent disks** (required for Garmin session + widget snapshot retention). Do not hardcode a specific plan name here; choose a disk-capable option in the Dashboard.
- Later: DNS-provider access for a custom domain (optional for first deploy; use the default Render hostname initially)

## Important constraints

- Refresh locking is **process-local**. Run **exactly one** instance/worker.
- A persistent disk also forces a **single instance** on Render and disables zero-downtime deploys.
- Never commit bearer tokens, Garmin credentials, session files, snapshot files, domains, or personal health data.
- Do **not** delete or recreate the persistent disk casually—session material and the latest widget snapshot live there.

## Blueprint overview

Root [`render.yaml`](../render.yaml) defines:

| Item | Value |
|------|--------|
| Service type | Docker web service |
| Dockerfile | `./backend/Dockerfile` |
| Build context | `./backend` |
| Health check | `GET /health` |
| Instances | `1` |
| Disk mount | `/var/data` |
| `GARMIN_WIDGET_DATA_DIR` | `/var/data` |
| App env | `production` |
| Timezone | `Europe/Bucharest` |
| Cooldown | `60` seconds |
| Secrets (manual / `sync: false`) | widget bearer token, Garmin username, Garmin password |

`sync: false` means Render prompts for those values during **initial Blueprint creation** and then **ignores** them on later Blueprint syncs. Update those secrets later only through the Render Dashboard.

No plan name, region, repository URL, custom domain, or secret **values** are hardcoded.

## Environment variables

| Variable | Secret? | Notes |
|----------|---------|--------|
| `GARMIN_WIDGET_APP_ENV` | No | `production` |
| `GARMIN_WIDGET_DATA_DIR` | No | `/var/data` |
| `GARMIN_WIDGET_TIMEZONE` | No | `Europe/Bucharest` |
| `GARMIN_WIDGET_REFRESH_COOLDOWN_SECONDS` | No | `60` |
| `GARMIN_WIDGET_LOG_LEVEL` | No | `INFO` |
| `GARMIN_WIDGET_SERVICE_NAME` | No | service label |
| `GARMIN_WIDGET_WIDGET_BEARER_TOKEN` | **Yes** | Required in production; generate locally |
| `GARMIN_WIDGET_GARMIN_USERNAME` | **Yes** | Temporary bootstrap only. Enter during initial Blueprint creation if bootstrapping immediately, or add manually in the Dashboard later. Remove after the disk session is verified. |
| `GARMIN_WIDGET_GARMIN_PASSWORD` | **Yes** | Temporary bootstrap only. Same lifecycle as the username. Never leave long-term in Render env after session persistence is confirmed. |
| `PORT` | No | Injected by Render; container honors it |

### Generate the widget bearer token locally

Do not paste the result into chat, screenshots, commits, or example files.

```powershell
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

Store the value only in:

1. Render Dashboard secret for `GARMIN_WIDGET_WIDGET_BEARER_TOKEN`
2. Later: Android secure storage
3. Optional local ignored `.env` for private smoke tests

## Persistent disk

| Concern | Detail |
|---------|--------|
| Mount path | `/var/data` |
| App setting | `GARMIN_WIDGET_DATA_DIR=/var/data` |
| Expected files after bootstrap | `/var/data/garmin/garmin_tokens.json`, `/var/data/widget/latest_snapshot.json` |
| Permissions | Container runs as non-root `appuser` (uid `10001`); ensure the mount is writable by that user |
| Backup | Treat disk contents as sensitive. Prefer Render/disk snapshots or a private encrypted copy of the two JSON files. Never commit them. |
| Warning | Recreating the disk loses the Garmin session and latest widget snapshot |

## Why one worker/instance

1. `WidgetRefreshService` deduplication uses a process-scoped lock.
2. Render persistent disks do not support multi-instance scaling.
3. Multiple workers would risk duplicate Garmin calls and split-brain cooldown metadata.

Do not scale out until a cross-process/distributed lock exists.

## Create / deploy from the Blueprint

1. In Render, create a Blueprint from this repository's root `render.yaml` (or mirror the same settings in the Dashboard).
2. During **initial** Blueprint creation, provide `sync: false` secrets when prompted:
   - generated widget bearer token (required)
   - Garmin username/password only if you will bootstrap a session immediately; otherwise add them later in the Dashboard before bootstrap
3. Confirm disk mount `/var/data` and `numInstances: 1`.
4. Deploy. Wait until `/health` passes.
5. Remember: later Blueprint syncs ignore `sync: false` keys; change secrets only in the Dashboard.

Exact Dashboard clicks change over time; prefer Blueprint + the table above over screenshots.

## First-deployment sequence

1. Deploy with production env + disk + widget bearer token set.
2. Confirm `GET /health` returns `200`.
3. Confirm unauthenticated widget calls return `401`.
4. Bootstrap Garmin session (next section).
5. Perform authenticated refresh and latest checks.
6. Remove Garmin username/password secrets and redeploy while **keeping the disk**.
7. Re-verify refresh/latest still work from the persisted session.
8. (Later) attach custom domain + TLS verification when DNS details are known.

## Safe Garmin session bootstrap

Goal: persist reusable session material on the disk, then remove long-lived Garmin passwords from Render env.

1. Temporarily set Render secrets:
   - `GARMIN_WIDGET_GARMIN_USERNAME`
   - `GARMIN_WIDGET_GARMIN_PASSWORD`
2. Redeploy or restart so the process sees the secrets.
3. Call **one** authenticated refresh (see smoke tests). This initializes the unofficial session and writes `garmin_tokens.json` under the disk.
4. Call `/latest` and confirm payload shape (may still be empty only if refresh failed).
5. **Remove** Garmin username/password from Render env (clear secrets / leave unset).
6. Redeploy. Keep the persistent disk attached.
7. Call refresh again. It should reuse the disk session without Garmin password env vars.
8. If MFA or rate-limit blocks bootstrap: stop, do not paste secrets or raw upstream bodies into tickets/chat. Retry later from a trusted machine or after MFA is satisfied offline. Prefer CLI `auth_check` on a private machine with ignored `.env` only when necessary, then copy **only** the resulting session file onto the disk via a secure operator process—not into git.

## Cold start behavior

Before the first successful refresh:

- `GET /api/v1/widget/latest` → `404` with generic no-data detail
- After first successful refresh → `200` with `CACHE_HIT` / `SUCCESS` as appropriate

## Verification matrix

| Check | Expectation |
|-------|-------------|
| Health | `GET /health` → `200`, no auth |
| Unauthorized | Widget routes without/wrong bearer → `401` + `WWW-Authenticate: Bearer` |
| First refresh | Authenticated `POST /refresh` → `200`, `refreshStatus=SUCCESS`, `stale=false` |
| Cooldown | Immediate second refresh → `COOLDOWN`, `stale=false`, no extra Garmin storm |
| Latest | `GET /latest` → `CACHE_HIT`, `stale=false` |
| Stale fallback | With valid snapshot, force upstream failure → `200`, `UPSTREAM_UNAVAILABLE`, `stale=true` |
| Restart | Redeploy/restart with disk → session + snapshot still present |
| Credential removal | After removing Garmin env secrets, refresh still works via disk session |

## Smoke-test commands (PowerShell)

Set secrets in your **local shell only** (not in docs, scripts committed to git, or screenshots):

```powershell
$env:WIDGET_BASE_URL = "https://YOUR-RENDER-HOST"   # replace at runtime; do not commit
# $env:GARMIN_WIDGET_WIDGET_BEARER_TOKEN already set in your private shell
```

```powershell
Invoke-RestMethod -Method GET -Uri "$env:WIDGET_BASE_URL/health"
```

```powershell
Invoke-RestMethod -Method GET -Uri "$env:WIDGET_BASE_URL/api/v1/widget/latest" -Headers @{ Authorization = "Bearer $env:GARMIN_WIDGET_WIDGET_BEARER_TOKEN" }
```

```powershell
Invoke-RestMethod -Method POST -Uri "$env:WIDGET_BASE_URL/api/v1/widget/refresh" -Headers @{ Authorization = "Bearer $env:GARMIN_WIDGET_WIDGET_BEARER_TOKEN" }
```

Avoid embedding token values in command lines that will be copied into tickets. Prefer environment variables as above.

## Logs and troubleshooting

Safe to inspect:

- HTTP status codes
- Generic `detail` strings
- High-level warnings such as “Live widget refresh failed”

Never log or paste:

- `Authorization` headers or bearer tokens
- Garmin usernames/passwords
- Session cookie/token JSON
- Raw Garmin response bodies
- Personal health metric dumps
- Absolute disk paths from production in public channels

Common issues:

| Symptom | Likely cause |
|---------|--------------|
| Widget `503` auth not configured | Missing/empty `GARMIN_WIDGET_WIDGET_BEARER_TOKEN` |
| Production boot failure | Weak/placeholder/short bearer token |
| `/latest` → `404` | No successful refresh yet |
| Refresh `503` | No snapshot and upstream/session failure |
| Session lost after deploy | Disk missing, wrong `DATA_DIR`, or disk recreated |

## Rollback and recovery

1. Redeploy a known-good commit via Render.
2. **Keep the persistent disk** attached at `/var/data`.
3. Confirm `/health`, then authenticated `/latest` or `/refresh`.
4. If the disk was destroyed: treat as disaster recovery—bootstrap a new Garmin session (temporary credentials), regenerate widget token only if leaked, and re-run verification.

## Custom domain (later)

Exact DNS records depend on the chosen domain and DNS provider and will be completed after those details are supplied.

Conceptually:

1. Add the custom domain on the Render web service.
2. Apply the DNS records Render shows (often CNAME/ALIAS for the apex/www targets).
3. Wait for Render-managed TLS certificate verification to succeed.
4. Retest `/health` and authenticated widget routes over HTTPS on the custom hostname.
5. Update the Android client base URL only after verification.

Do not invent DNS hostnames or record values in this repository.

## Deployment checklist

### Pre-deploy

- [ ] Private repo accessible to Render
- [ ] `render.yaml` reviewed (one instance, `/var/data`, production env, secrets as `sync: false`)
- [ ] Widget bearer token generated with `secrets.token_urlsafe(32)` and stored privately
- [ ] No real secrets present in git status
- [ ] Operators understand single-instance + disk constraints

### Deploy

- [ ] Blueprint/service created from repository
- [ ] Persistent disk mounted at `/var/data`
- [ ] Non-secret env vars match the Blueprint
- [ ] Widget bearer token set in Render secrets
- [ ] Deploy finished; `/health` returns `200`

### Session bootstrap

- [ ] Temporary Garmin username/password set as Render secrets
- [ ] One authenticated `/refresh` succeeded
- [ ] Disk contains session material under `GARMIN_WIDGET_DATA_DIR`
- [ ] `/latest` returns expected normalized JSON (or documented empty only if intentional failure)

### Credential removal

- [ ] Garmin username/password removed from Render env
- [ ] Service redeployed with disk retained
- [ ] Authenticated refresh still succeeds without Garmin password env vars

### Post-deploy verification

- [ ] Unauthorized widget request → `401`
- [ ] Cooldown behavior confirmed
- [ ] Restart/redeploy retains session + snapshot
- [ ] Logs contain no tokens/credentials/raw Garmin bodies

### Later custom-domain verification

- [ ] Domain added in Render
- [ ] DNS records applied at provider
- [ ] TLS verified
- [ ] HTTPS health + authenticated widget checks on custom host
- [ ] Android base URL updated only after success

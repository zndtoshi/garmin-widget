# Render migration: Oregon to Frankfurt

This runbook moves the private Garmin backend to Frankfurt without interrupting the working Oregon service until the replacement is verified.

## Safety rules

- Keep the Oregon service, disk, Blueprint, custom domain, and DNS record intact during initial Frankfurt deployment.
- Use the same widget bearer token on both services so the Android configuration does not need to change.
- Never paste the bearer token or Garmin credentials into Git, chat, screenshots, or logs.
- Do not delete the Oregon disk until Frankfurt has passed refresh, restart, and custom-domain checks.

## 1. Create the Frankfurt replacement

1. In Render, choose **New > Blueprint**.
2. Select `zndtoshi/garmin-widget`.
3. Use branch `main`.
4. Set **Blueprint Path** to `render-frankfurt.yaml`.
5. Name the Blueprint `garmin-widget-frankfurt`.
6. Confirm the planned service is `garmin-widget-backend-eu` in **Frankfurt**.
7. Confirm one Starter instance and a 1 GB disk mounted at `/var/data`.
8. Enter the existing widget bearer token and Garmin username/password as Render secrets.
9. Deploy the Blueprint.

Expected temporary cost during migration: both Oregon and Frankfurt compute/disk resources are billed until Oregon is removed.

## 2. Verify Frankfurt before moving traffic

Use the new Render hostname, expected to resemble:

```text
https://garmin-widget-backend-eu.onrender.com
```

Verify:

1. `GET /health` returns `200`.
2. Widget routes without a bearer token return `401`.
3. One authenticated `POST /api/v1/widget/refresh` returns `SUCCESS`.
4. `GET /api/v1/widget/latest` returns the cached payload.
5. Restart/redeploy the Frankfurt service without detaching its disk.
6. After the 60-second cooldown, refresh again and confirm the session/cache survived.

## 3. Move the custom domain

1. Remove `garmin.zndtoshi.com` from the Oregon service in Render.
2. Add `garmin.zndtoshi.com` to `garmin-widget-backend-eu`.
3. In Namecheap Advanced DNS, change the existing `garmin` CNAME value to the exact Frankfurt `onrender.com` hostname.
4. Return to the Frankfurt service and verify the domain.
5. Wait for the TLS certificate to become active.
6. Verify `https://garmin.zndtoshi.com/health`, unauthorized widget routes, and one authenticated refresh.

A short DNS/TLS transition is possible. The old `onrender.com` URL remains available for rollback.

## 4. Retire Oregon

Only after the custom domain has worked against Frankfurt:

1. Keep Oregon for a short observation window if desired.
2. Suspend or delete the Oregon web service and its disk to stop duplicate billing.
3. Remove or archive the old Oregon Blueprint.
4. Keep the Frankfurt Blueprint as the active deployment configuration.

## Rollback

If Frankfurt fails before Oregon is deleted:

1. Reattach `garmin.zndtoshi.com` to the Oregon service.
2. Restore the Namecheap `garmin` CNAME to `garmin-widget-backend.onrender.com`.
3. Verify Oregon health and authenticated refresh.

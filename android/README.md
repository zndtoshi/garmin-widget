# Android Garmin Widget

Private Android app and Jetpack Glance home-screen widget for the deployed Garmin backend.

## Current state

Phase 8B premium adaptive widget UI is implemented on the device-polish branch line; visual verification across launchers remains pending. Phase 8C configuration-app dark theme is in progress on this branch.

- Kotlin/Compose configuration activity with a **forced dark** Material 3 theme (charcoal surfaces, Garmin cyan primary, purple accent)
- Dark status/navigation bars and dark window background to avoid a white launch flash
- Default backend: `https://garmin.zndtoshi.com`
- Bearer token encrypted with Android Keystore (AES-GCM)
- Latest widget payload cached in app-private storage
- Three widget-picker presets (**Garmin Compact**, **Garmin Wide**, **Garmin Large**) sharing one adaptive Glance implementation (`SizeMode.Exact` + `AdaptiveLayoutSpec` from real `LocalSize`)
- Manual refresh through one deduplicated WorkManager job (no network constraint)
- Cached-data preservation across network and authentication failures
- Widget-body launch into Garmin Connect, with browser fallback
- Background opacity control (0-100%) persisted locally with immediate widget refresh
- **Backend-first additive compatibility**: parser + cache handle deployed additive fields plus optional `lastActivity.heartRateTimeline` while preserving original-field compatibility. Sleep legend shows stage initials only (no per-stage durations). Large activity cards can draw a real HR chart when timeline samples exist.

The app never stores Garmin credentials and never contacts Garmin directly.

### Upcoming work

- **Phase 8B**: Device visual verification and polish for launcher-specific rendering behavior (presets/adaptive/HR chart pending on-device confirmation)
- **Phase 8C**: Approved 30-minute periodic background refresh via WorkManager

## Build prerequisites

- Android Studio with JDK 17
- Android SDK 35

Open this `android` directory as the Android Studio project, allow Gradle sync to finish, then run the `app` configuration on an Android 8.0 (API 26) or newer device/emulator.

## Private setup

1. Open the installed app.
2. Keep the default backend URL unless the deployment changes.
3. Paste the same widget bearer token stored in Render.
4. Tap **Save and refresh**.
5. Pin **Compact**, **Wide**, or **Large** from the app, or add the matching preset from the launcher widget picker.

Never commit the bearer token or include it in screenshots/logs.

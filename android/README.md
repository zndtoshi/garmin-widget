# Android Garmin Widget

Private Android app and Jetpack Glance home-screen widget for the deployed Garmin backend.

## Current state

Phase 8A backend data contract is merged, deployed, and live-verified. Phase 8B/8D widget UI is a **single adaptive wide two-row Glance widget** (`SizeMode.Exact`); device visual verification across launchers remains pending. Phase 8C configuration-app dark theme is included.

- Kotlin/Compose configuration activity with a **forced dark** Material 3 theme (charcoal surfaces, Garmin cyan primary, purple accent)
- Dark status/navigation bars and dark window background to avoid a white launch flash
- Default backend: `https://garmin.zndtoshi.com`
- Bearer token encrypted with Android Keystore (AES-GCM)
- Latest widget payload cached in app-private storage
- **One** widget-picker entry (**Garmin Widget**) with a fixed two-row composition: equal Sleep / HRV / Body Battery panels on top; activity details + HR chart on the bottom
- Manual refresh through one deduplicated WorkManager job (no network constraint)
- Cached-data preservation across network and authentication failures
- Widget-body launch into Garmin Connect, with browser fallback
- Background opacity control (0-100%) persisted locally with immediate widget refresh
- Activity HR chart color mode (`White + red peaks` default, or `Garmin HR zones`) persisted locally; zone colors are percentage bands reaching pure red at 95% of the resolved max-HR ceiling, not personal Garmin zone settings
- Closing the lower card dismisses both the current Body Battery and latest Activity views in one action; new morning/activity events and the existing explicit restore flows still reopen the appropriate card
- **Backend-first additive compatibility**: parser + cache handle deployed additive fields plus optional `lastActivity.heartRateTimeline` while preserving original-field compatibility. Sleep shows ring + total duration only (no stage legend).

The app never stores Garmin credentials and never contacts Garmin directly.

### Upcoming work

- Device visual verification for the single two-row widget across Samsung/Lawnchair resizing
- Approved 30-minute periodic background refresh via WorkManager

## Build prerequisites

- Android Studio with JDK 17
- Android SDK 35

Open this `android` directory as the Android Studio project, allow Gradle sync to finish, then run the `app` configuration on an Android 8.0 (API 26) or newer device/emulator.

## Private setup

1. Open the installed app.
2. Keep the default backend URL unless the deployment changes.
3. Paste the same widget bearer token stored in Render.
4. Tap **Save and refresh**.
5. Tap **Add widget to home screen**, or add **Garmin Widget** from the launcher widget picker.

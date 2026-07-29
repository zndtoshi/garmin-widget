# Android Garmin Widget

Private Android app and Jetpack Glance home-screen widget for the deployed Garmin backend.

## Current Phase 7 increment

- Kotlin/Compose configuration activity
- Default backend: `https://garmin.zndtoshi.com`
- Bearer token encrypted with Android Keystore (AES-GCM)
- Latest widget payload cached in app-private storage
- Responsive Glance widget with primary daily metrics
- Manual refresh through one deduplicated WorkManager job
- Cached-data preservation across network and authentication failures
- Widget-body launch into Garmin Connect, with browser fallback

The app never stores Garmin credentials and never contacts Garmin directly.

## Build prerequisites

- Android Studio with JDK 17
- Android SDK 35

Open this `android` directory as the Android Studio project, allow Gradle sync to finish, then run the `app` configuration on an Android 8.0 (API 26) or newer device/emulator.

## Private setup

1. Open the installed app.
2. Keep the default backend URL unless the deployment changes.
3. Paste the same widget bearer token stored in Render.
4. Tap **Save and refresh**.
5. Tap **Add widget to home screen**, or add **Garmin Daily Metrics** from the launcher widget picker.

Never commit the bearer token or include it in screenshots/logs.

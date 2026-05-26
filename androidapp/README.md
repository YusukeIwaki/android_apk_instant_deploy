# Android App

Android companion app for device registration and policy-driven APK installation.

## Implemented behavior

- Handles `apkdist://register-device?identifier=...&secret=...&server_base_url=...` deep links.
- Saves `server_base_url` from the registration link before calling the registration API.
- Keeps the registration button disabled until a display name and notification token are available.
- Calls `POST /api/devices`, stores `identifier` and `device_auth_token`, and uses the token for device APIs.
- Fetches `GET /api/devices/me/policy` only when a fetch is pending or the user taps update.
- Shows required apps and available apps from the policy entries.
- Uses WorkManager, `GET /api/releases/:release_id/artifact_url`, OkHttp streaming, app-private APK files, and `PackageInstaller` sessions to start APK installation without loading APK bytes into memory.
- Stores active WorkManager download state by release/version so an active download disables the matching install action instead of enqueueing the same APK repeatedly.

The default server URL is `http://10.0.2.2:4567` for Android Emulator. Registration links should carry `server_base_url`; Settings can still change the URL after registration.

## Build

Install a JDK and Android SDK platform 35, then run:

```sh
cd androidapp
./gradlew :app:assembleDebug
```

The current token provider returns a deterministic development token with the `fcm:dev:` prefix. Wire Firebase Messaging before using push delivery outside local development.

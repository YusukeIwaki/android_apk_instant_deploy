# Android App

Android companion app for device registration and policy-driven APK installation.

## Implemented behavior

- Handles `apkdist://register-device?identifier=...&secret=...&server_base_url=...` deep links.
- Saves `server_base_url` from the registration link before calling the registration API.
- When an already registered device opens a registration link for a different server, offers to switch the saved server URL and clears the cached policy snapshot.
- Keeps the registration button disabled until a display name and notification token are available.
- Calls `POST /api/devices`, stores `identifier` and `device_auth_token`, and uses the token for device APIs.
- Retrieves Firebase Messaging tokens, handles token refresh, and updates the server with `PUT /api/devices/me/fcm_push_token`.
- Handles `POLICY_UPDATED` FCM data messages by marking policy fetch pending.
- Fetches `GET /api/devices/me/policy` only when a fetch is pending or the user taps update.
- Shows the current policy as one app list, with required apps marked by a chip and device/server/app-version details at the bottom of home.
- Fetches `GET /api/notifications` for the home notification action and in-app notification list.
- Uses WorkManager, `GET /api/releases/:release_id/artifact_url`, OkHttp streaming, app-private APK files, and `PackageInstaller` sessions to start APK installation without loading APK bytes into memory.
- Stores active WorkManager download state by release/version so an active download disables the matching install action instead of enqueueing the same APK repeatedly.

The default server URL is `http://10.0.2.2:4567` for Android Emulator. Registration links should carry `server_base_url`; after registration, server switching is only exposed when another registration link points to a different server.

## Build

Install a JDK and Android SDK platform 35, then run:

```sh
cd androidapp
./gradlew :app:assembleDebug
```

`androidapp/app/google-services.json` must exist for Firebase Messaging. The current local project file has been placed there.

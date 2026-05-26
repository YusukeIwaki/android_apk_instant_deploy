# Android App Architecture Note

This note describes the current `androidapp/` architecture and the main points a developer should understand before making changes.

## Overview

`androidapp/` is a small Java Android companion app. It registers a device through a custom scheme deep link, fetches the device policy from the Sinatra server, and starts APK installs through Android `PackageInstaller`.

The app is intentionally framework-light:

- `MainActivity.java` owns screen state, navigation, user input, permission prompts, and orchestration.
- `ApiClient.java` owns API HTTP calls, JSON parsing, and API error mapping.
- `AppStore.java` owns local `SharedPreferences` persistence for server URL, registration credentials, display name, FCM token, pending fetch state, and the last fetched policy JSON.
- `FcmTokenProvider.java` currently returns a deterministic development token. It is a placeholder for Firebase Messaging integration.
- `ApkDownloadManager.java` enqueues unique WorkManager jobs for APK downloads and keeps the app-private file path stable per release/version.
- `ApkDownloadWorker.java` fetches a fresh artifact URL, streams the APK with OkHttp into app-private storage, updates local progress, and starts installation.
- `ApkDownloadStore.java` stores active WorkManager job metadata by release/version so repeated taps do not enqueue the same APK multiple times.
- `MainActivity.java` reflects the active download state from local progress data and disables matching install actions while a job is active.
- `ApkInstaller.java` wraps `PackageInstaller` sessions.
- `InstallNotificationReceiver.java` handles install commit callbacks and opens the Android confirmation UI when user action is required.
- `AndroidManifest.xml` declares the launcher, `apkdist://register-device` deep link, install receiver, and required permissions.

The code currently uses programmatic Android views rather than XML layouts. Keep UI changes simple and product-flow oriented unless the app is deliberately moved to a fuller UI architecture.

## Registration Flow

The app must be opened through a custom scheme link:

```text
apkdist://register-device?identifier=...&secret=...&server_base_url=...
```

Registration is not browser-based AMAPI enrollment. The companion app must run because it needs a notification token before it can register.

Current behavior:

1. `MainActivity.handleIntent` detects the deep link.
2. If already registered locally, the app shows an already-registered screen and does not post registration again.
3. If the link includes `server_base_url`, the app validates that it is HTTP(S), saves it, and rebuilds `ApiClient` before registration.
4. If the link is missing identifier or secret, the app blocks registration.
5. The registration screen asks for a required display name.
6. The registration button stays disabled until both display name and FCM token are available.
7. `ApiClient.registerDevice` calls `POST /api/devices`.
8. `AppStore.saveRegistration` stores `identifier`, `device_auth_token`, display name, FCM token, and marks policy fetch pending.

Do not introduce a device-generated primary identifier. The server/device identity is `devices.identifier`, created from the server-issued `DeviceRegistrationToken` identifier.

Do not auto-collect model, manufacturer, OS version, or similar profile details unless the product design changes. The current registration profile is display name only.

## Policy and Install Flow

After registration, `MainActivity.showHome` fetches policy when:

- registration just completed,
- a pending fetch flag is set,
- no cached policy JSON exists,
- or the user taps update.

Otherwise the last fetched policy JSON is parsed locally for display.

Policy entries are split by install mode:

- `FORCE_INSTALLED` appears as required action.
- `AVAILABLE` appears as optional app list/detail.

Install flow:

1. User selects an entry.
2. The app checks `REQUEST_INSTALL_PACKAGES` capability and sends the user to Android settings when needed.
3. `ApkDownloadManager` enqueues a WorkManager unique work named by `release_id + version_code` and records the app-private APK path.
4. `ApkDownloadWorker` calls `GET /api/releases/:release_id/artifact_url` inside the worker so retries obtain a fresh short-lived URL.
5. `ApkDownloadWorker` streams the APK with OkHttp to `context.getFilesDir()/apk-downloads/*.apk.part`, resumes with HTTP `Range` when a partial file exists and the server supports it, then renames to `.apk`.
6. While `ApkDownloadStore` has an active job for that release, `MainActivity` disables the matching install action and displays waiting/running/installing progress.
7. After download completion, `ApkDownloadWorker` passes the app-private APK file to `ApkInstaller`.
8. `ApkInstaller.install` streams the downloaded APK into a full-install `PackageInstaller` session and commits it.
9. `InstallNotificationReceiver` handles success, failure, or pending user action and removes the temporary APK file/state when the install reaches a terminal result.

Android does not allow silent arbitrary APK installs for this companion app. Expect a platform confirmation flow unless the app later becomes a device owner, profile owner, privileged app, or uses another managed-device channel.

The server already has `POST /api/devices/me/policy_sync_results`, but the current app does not yet submit detailed sync reports after install attempts. Add that deliberately when the install result model is ready, and make repeated reports idempotent by policy revision as the server expects.

## Local Persistence

`AppStore` stores device credentials in normal `SharedPreferences`. This is enough for the current development app, but production hardening should move bearer tokens to encrypted storage.

Stored values:

- `server_base_url`
- `identifier`
- `device_auth_token`
- `display_name`
- `fcm_token`
- `pending_policy_fetch`
- `fetched_policy_json`

`ApkDownloadStore` separately stores active APK download work keyed by release/version. These records are temporary scheduler references, not policy sync completion state; they are removed when the worker hits a non-retryable download failure, when the user-visible install reaches a terminal PackageInstaller result, or when cleanup explicitly cancels the job. APK bytes stay under app-private `filesDir/apk-downloads`, with partial downloads kept as `.part` files for retry/resume.

Default server URL is `http://10.0.2.2:4567`, which works for Android Emulator talking to a host machine server. Physical devices need a reachable server URL configured from Settings, and the server's device-facing artifact URL must also be reachable by that device.

## FCM

`FcmTokenProvider` is a development shim and returns `fcm:dev:<sha256>`. This only satisfies the registration contract locally.

Before using push delivery outside local development:

- add Firebase dependencies and configuration,
- retrieve the real Firebase Messaging token asynchronously,
- handle token refresh,
- update the server-side `fcm_push_tokens` record when the token changes,
- keep registration disabled until a valid token is available.

Do not rename server data to `device_push_tokens`; the design calls the table `fcm_push_tokens`.

## Permissions and Manifest

The manifest currently declares:

- `INTERNET` for API calls and APK download.
- `POST_NOTIFICATIONS` for Android 13+ notification permission.
- `REQUEST_INSTALL_PACKAGES` for user-approved APK installation.
- `QUERY_ALL_PACKAGES` for broad package visibility if future policy comparison needs installed-package inspection.
- A browsable `apkdist://register-device` intent filter on `MainActivity`.
- A non-exported install callback receiver.

Be careful when changing exported components. The registration activity must remain reachable from the deep link, while install callbacks should remain non-exported unless there is a concrete platform requirement.

## Development Cautions

- Build with Java 17 and an installed Android SDK. The local Gradle build will fail if `ANDROID_HOME` or `local.properties` is missing.
- Keep API error-code handling in sync with server error codes. The UI currently branches on stable codes, not Japanese message text.
- Do not enable registration before display name and FCM token are ready.
- Do not make registration a generic browser flow; FCM acquisition and local credential storage belong in the app.
- Do not put server-only concepts such as DB table names, policy internals, or object storage keys into user-facing Android text.
- APK download uses WorkManager + OkHttp, writes into app-private files, then streams the downloaded file into `PackageInstaller`; avoid reintroducing an in-memory `byte[]` APK path or Android `DownloadManager`.
- Keep active download state keyed by release/version and backed by WorkManager unique work so the UI cannot enqueue duplicate downloads for the same APK.
- `ExecutorService` is single-threaded so network and install orchestration stay ordered. If adding parallel work, keep UI updates on the main thread.
- `SharedPreferences` persistence means uninstalling the companion app loses device credentials; the current recovery path is re-registration.

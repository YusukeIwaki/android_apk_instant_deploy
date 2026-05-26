# Android App Architecture Note

This note describes the current `androidapp/` architecture and the main points a developer should understand before making changes.

## Overview

`androidapp/` is a small Java Android companion app. It registers a device through a custom scheme deep link, fetches the device policy from the Sinatra server, and starts APK installs through Android `PackageInstaller`.

The app is intentionally framework-light:

- `MainActivity.java` owns screen state, navigation, user input, permission prompts, and orchestration.
- `ApiClient.java` owns API HTTP calls, JSON parsing, and API error mapping.
- `AppStore.java` owns local `SharedPreferences` persistence for server URL, registration credentials, display name, FCM token, pending fetch state, and the last fetched policy JSON.
- `FcmTokenProvider.java` obtains the current Firebase Messaging token for registration.
- `FcmMessagingService.java` handles FCM data messages and token refresh callbacks.
- `ApkDownloadManager.java` enqueues unique WorkManager jobs for APK downloads and keeps the app-private file path stable per release/version.
- `ApkDownloadWorker.java` fetches a fresh artifact URL, streams the APK with OkHttp into app-private storage, updates local progress, and starts installation.
- `ApkDownloadStore.java` stores active WorkManager job metadata by release/version so repeated taps do not enqueue the same APK multiple times.
- `MainActivity.java` reflects the active download state from local progress data and disables matching install actions while a job is active.
- `ApkInstaller.java` wraps `PackageInstaller` sessions.
- `InstallNotificationReceiver.java` handles install commit callbacks and opens the Android confirmation UI when user action is required.
- `AndroidManifest.xml` declares the launcher, `apkdist://register-device` deep link, install receiver, and required permissions.

The code currently uses programmatic Android views rather than XML layouts. Keep UI changes simple and product-flow oriented unless the app is deliberately moved to a fuller UI architecture.

## Registration Flow

The app supports two registration inputs. AMAPI managed devices can receive registration values through managed configuration, while manual or unmanaged devices open a custom scheme link:

```text
apkdist://register-device?identifier=...&secret=...&server_base_url=...
```

Registration is not browser-based AMAPI enrollment. The companion app must run because it needs a notification token before it can register.

Current behavior:

1. `MainActivity` declares `android.content.APP_RESTRICTIONS` and reads `RestrictionsManager.getApplicationRestrictions()` on launch/resume and when `ACTION_APPLICATION_RESTRICTIONS_CHANGED` is received.
2. If managed configuration includes `device_registration_identifier`, `device_registration_secret`, `server_base_url`, and `display_name`, the app saves the server URL, obtains an FCM token, and calls `POST /api/devices` automatically.
3. If managed configuration includes identifier/secret but no display name, the app falls back to the registration screen so the user can enter the required display name.
4. `MainActivity.handleIntent` detects the deep link.
5. If already registered locally, the app shows an already-registered screen and does not post registration again.
6. If the link includes `server_base_url`, the app validates that it is HTTP(S), saves it, and rebuilds `ApiClient` before registration.
7. If the link is missing identifier or secret, the app blocks registration.
8. The registration screen asks for a required display name.
9. The registration button stays disabled until both display name and FCM token are available.
10. `ApiClient.registerDevice` calls `POST /api/devices`.
11. `AppStore.saveRegistration` stores `identifier`, `device_auth_token`, display name, FCM token, and marks policy fetch pending.
12. `FcmMessagingService.onNewToken` stores refreshed FCM tokens locally and updates the server via `PUT /api/devices/me/fcm_push_token` when the device is registered.

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
2. The app treats an install/update as needed when the package is missing, the server `version_code` is newer than the installed one, or the `version_code` is the same but the server `artifact_sha256` differs from the installed APK checksum.
3. The app checks `REQUEST_INSTALL_PACKAGES` capability and sends the user to Android settings when needed.
4. `ApkDownloadManager` enqueues a WorkManager unique work named by `release_id + version_code` and records the app-private APK path.
5. `ApkDownloadWorker` calls `GET /api/releases/:release_id/artifact_url` inside the worker so retries obtain a fresh short-lived URL.
6. `ApkDownloadWorker` streams the APK with OkHttp to `context.getFilesDir()/apk-downloads/*.apk.part`, resumes with HTTP `Range` when a partial file exists and the server supports it, then renames to `.apk`.
7. While `ApkDownloadStore` has an active job for that release, `MainActivity` disables the matching install action and displays waiting/running/installing progress.
8. After download completion, `ApkDownloadWorker` passes the app-private APK file to `ApkInstaller`.
9. `ApkInstaller.install` streams the downloaded APK into a full-install `PackageInstaller` session and commits it.
10. `InstallNotificationReceiver` handles success, failure, or pending user action and removes the temporary APK file/state when the install reaches a terminal result.

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

`FcmTokenProvider` retrieves the Firebase Messaging token through Firebase SDK. `FcmMessagingService` handles:

- `POLICY_UPDATED` data messages by setting `pending_policy_fetch=true`,
- deleted-message callbacks by setting `pending_policy_fetch=true` so the next foreground/update path performs a full policy fetch,
- token refresh by storing the new token and calling `PUT /api/devices/me/fcm_push_token` for registered devices.

Registration stays disabled until a valid token is available.

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

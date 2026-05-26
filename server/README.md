# Server

Sinatra implementation of the API described in `development/public/openapi.yaml`.

## Run

```sh
cd server
docker compose up --build
docker compose run --rm migrate
```

The local server listens on `http://localhost:4567`. Admin endpoints require:

```sh
Authorization: Bearer dev-admin-token
```

Set `ADMIN_TOKEN`, `TOKEN_HMAC_SECRET`, and `TOKEN_ENCRYPTION_SECRET` for non-local environments.
FCM delivery uses `FCM_SERVICE_ACCOUNT_JSON` or `GOOGLE_APPLICATION_CREDENTIALS` to locate a Firebase service account JSON file. The local Compose setup points to `server/config/firebase-service-account.json`, which is intentionally ignored by Git.

Sinatra 4's Host Authorization is disabled for this development server so ngrok and physical-device testing work without host allow-list churn. If generated artifact URLs must always use a specific public origin, set `PUBLIC_BASE_URL`, for example `https://example.ngrok-free.app`.

Device registration deep links also include `server_base_url`. When testing through ngrok, open the admin UI through ngrok or set `PUBLIC_BASE_URL` so QR/deep link registration points the Android app at the tunnel URL instead of `localhost`.

If you previously ran the old skeleton server, reset the local MySQL volume before applying this schema:

```sh
docker compose down -v
docker compose up --build
docker compose run --rm migrate
```

## Implemented API

- `POST /admin/device_registration_tokens`
- `POST /api/devices`
- `PUT /api/devices/me/fcm_push_token`
- `DELETE /admin/devices/:identifier`
- `POST /admin/artifacts`
- `GET /admin/apps`
- `DELETE /admin/releases/:release_id`
- `GET /api/releases/:release_id/artifact_url`
- `GET /admin/devices/:identifier/policy`
- `PUT /admin/devices/:identifier/policy`
- `GET /api/devices/me/policy`
- `POST /api/devices/me/policy_sync_results`
- `GET /api/notifications`

## Notes

`POST /admin/artifacts` accepts a single multipart field named `file`. The server validates APK structure, extracts manifest metadata, stores the APK object, creates `apps` / `app_profiles` / `app_icons` / `artifacts` / `releases`, and rejects duplicate APK bytes by `artifacts.sha256`. Re-uploading the same package/versionCode with different bytes creates another release.

Object storage defaults to S3 when `S3_BUCKET` is set. In Compose, Floci is only reachable from other containers on the Compose network. Device-facing artifact URLs point at the Sinatra server, and the server streams the object from Floci.

Set `OBJECT_STORE=local` to store APK bytes under `server/storage/artifacts` and return local download URLs.

APK signing certificate extraction uses `APKSIGNER_PATH` or `apksigner` when available. If neither `apksigner` nor a v1 signature certificate is available, `signing_cert_sha256` is recorded as `UNKNOWN`; existing known certificates are still enforced when present.

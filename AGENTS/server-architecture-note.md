# Server Architecture Note

This note describes the current `server/` architecture and the main points a developer should understand before making changes.

## Overview

`server/` is a Sinatra API implementation for the product flows described by the Astro documentation and `development/public/openapi.yaml`.

The server is intentionally small and explicit:

- `app.rb` owns HTTP routing, request validation, response shaping, and transaction boundaries.
- `lib/models.rb` defines ActiveRecord associations only. Domain behavior is kept in the route/service helpers until a real need for extraction appears.
- `db/Schemafile` is the Ridgepole source of truth for the MySQL schema.
- `lib/security.rb` owns token generation, HMAC hashing, constant-time comparison, and token encryption.
- `lib/apk_inspector.rb` extracts APK metadata and signing certificate fingerprints.
- `lib/object_store.rb` hides local storage vs S3-compatible storage and generates device-facing artifact download URLs.

The implementation follows the documentation-level design rules in the root `AGENTS.md`: identity tables stay focused on stable identity, mutable concepts are split into separate tables, and one-time registration tokens are deleted after use instead of marked consumed.

## Runtime Topology

Local development uses Docker Compose from `server/`:

- `server` publishes Sinatra on `localhost:4567`.
- `mysql` is internal to the Compose project and persists data in `mysql-data`.
- `floci` emulates S3 and must remain internal-only. It does not need host port publishing because only the Sinatra container talks to it.
- `create-bucket` creates the local S3 bucket after Floci is healthy.
- `migrate` runs Ridgepole and is kept behind the `tools` profile.

Sinatra 4 Host Authorization is disabled for this development server so ngrok and physical-device testing work without host allow-list churn. Set `PUBLIC_BASE_URL` when generated device-facing artifact URLs must use a specific public origin.

Do not expose Floci just to make APK downloads work. In the default Compose setup, artifact URLs returned to devices point at Sinatra `/artifact_objects/...`; Sinatra verifies a short-lived HMAC signature, reads the object from Floci over the Compose network, and streams the APK to the device. `S3_PUBLIC_ENDPOINT` exists for environments that deliberately want direct presigned S3 URLs, but local Compose should leave it unset.

If a developer has an old skeleton database volume, Ridgepole may fail because the schema changed substantially. For disposable local data, reset with `docker compose down -v` before applying `db/Schemafile`.

## API Boundaries

Admin endpoints use `Authorization: Bearer <ADMIN_TOKEN>`. Device endpoints use the per-device `device_auth_token` returned from registration. Only HMACs are stored for secrets and device tokens.

The API response style is:

- Success responses match the documented OpenAPI shape.
- Error responses use `{ "error": { "code": "...", "message": "..." } }`.
- Use concrete domain error codes; Android branches on codes such as `DEVICE_ALREADY_REGISTERED`, `REGISTRATION_TOKEN_NOT_FOUND`, and `DEVICE_AUTH_REQUIRED`.

Keep `app.rb` OpenAPI-aligned when adding endpoints. If behavior changes, update the design docs and mock screens in `development/` in the same pass.

## Data Model

Device registration is centered on stable identifiers:

- `device_identifiers.identifier` is the product identity string.
- `device_registration_tokens` stores the temporary registration secret hash and expiration.
- `devices` records registration of an identifier and contains only registration facts.
- `device_profiles` stores the user-provided display name.
- `device_credentials` stores the current device bearer-token HMAC.
- `fcm_push_tokens` stores encrypted and hashed FCM token material.

Do not add `status`, `active`, `enabled`, `consumed`, or similar lifecycle flags as shortcuts. For registration, completion is inferred from the existence of the `devices` row for the same `device_identifiers.identifier`; the registration token row is deleted after successful registration.

Apps and releases are also split by lifecycle:

- `apps` owns package identity.
- `app_profiles` and `app_icons` hold mutable display data.
- `artifacts` owns stored APK object facts.
- `releases` joins an app to an artifact and version metadata.

Policies are immutable by revision:

- `device_policies.current_revision_id` points to the active revision.
- `device_policy_revisions` are append-only snapshots.
- `device_policy_app_entries` record app id and install mode for a revision.
- `device_policy_sync_reports` are idempotent by `(device_id, device_policy_revision_id)`.

Policy entries intentionally reference apps, not fixed release ids. The API resolves the latest release when serving policy so policy ownership remains "which apps should this device have" rather than "which artifact object should this device fetch forever."

## Main Flows

Device registration:

1. Admin creates a `DeviceRegistrationToken`.
2. The response includes `identifier`, `registration_secret`, and an `apkdist://register-device?...` deep link that carries `server_base_url`.
3. Android saves `server_base_url`, then posts identifier, secret, display name, and FCM token to `POST /api/devices`.
4. The server creates device identity/profile/credential/push-token records in one transaction and deletes the registration token.
5. Reusing the same link returns an already-registered error without changing device, profile, or push-token records.
6. Later Firebase token refreshes are written through `PUT /api/devices/me/fcm_push_token`.

Artifact upload:

1. Admin uploads exactly one multipart field named `file`.
2. The server validates the APK, extracts package/version/signing metadata, computes object facts, and stores bytes.
3. The server creates or updates app profile data and creates one release for the uploaded version.
4. Duplicate APK bytes are rejected by `artifacts.sha256`, while the same `(app, version_code)` may be uploaded again when the APK checksum differs. Signing certificate mismatches are rejected when the existing and incoming certificates are known.

Policy sync:

1. Admin writes a full policy entry list for a device.
2. The server creates a new policy revision and flips `current_revision_id`.
3. `notify_policy_updated` sends an FCM data message through `FcmPushClient` after the DB transaction completes. FCM failures are logged and do not roll back the policy revision.
4. Device fetches `GET /api/devices/me/policy`.
5. Device reports applied actions to `POST /api/devices/me/policy_sync_results`; the server returns the existing report on repeated submission for the same revision.

## Development Cautions

- Prefer adding small helper methods near the route that uses them until behavior is reused. Avoid premature service-object sprawl.
- Keep DB writes that represent one product event inside a transaction.
- When storing or returning secrets, return raw values only at issuance time; persist hashes or encrypted values.
- Do not put Floci hostnames or internal S3 URLs into device-facing responses unless the deployment intentionally exposes that endpoint.
- `ApkInspector` has fallbacks for local tooling, but production-quality signing validation should prefer `apksigner`.
- `FcmPushClient` owns HTTP v1 FCM delivery and OAuth token minting from the configured service account JSON. Keep it behind `settings.fcm_push_client_factory` so tests can replace it without network access.
- When adding Android-visible errors, keep the server error code stable and update `androidapp` handling deliberately.
- `server/openapi.yaml` is not the canonical local source right now; use `development/public/openapi.yaml` and the Astro pages as the behavior reference unless the project reintroduces a generated copy.

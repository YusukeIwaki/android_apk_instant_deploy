# Project Design Rules

## Documentation Goals

- The Astro documentation under `development/` should explain use-case processing flows at the product and data-design level.
- Each use-case page should make it clear what the operator or device user does, what API call happens, and what DB records are created, updated, deleted, or intentionally not touched.
- Prefer diagrams, screen mockups, and short `curl` examples over source-code-level implementation details. Code examples are only for conveying request/response shape.
- For step-by-step explanations, pair each step with a concrete visual: desktop admin operations use a Chrome-like desktop browser frame, and smartphone/app operations use a smartphone frame.
- Iframes used for HTML/CSS mockups must resize to their content and avoid double scrollbars.
- The index page should stay a simple page list. Do not add an overview section there.
- The docs layout should not use a hero/header band for these design pages. The main content should use the available screen width, and the sidebar label for the root page is `TOP`.

## Data Modeling

- Prefer identity tables to contain only stable identifiers and facts intrinsic to that identity.
- Keep factual timestamps such as `createdAt`, `issuedAt`, `registeredAt`, and `expiresAt` when they record events that happened.
- Avoid storing mutable state flags such as `enabled`, `active`, `inactive`, `status`, or similar columns on core identity tables unless there is a strong domain reason.
- When a concept has a different meaning, lifecycle, owner, or update trigger, model it as a separate table related to the identity table instead of adding columns to the identity table.
- Examples for devices: keep `devices` focused on device identity such as `uniqueIdentifier` and registration facts. Store profile data in `device_profiles`, credentials in `device_credentials`, FCM tokens in `fcm_push_tokens`, and group membership in a separate relationship table.
- For one-time registration records, prefer deleting the registration token after successful registration. If the same token is used again, determine completion from the existence of the resulting identity record, such as `devices.uniqueIdentifier`, instead of preserving a consumed status flag or creating a separate usage table by default.
- This follows the identity modeling approach described in "Identifying User Identity" (https://speakerdeck.com/moro/identifying-user-idenity): name and model the domain identity explicitly, and split different concepts instead of compressing them into a generic stateful record.

## Device Registration Design

- Device registration in this project is not Android Management API managed-device enrollment. Do not describe AMAPI enrollment as the registration flow.
- Use the term `DeviceRegistrationToken`. Do not call it an enrollment token, because that conflicts with AMAPI terminology.
- The server issues `registrationTokenId` and a registration secret. The registration link or QR code carries those values.
- The app/server device identity is `devices.uniqueIdentifier`, not `deviceId`. In the current design, `uniqueIdentifier` is created from the `registrationTokenId` value when registration succeeds.
- Do not use a device-generated random `localInstallationId` as the main identifier in the design.
- The registration link should use a custom scheme deep link such as `apkdist://register-device?...` so the companion app starts directly. Do not center the flow on a browser registration page, because FCM token retrieval requires the app to run.
- The app must not enable the registration action until it has obtained an FCM token and the user has entered the required display name.
- The display name is user-provided, required during registration, changeable later, and stored in `device_profiles`, not in `devices`.
- Do not auto-collect device profile details such as OS, manufacturer, or model unless explicitly requested. The current registration profile is the display name only.
- Store push tokens in `fcm_push_tokens`, not `device_push_tokens`. Upsert by `uniqueIdentifier`; do not describe a separate procedure that marks old tokens inactive unless a later design explicitly requires token history.
- `device_credentials` is separate from `devices` because credential lifecycle and rotation differ from identity lifecycle.
- `device_group_memberships` or equivalent relationship tables should be separate from `devices`; group membership is not intrinsic device identity.
- On successful registration, delete the `device_registration_tokens` row. For later access using the same link, the expected behavior is: token row is absent, `devices.uniqueIdentifier` with the same value exists, so the request is rejected as an already registered link and no `devices`, `device_profiles`, or `fcm_push_tokens` records are changed.
- The device registration page should stop at registration completion and already-registered-link behavior. Do not add initial sync, policy motivation, install-task behavior, or distribution workflow details to this page unless explicitly asked.
- The registration completion mock should show completion clearly and only necessary actions such as close. Do not add a display-name edit action to the completion screen.

## Common Review Pitfalls

- Do not add `enabled`, `status`, `active`, `inactive`, or `CONSUMED` columns to core tables as a shortcut for modeling behavior.
- Do not preserve a consumed registration-token status if deleting the token and checking `devices.uniqueIdentifier` answers the question.
- Do not introduce extra tables just to remember a state that can be inferred from identity existence, such as a registration token usage table, unless the product needs an audit trail.
- Do not mix concepts with different update triggers into `devices`; split profile, credential, FCM token, and group membership data.
- Do not let mock UI imply browser-based registration, automatic profile collection, AMAPI enrollment, or registration before FCM token acquisition.
- Do not let docs drift into implementation internals. The page should explain processing responsibility, data ownership, and observable behavior.

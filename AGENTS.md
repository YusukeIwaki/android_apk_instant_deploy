# Project Design Rules

## Implementation Architecture Notes

- Server-side implementation guidance is maintained in `AGENTS/server-architecture-note.md`. Read it before changing `server/`, especially API behavior, database schema, object storage, authentication, or Docker Compose wiring.
- Android companion app implementation guidance is maintained in `AGENTS/androidapp-architecture-note.md`. Read it before changing `androidapp/`, especially registration deep links, local persistence, policy sync, APK installation, permissions, or FCM behavior.
- When locally checking `server/`, do not use the host's native Ruby or Bundler. Run server-side commands through Docker Compose, for example `cd server && docker compose run --rm server bundle exec rake hoge`, so the Ruby/Bundler versions and service environment match the project.
- Run Smartest tests through the test Compose service with aliases enabled, for example `cd server && docker compose --profile test run --rm --use-aliases test bundle exec smartest smartest/` or `cd server && docker compose --profile test run --rm --use-aliases test bundle exec ruby smartest/admin_api_test.rb`. The `--use-aliases` flag is required because Playwright resolves the Sinatra test server through the Compose service alias. Do not run multiple Smartest commands in parallel; they share the same test database and can interfere with each other.
- Before building `androidapp/`, load `androidapp/.envrc` or otherwise export its `JAVA_HOME` so Gradle uses Java 17; missing this commonly causes local Android builds to fail.

## Documentation Goals

- The Astro documentation under `development/` should explain use-case processing flows at the product and data-design level.
- Each use-case page should make it clear what the operator or device user does, what API call happens, and what DB records are created, updated, deleted, or intentionally not touched.
- Prefer diagrams, screen mockups, and short `curl` examples over source-code-level implementation details. Code examples are only for conveying request/response shape.
- For step-by-step explanations, pair each step with a concrete visual: desktop admin operations use a Chrome-like desktop browser frame, and smartphone/app operations use a smartphone frame.
- Iframes used for HTML/CSS mockups must resize to their content and avoid double scrollbars.
- The index page contains a short reading guide (how to read each use-case page, what the mocks are scoped to) plus a page list where each entry carries a one- to two-sentence summary and a target-audience hint. Do not add product overviews, architecture diagrams, or use-case content to the index — keep that on the use-case pages themselves.
- Each use-case page should follow this section order: lede, "登場人物と責務" (actors and responsibilities) table, data model, processing flow with mocks and API/DB details, data ownership, sequence, lifecycle, "主要エラー" (errors) table, and "判断指針" (out-of-spec guidance) list. The 3-column-feel layout per step (mock + API call + DB data) is the established pattern; do not collapse it.
- Mocks under `development/public/mocks/` must be pure UI. They must not contain DB table names, API endpoints, multipart field names, internal state variable names (e.g., `fetched_policy.updated_at`, `installed_by_companion`), data message type names, or implementation log text. Any such information belongs in the Astro page next to the mock, not inside the mock itself.
- Mocks must show only what the operator or device user actually sees on that screen at that moment. Do not include impact summaries ("this affects 12 devices"), aggregate counts that the real UI would not surface, hand-wavy explanation paragraphs about what will happen next, design rationale notes, or side-panel "checklists" of internal steps. When uncertain, prefer a minimal screen that just confirms the action and links back to a list — fewer panels is better than a panel filled with synthesized commentary.
- Mock screen contents must stay consistent with the current design described on the Astro page. If you change the design (e.g., DevicePolicy no longer references release IDs), update the related mocks in the same pass so they do not show stale relationships, columns, or impact descriptions.
- Error cases and judgment guidance belong on the use-case page that owns the flow. Don't create separate error or guidance pages.
- The docs layout should not use a hero/header band for these design pages. The main content should use the available screen width, and the sidebar label for the root page is `TOP`.

## Data Modeling

- Prefer identity tables to contain only stable identifiers and facts intrinsic to that identity.
- Keep factual timestamps such as `created_at`, `issued_at`, `registered_at`, and `expires_at` when they record events that happened.
- Avoid storing mutable state flags such as `enabled`, `active`, `inactive`, `status`, or similar columns on core identity tables unless there is a strong domain reason.
- When a concept has a different meaning, lifecycle, owner, or update trigger, model it as a separate table related to the identity table instead of adding columns to the identity table.
- Examples for devices: keep `devices` focused on device identity such as `identifier` and registration facts. Store profile data in `device_profiles`, credentials in `device_credentials`, and FCM tokens in `fcm_push_tokens`.
- For one-time registration records, prefer deleting the registration token after successful registration. If the same token is used again, determine completion from the existence of the resulting identity record, such as `devices.identifier`, instead of preserving a consumed status flag or creating a separate usage table by default.
- This follows the identity modeling approach described in "Identifying User Identity" (https://speakerdeck.com/moro/identifying-user-idenity): name and model the domain identity explicitly, and split different concepts instead of compressing them into a generic stateful record.

## Device Registration Design

- Device registration in this project is not Android Management API managed-device enrollment. Do not describe AMAPI enrollment as the registration flow.
- Use the term `DeviceRegistrationToken`. Do not call it an enrollment token, because that conflicts with AMAPI terminology.
- The server issues a DeviceRegistrationToken `identifier` and a registration secret. The registration link or QR code carries those values.
- The app/server device identity is `devices.identifier`, not `device_id`. In the current design, `devices.identifier` is created from the DeviceRegistrationToken `identifier` value when registration succeeds.
- Do not use a device-generated random `local_installation_id` as the main identifier in the design.
- The registration link should use a custom scheme deep link such as `apkdist://register-device?...` so the companion app starts directly. Do not center the flow on a browser registration page, because FCM token retrieval requires the app to run.
- The app must not enable the registration action until it has obtained an FCM token and the user has entered the required display name.
- The display name is user-provided, required during registration, changeable later, and stored in `device_profiles`, not in `devices`.
- Do not auto-collect device profile details such as OS, manufacturer, or model unless explicitly requested. The current registration profile is the display name only.
- Store push tokens in `fcm_push_tokens`, not `device_push_tokens`. Upsert by `identifier`; do not describe a separate procedure that marks old tokens inactive unless a later design explicitly requires token history.
- `device_credentials` is separate from `devices` because credential lifecycle and rotation differ from identity lifecycle.
- On successful registration, delete the `device_registration_tokens` row. For later access using the same link, the expected behavior is: token row is absent, `devices.identifier` with the same value exists, so the request is rejected as an already registered link and no `devices`, `device_profiles`, or `fcm_push_tokens` records are changed.
- The device registration page should stop at registration completion and already-registered-link behavior. Do not add initial sync, policy motivation, install-task behavior, or distribution workflow details to this page unless explicitly asked.
- The registration completion mock should show completion clearly and only necessary actions such as close. Do not add a display-name edit action to the completion screen.

## Common Review Pitfalls

- Do not add `enabled`, `status`, `active`, `inactive`, or `CONSUMED` columns to core tables as a shortcut for modeling behavior.
- Do not preserve a consumed registration-token status if deleting the token and checking `devices.identifier` answers the question.
- Do not introduce extra tables just to remember a state that can be inferred from identity existence, such as a registration token usage table, unless the product needs an audit trail.
- Do not mix concepts with different update triggers into `devices`; split profile, credential, FCM token, and group membership data.
- Do not let mock UI imply browser-based registration, automatic profile collection, AMAPI enrollment, or registration before FCM token acquisition.
- Do not let docs drift into implementation internals. The page should explain processing responsibility, data ownership, and observable behavior.

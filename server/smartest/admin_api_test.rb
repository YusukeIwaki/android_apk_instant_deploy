# frozen_string_literal: true

require_relative "test_helper"

ADMIN_API_SAMPLE_APK_PATH = File.expand_path("assets/sample.apk", __dir__)
ADMIN_API_SAME_VERSION_NEW_CHECKSUM_APK_PATH = File.expand_path("assets/same_version_new_checksum.apk", __dir__)
ADMIN_API_INVALID_APK_PATH = __FILE__

def assert_api_error(api_client, response, status, code)
  raise "expected HTTP #{status}, got #{response.status}: #{response.body}" unless response.status == status
  raise "expected error code #{code}, got #{response.body}" unless api_client.error_code(response) == code
end

def create_api_device(api_client, display_name: "Smartest Device")
  identifier = ApkInstantDeploy::Security.registration_identifier
  device_auth_token = ApkInstantDeploy::Security.device_auth_token
  device_identifier = nil
  ApkInstantDeploy::Device.transaction do
    device_identifier = ApkInstantDeploy::DeviceIdentifier.create!(identifier: identifier)
    device = ApkInstantDeploy::Device.create!(device_identifier: device_identifier, registered_at: Time.now.utc)
    ApkInstantDeploy::DeviceProfile.create!(device: device, display_name: display_name)
    ApkInstantDeploy::DeviceCredential.create!(
      device: device,
      token_hmac: ApkInstantDeploy::Security.hmac(device_auth_token),
      issued_at: Time.now.utc
    )
  end
  [device_identifier.identifier, device_auth_token]
end

def create_api_app_and_policy(api_client, install_mode: "FORCE_INSTALLED")
  api_client.upload_apk(ADMIN_API_SAMPLE_APK_PATH)
  app = api_client.fetch_app_by_package_name("com.example.smartest")
  raise "expected uploaded app in admin apps response" unless app
  app_id = app_id_for_package_name(app.fetch("package_name"))

  identifier, device_auth_token = create_api_device(api_client)
  policy = create_api_policy(identifier, [{ app_id: app_id, install_mode: install_mode }])
  [app, identifier, device_auth_token, policy]
end

def app_id_for_package_name(package_name)
  ApkInstantDeploy::App.find_by!(package_name: package_name).id
end

def create_api_policy(identifier, entries)
  device = ApkInstantDeploy::DeviceIdentifier.find_by!(identifier: identifier).device
  policy = nil
  revision = nil
  ApkInstantDeploy::DevicePolicy.transaction do
    policy = device.device_policy || ApkInstantDeploy::DevicePolicy.create!(device: device)
    revision = policy.device_policy_revisions.create!
    entries.each do |entry|
      ApkInstantDeploy::DevicePolicyAppEntry.create!(
        device_policy_revision: revision,
        app_id: entry.fetch(:app_id),
        install_mode: entry.fetch(:install_mode)
      )
    end
    policy.update!(current_revision: revision)
  end

  {
    "identifier" => identifier,
    "current_revision" => { "id" => revision.id },
    "updated_at" => policy.updated_at.utc.iso8601
  }
end

test("OpenAPI Admin API は認証エラーを返す") do |api_client:|
  assert_api_error(api_client, api_client.admin_get("/admin/apps", auth: :none), 401, "ADMIN_AUTH_REQUIRED")
  assert_api_error(api_client, api_client.admin_get("/admin/apps", auth: :invalid), 401, "ADMIN_AUTH_INVALID")
end

test("OpenAPI Admin API の artifact upload / app list / release delete を確認する") do |api_client:|
  missing_file = api_client.upload_without_file
  assert_api_error(api_client, missing_file, 400, "FILE_REQUIRED")

  invalid_apk = api_client.upload_apk_response(ADMIN_API_INVALID_APK_PATH)
  assert_api_error(api_client, invalid_apk, 400, "INVALID_APK")

  artifact_response = api_client.upload_apk_response(ADMIN_API_SAMPLE_APK_PATH)
  raise "expected artifact upload success: #{artifact_response.status} #{artifact_response.body}" unless artifact_response.status == 201
  artifact = api_client.json(artifact_response).fetch("artifact")
  raise "expected artifact filename" unless artifact.fetch("filename") == "sample.apk"

  duplicate = api_client.upload_apk_response(ADMIN_API_SAMPLE_APK_PATH)
  assert_api_error(api_client, duplicate, 400, "ARTIFACT_ALREADY_EXISTS")

  apps_response = api_client.admin_get("/admin/apps")
  raise "expected app list success: #{apps_response.status} #{apps_response.body}" unless apps_response.ok?
  app = api_client.json(apps_response).fetch("apps").find { |candidate| candidate.fetch("package_name") == "com.example.smartest" }
  raise "expected uploaded app in app list" unless app
  raise "expected app list to hide internal id" if app.key?("id")
  raise "expected app list to omit releases" if app.key?("releases")
  releases = api_client.fetch_releases(app.fetch("package_name"))
  release_id = releases.fetch("releases").first.fetch("id")

  delete_response = api_client.delete_app_release(app.fetch("package_name"), release_id)
  raise "expected release delete 204, got #{delete_response.status}: #{delete_response.body}" unless delete_response.status == 204
  assert_api_error(api_client, api_client.delete_app_release(app.fetch("package_name"), release_id), 404, "APP_NOT_FOUND")
end

test("REST API の APK 更新は関連ポリシー revision を連動更新しない") do |api_client:, fake_fcm_factory:, fcm_deliveries:|
  api_client.upload_apk(ADMIN_API_SAMPLE_APK_PATH)
  app = api_client.fetch_app_by_package_name("com.example.smartest")
  raise "expected uploaded app in admin apps response" unless app

  identifier, = create_api_device(api_client)

  create_api_policy(identifier, [{ app_id: app_id_for_package_name(app.fetch("package_name")), install_mode: "FORCE_INSTALLED" }])

  policy = ApkInstantDeploy::DeviceIdentifier.find_by!(identifier: identifier).device.device_policy
  initial_revision_id = policy.current_revision_id
  initial_revision_count = policy.device_policy_revisions.count

  original_fcm_enabled = ApkInstantDeploy::Server.settings.fcm_push_enabled
  original_fcm_factory = ApkInstantDeploy::Server.settings.fcm_push_client_factory
  ApkInstantDeploy::Server.set :fcm_push_enabled, true
  ApkInstantDeploy::Server.set :fcm_push_client_factory, fake_fcm_factory
  begin
    api_client.upload_apk(ADMIN_API_SAME_VERSION_NEW_CHECKSUM_APK_PATH)
  ensure
    ApkInstantDeploy::Server.set :fcm_push_enabled, original_fcm_enabled
    ApkInstantDeploy::Server.set :fcm_push_client_factory, original_fcm_factory
  end

  policy.reload
  raise "expected policy revision to stay unchanged" unless policy.current_revision_id == initial_revision_id
  raise "expected no additional policy revision" unless policy.device_policy_revisions.count == initial_revision_count
  raise "expected no fake FCM deliveries, got #{fcm_deliveries.length}" unless fcm_deliveries.empty?
end

test("Admin API で app 一覧を取得し、指定 app を含む policy revision を touch できる") do |api_client:|
  api_client.upload_apk(ADMIN_API_SAMPLE_APK_PATH)

  app = api_client.fetch_app_by_package_name("com.example.smartest")
  raise "expected uploaded app in admin apps response" unless app
  expect(app.fetch("display_name")).to eq("Smartest Demo")

  identifier, = create_api_device(api_client)

  create_api_policy(identifier, [{ app_id: app_id_for_package_name(app.fetch("package_name")), install_mode: "FORCE_INSTALLED" }])

  policy = ApkInstantDeploy::DeviceIdentifier.find_by!(identifier: identifier).device.device_policy
  initial_revision_id = policy.current_revision_id

  touched_policy = api_client.touch_policies(app.fetch("package_name")).fetch("device_policies").first
  raise "expected touched policy identifier" unless touched_policy.fetch("identifier") == identifier
  raise "expected touched policy display_name" unless touched_policy.fetch("display_name") == "Smartest Device"

  policy.reload
  raise "expected policy revision to be touched" unless policy.current_revision_id != initial_revision_id
end

test("OpenAPI Admin policy touch API の成功とエラーを確認する") do |api_client:|
  app, identifier, = create_api_app_and_policy(api_client)

  assert_api_error(
    api_client,
    api_client.admin_post_json("/admin/apps/com.example.missing/touch_policies"),
    404,
    "APP_NOT_FOUND"
  )

  response = api_client.admin_post_json("/admin/apps/#{app.fetch("package_name")}/touch_policies")
  raise "expected touch success: #{response.status} #{response.body}" unless response.ok?
  touched = api_client.json(response).fetch("device_policies").first
  raise "expected touched policy identifier" unless touched.fetch("identifier") == identifier
end

test("OpenAPI Android release artifact URL / policy / FCM / notifications API の成功とエラーを確認する") do |api_client:|
  app, _identifier, device_auth_token = create_api_app_and_policy(api_client)
  release_id = api_client.fetch_releases(app.fetch("package_name")).fetch("releases").first.fetch("id")

  assert_api_error(api_client, api_client.device_get("/api/releases/#{release_id}/artifact_url"), 401, "DEVICE_AUTH_REQUIRED")
  artifact_url_response = api_client.device_get("/api/releases/#{release_id}/artifact_url", device_auth_token: device_auth_token)
  raise "expected artifact URL success: #{artifact_url_response.status} #{artifact_url_response.body}" unless artifact_url_response.ok?
  raise "expected artifact_url key" unless api_client.json(artifact_url_response).fetch("release_artifact_url").fetch("artifact_url")

  assert_api_error(api_client, api_client.device_get("/api/devices/me/policy"), 401, "DEVICE_AUTH_REQUIRED")
  policy_response = api_client.device_get("/api/devices/me/policy", device_auth_token: device_auth_token)
  raise "expected device policy success: #{policy_response.status} #{policy_response.body}" unless policy_response.ok?

  assert_api_error(api_client, api_client.device_put_json("/api/devices/me/fcm_push_token", fcm_push_token: ""), 401, "DEVICE_AUTH_REQUIRED")
  assert_api_error(
    api_client,
    api_client.device_put_json("/api/devices/me/fcm_push_token", { fcm_push_token: "" }, device_auth_token: device_auth_token),
    400,
    "FCM_PUSH_TOKEN_REQUIRED"
  )
  fcm_response = api_client.device_put_json("/api/devices/me/fcm_push_token", { fcm_push_token: "new-fcm" }, device_auth_token: device_auth_token)
  raise "expected FCM update success: #{fcm_response.status} #{fcm_response.body}" unless fcm_response.ok?

  device = ApkInstantDeploy::DeviceCredential.find_by!(token_hmac: ApkInstantDeploy::Security.hmac(device_auth_token)).device
  app_record = ApkInstantDeploy::App.find_by!(package_name: app.fetch("package_name"))
  ApkInstantDeploy::Notification.create!(
    device: device,
    app: app_record,
    kind: "INSTALL_PERMISSION_REQUIRED",
    title: "Install required app",
    body: "Please install the required app."
  )

  assert_api_error(api_client, api_client.device_get("/api/notifications"), 401, "DEVICE_AUTH_REQUIRED")
  notifications_response = api_client.device_get("/api/notifications", device_auth_token: device_auth_token)
  raise "expected notifications success: #{notifications_response.status} #{notifications_response.body}" unless notifications_response.ok?
  notification = api_client.json(notifications_response).fetch("notifications").first
  raise "expected notification kind" unless notification.fetch("kind") == "INSTALL_PERMISSION_REQUIRED"

  delete_response = api_client.delete_app_release(app.fetch("package_name"), release_id)
  raise "expected release delete 204, got #{delete_response.status}: #{delete_response.body}" unless delete_response.status == 204
  assert_api_error(
    api_client,
    api_client.device_get("/api/releases/#{release_id}/artifact_url", device_auth_token: device_auth_token),
    404,
    "RELEASE_NOT_FOUND"
  )
end

test("OpenAPI Android policy sync result API の成功・冪等性・エラーを確認する") do |api_client:|
  app, _identifier, device_auth_token, policy = create_api_app_and_policy(api_client)
  revision_id = policy.fetch("current_revision").fetch("id")
  other_identifier, = create_api_device(api_client)
  other_policy = create_api_policy(
    other_identifier,
    [{ app_id: app_id_for_package_name(app.fetch("package_name")), install_mode: "FORCE_INSTALLED" }]
  )
  other_revision_id = other_policy.fetch("current_revision").fetch("id")
  now = Time.now.utc.iso8601
  valid_payload = {
    device_policy_revision: { id: revision_id },
    fetched_policy_updated_at: now,
    applied_policy_updated_at: now,
    actions: [
      {
        package_name: "com.example.smartest",
        action: "INSTALL",
        route: "AMAPI_CUSTOM_APP",
        result: "INSTALLED"
      }
    ]
  }

  assert_api_error(api_client, api_client.device_post_json("/api/devices/me/policy_sync_results", valid_payload), 401, "DEVICE_AUTH_REQUIRED")
  assert_api_error(
    api_client,
    api_client.device_post_json("/api/devices/me/policy_sync_results", { actions: [] }, device_auth_token: device_auth_token),
    400,
    "DEVICE_POLICY_REVISION_REQUIRED"
  )
  assert_api_error(
    api_client,
    api_client.device_post_json("/api/devices/me/policy_sync_results", valid_payload.merge(actions: [{ package_name: "" }]), device_auth_token: device_auth_token),
    400,
    "ACTIONS_INVALID"
  )
  assert_api_error(
    api_client,
    api_client.device_post_json("/api/devices/me/policy_sync_results", valid_payload.merge(device_policy_revision: { id: other_revision_id }), device_auth_token: device_auth_token),
    404,
    "DEVICE_POLICY_REVISION_NOT_FOUND"
  )
  assert_api_error(
    api_client,
    api_client.device_post_json("/api/devices/me/policy_sync_results", valid_payload.merge(fetched_policy_updated_at: "not-a-time"), device_auth_token: device_auth_token),
    400,
    "INVALID_REQUEST"
  )

  create_response = api_client.device_post_json("/api/devices/me/policy_sync_results", valid_payload, device_auth_token: device_auth_token)
  raise "expected sync result create 201, got #{create_response.status}: #{create_response.body}" unless create_response.status == 201
  duplicate_response = api_client.device_post_json("/api/devices/me/policy_sync_results", valid_payload, device_auth_token: device_auth_token)
  raise "expected sync result duplicate 200, got #{duplicate_response.status}: #{duplicate_response.body}" unless duplicate_response.status == 200
end

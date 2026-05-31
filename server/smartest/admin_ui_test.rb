# frozen_string_literal: true

require_relative "test_helper"

SAMPLE_APK_PATH = File.expand_path("assets/sample.apk", __dir__)
SAME_VERSION_NEW_CHECKSUM_APK_PATH = File.expand_path("assets/same_version_new_checksum.apk", __dir__)
RESOURCE_LABEL_APK_PATH = File.expand_path("assets/binary_resource_label.apk", __dir__)
SIGNED_TEXT_MANIFEST_APK_PATH = File.expand_path("assets/signed_text_manifest.apk", __dir__)

def issue_registration_token(page)
  page.goto("/devices/new")
  page.get_by_role("button", name: "Issue token").click
  page.wait_for_url(%r{/devices$})
  page.locator("dd code").first.text_content.strip
end

# @type [Playwright::Page] admin_page
test("ログイン後のダッシュボードに見出しと統計カードが表示される") do |admin_page:|
  expect(admin_page.get_by_role("heading", name: "Dashboard")).to be_visible
  expect(admin_page.get_by_text("Registered devices")).to be_visible
  expect(admin_page.get_by_text("Pending registration tokens")).to be_visible
end

test("登録トークン発行画面で QR コードと Copy ボタンが表示される") do |admin_page:|
  admin_page.goto("/apps/new")
  admin_page.locator('input[type="file"]').set_input_files(SAMPLE_APK_PATH)
  admin_page.get_by_role("button", name: "Upload").click
  expect(admin_page.get_by_text("Uploaded com.example.smartest versionCode=1.")).to be_visible

  admin_page.goto("/devices/new")
  admin_page.get_by_role("button", name: "Issue token").click
  admin_page.wait_for_url(%r{/devices$})

  expect(admin_page.get_by_role("heading", name: "Registration token issued")).to be_visible
  expect(admin_page.locator("#qr-code svg")).to be_visible
  expect(admin_page.get_by_role("button", name: "Copy", exact: true)).to be_visible
  expect(admin_page.get_by_role("heading", name: "AMAPI policy JSON")).to be_visible
  expect(admin_page.get_by_text('"installType": "FORCE_INSTALLED"')).to be_visible
  expect(admin_page.get_by_text('"roleType": "COMPANION_APP"')).to be_visible
  expect(admin_page.get_by_text('"installType": "CUSTOM"')).to be_visible
  expect(admin_page.get_by_text('"packageName": "com.example.smartest"')).to be_visible
  expect(admin_page.get_by_text('"signingKeyCerts"')).to be_visible
  expect(admin_page.get_by_text('"managedConfiguration"')).to be_visible
  expect(admin_page.get_by_role("button", name: "Copy AMAPI policy JSON")).to be_visible

  policy_json = JSON.parse(admin_page.locator("#amapi-policy-value").text_content)
  custom_entry = policy_json.fetch("applications").find { |entry| entry.fetch("packageName") == "com.example.smartest" }
  raise "expected com.example.smartest CUSTOM entry" unless custom_entry&.fetch("installType") == "CUSTOM"
  cert = custom_entry.fetch("signingKeyCerts").first.fetch("signingKeyCertFingerprintSha256")
  raise "expected package-specific signing certificate placeholder for UNKNOWN fixture" unless cert == "<signing_cert_sha256_for_com.example.smartest>"
end

test("発行済みトークンが Pending registration tokens 一覧に表示される") do |admin_page:|
  identifier = issue_registration_token(admin_page)

  admin_page.goto("/devices")
  expect(admin_page.get_by_role("heading", name: "Pending registration tokens")).to be_visible
  expect(admin_page.get_by_text(identifier)).to be_visible
  expect(admin_page.get_by_role("button", name: "Revoke")).to be_visible
end

test("Pending registration tokens から Revoke すると一覧から消える") do |admin_page:|
  identifier = issue_registration_token(admin_page)

  admin_page.goto("/devices")
  expect(admin_page.get_by_text(identifier)).to be_visible

  admin_page.on("dialog", ->(dialog) { dialog.accept })
  admin_page.get_by_role("button", name: "Revoke").click

  expect(admin_page.get_by_text("Registration token was revoked.")).to be_visible
  expect(admin_page.get_by_text(identifier)).not_to be_visible
end

test("APK アップロードでアプリと release が作成される") do |admin_page:|
  admin_page.goto("/apps/new")
  admin_page.locator('input[type="file"]').set_input_files(SAMPLE_APK_PATH)
  admin_page.get_by_role("button", name: "Upload").click

  expect(admin_page.get_by_text("Uploaded com.example.smartest versionCode=1.")).to be_visible
  expect(admin_page.get_by_role("heading", name: "com.example.smartest")).to be_visible
  expect(admin_page.get_by_role("cell", name: "1.0.0", exact: true)).to be_visible
end

test("APK アップロードで app_name リソースが display name として表示される") do |admin_page:|
  admin_page.goto("/apps/new")
  admin_page.locator('input[type="file"]').set_input_files(RESOURCE_LABEL_APK_PATH)
  admin_page.get_by_role("button", name: "Upload").click

  expect(admin_page.get_by_text("Uploaded com.example.resource_label versionCode=42.")).to be_visible
  expect(admin_page.get_by_role("heading", name: "com.example.resource_label")).to be_visible
  expect(admin_page.get_by_text("Resource Label Demo")).to be_visible
end

test("Apps 一覧に AMAPI ApplicationPolicy 用 JSON が表示される") do |admin_page:|
  admin_page.goto("/apps/new")
  admin_page.locator('input[type="file"]').set_input_files(SIGNED_TEXT_MANIFEST_APK_PATH)
  admin_page.get_by_role("button", name: "Upload").click
  expect(admin_page.get_by_text("Uploaded com.example.signed versionCode=1.")).to be_visible

  admin_page.goto("/apps")
  expect(admin_page.get_by_role("heading", name: "AMAPI ApplicationPolicy JSON")).to be_visible
  application_policies = JSON.parse(admin_page.locator("#application-policy-value").text_content)
  signed_app = application_policies.find { |entry| entry.fetch("packageName") == "com.example.signed" }
  raise "expected com.example.signed signingKeyCerts entry" unless signed_app
  raise "latestRelease is not an AMAPI ApplicationPolicy field" if signed_app.key?("latestRelease")

  # AMAPI requires the base64-encoded raw 32-byte SHA-256, not hex. The DB stores
  # hex (5d4d46...02cf); the policy JSON emits its base64 form.
  cert = signed_app.fetch("signingKeyCerts").first.fetch("signingKeyCertFingerprintSha256")
  expect(cert).to eq("XU1GNxOmE20Ad7o/smd+5z78Ve3EJH1Zdz6FFQ4UAs8=")
end

test("同一 checksum の APK 再アップロードは拒否される") do |admin_page:|
  admin_page.goto("/apps/new")
  admin_page.locator('input[type="file"]').set_input_files(SAMPLE_APK_PATH)
  admin_page.get_by_role("button", name: "Upload").click
  expect(admin_page.get_by_text("Uploaded com.example.smartest versionCode=1.")).to be_visible

  admin_page.goto("/apps/new")
  admin_page.locator('input[type="file"]').set_input_files(SAMPLE_APK_PATH)
  admin_page.get_by_role("button", name: "Upload").click

  expect(admin_page.get_by_text("The exact same APK artifact has already been uploaded.")).to be_visible
end

test("同一 package_name と versionCode でも checksum が異なれば release が追加される") do |admin_page:|
  admin_page.goto("/apps/new")
  admin_page.locator('input[type="file"]').set_input_files(SAMPLE_APK_PATH)
  admin_page.get_by_role("button", name: "Upload").click
  expect(admin_page.get_by_text("Uploaded com.example.smartest versionCode=1.")).to be_visible

  admin_page.goto("/apps/new")
  admin_page.locator('input[type="file"]').set_input_files(SAME_VERSION_NEW_CHECKSUM_APK_PATH)
  admin_page.get_by_role("button", name: "Upload").click

  expect(admin_page.get_by_text("Uploaded com.example.smartest versionCode=1.")).to be_visible
  raise "expected two releases" unless admin_page.locator("tbody tr").count == 2
end

test("管理画面の APK 更新で関連ポリシー revision が更新され FCM が送信される") do |admin_page:, api_client:, fake_fcm_factory:, fcm_deliveries:|
  admin_page.goto("/apps/new")
  admin_page.locator('input[type="file"]').set_input_files(SAMPLE_APK_PATH)
  admin_page.get_by_role("button", name: "Upload").click
  expect(admin_page.get_by_text("Uploaded com.example.smartest versionCode=1.")).to be_visible

  identifier = issue_registration_token(admin_page)
  secret = admin_page.locator("dd code").nth(1).text_content.strip
  device_auth_token = api_client.register_device(identifier: identifier, registration_secret: secret)

  admin_page.goto("/policies/#{identifier}/edit")
  row = admin_page.locator('tr:has-text("com.example.smartest")')
  row.locator('input[name="include[]"]').check
  row.locator('label:has-text("FORCE_INSTALLED")').click
  admin_page.get_by_role("button", name: "Publish new revision").click
  expect(admin_page.get_by_text("Policy updated for #{identifier}.")).to be_visible

  device = ApkInstantDeploy::DeviceIdentifier.find_by!(identifier: identifier).device
  policy = device.device_policy
  initial_revision_id = policy.current_revision_id
  initial_revision_count = policy.device_policy_revisions.count
  initial_latest_release_id = ApkInstantDeploy::App.find_by!(package_name: "com.example.smartest").releases.order(version_code: :desc, id: :desc).first.id

  original_fcm_enabled = ApkInstantDeploy::Server.settings.fcm_push_enabled
  original_fcm_factory = ApkInstantDeploy::Server.settings.fcm_push_client_factory
  ApkInstantDeploy::Server.set :fcm_push_enabled, true
  ApkInstantDeploy::Server.set :fcm_push_client_factory, fake_fcm_factory
  begin
    admin_page.goto("/apps/new")
    admin_page.locator('input[type="file"]').set_input_files(SAME_VERSION_NEW_CHECKSUM_APK_PATH)
    admin_page.get_by_role("button", name: "Upload").click
  ensure
    ApkInstantDeploy::Server.set :fcm_push_enabled, original_fcm_enabled
    ApkInstantDeploy::Server.set :fcm_push_client_factory, original_fcm_factory
  end

  expect(admin_page.get_by_text("Uploaded com.example.smartest versionCode=1.")).to be_visible
  policy.reload
  raise "expected policy revision to be refreshed" unless policy.current_revision_id != initial_revision_id
  raise "expected one additional policy revision" unless policy.device_policy_revisions.count == initial_revision_count + 1
  raise "expected one fake FCM delivery, got #{fcm_deliveries.length}" unless fcm_deliveries.length == 1
  raise "expected decrypted FCM token" unless fcm_deliveries.first.fetch(:token) == "smartest-fcm"
  raise "expected POLICY_UPDATED payload" unless fcm_deliveries.first.fetch(:payload).fetch(:type) == "POLICY_UPDATED"

  latest_release_id = api_client.fetch_device_policy(device_auth_token).fetch("entries").first.fetch("install").fetch("release").fetch("id")
  raise "expected latest release to change" unless latest_release_id != initial_latest_release_id
end

test("ポリシー編集で revision が発行され、デバイス詳細に反映される") do |admin_page:, api_client:, fake_fcm_factory:, fcm_deliveries:|
  # 1) アプリを登録
  admin_page.goto("/apps/new")
  admin_page.locator('input[type="file"]').set_input_files(SAMPLE_APK_PATH)
  admin_page.get_by_role("button", name: "Upload").click
  expect(admin_page.get_by_text("Uploaded com.example.smartest versionCode=1.")).to be_visible

  # 2) 端末を登録（API 経由で簡略化）
  identifier = issue_registration_token(admin_page)
  secret = admin_page.locator("dd code").nth(1).text_content.strip

  device_auth_token = api_client.register_device(identifier: identifier, registration_secret: secret)

  # 3) ポリシー編集画面で対象アプリを FORCE_INSTALLED に設定
  admin_page.goto("/policies/#{identifier}/edit")
  row = admin_page.locator('tr:has-text("com.example.smartest")')
  row.locator('input[name="include[]"]').check
  row.locator('label:has-text("FORCE_INSTALLED")').click
  original_fcm_enabled = ApkInstantDeploy::Server.settings.fcm_push_enabled
  original_fcm_factory = ApkInstantDeploy::Server.settings.fcm_push_client_factory

  ApkInstantDeploy::Server.set :fcm_push_enabled, true
  ApkInstantDeploy::Server.set :fcm_push_client_factory, fake_fcm_factory
  begin
    admin_page.get_by_role("button", name: "Publish new revision").click
  ensure
    ApkInstantDeploy::Server.set :fcm_push_enabled, original_fcm_enabled
    ApkInstantDeploy::Server.set :fcm_push_client_factory, original_fcm_factory
  end

  expect(admin_page.get_by_text("Policy updated for #{identifier}.")).to be_visible
  raise "expected one fake FCM delivery, got #{fcm_deliveries.length}" unless fcm_deliveries.length == 1
  raise "expected decrypted FCM token" unless fcm_deliveries.first.fetch(:token) == "smartest-fcm"
  raise "expected POLICY_UPDATED payload" unless fcm_deliveries.first.fetch(:payload).fetch(:type) == "POLICY_UPDATED"

  policy_entry = api_client.fetch_device_policy(device_auth_token).fetch("entries").first
  expect(policy_entry.fetch("app").fetch("display_name")).to eq("Smartest Demo")

  # 4) デバイス詳細にポリシーが反映されている
  admin_page.goto("/devices/#{identifier}")
  expect(admin_page.get_by_role("heading", name: "Current policy")).to be_visible
  expect(admin_page.get_by_text("com.example.smartest")).to be_visible
  expect(admin_page.locator('.badge:has-text("FORCE_INSTALLED")')).to be_visible
end

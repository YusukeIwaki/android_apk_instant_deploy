# frozen_string_literal: true

require_relative "test_helper"

SAMPLE_APK_PATH = File.expand_path("assets/sample.apk", __dir__)
SAME_VERSION_NEW_CHECKSUM_APK_PATH = File.expand_path("assets/same_version_new_checksum.apk", __dir__)
RESOURCE_LABEL_APK_PATH = File.expand_path("assets/binary_resource_label.apk", __dir__)

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
  admin_page.goto("/devices/new")
  admin_page.get_by_role("button", name: "Issue token").click
  admin_page.wait_for_url(%r{/devices$})

  expect(admin_page.get_by_role("heading", name: "Registration token issued")).to be_visible
  expect(admin_page.locator("#qr-code svg")).to be_visible
  expect(admin_page.get_by_role("button", name: "Copy", exact: true)).to be_visible
  expect(admin_page.get_by_role("heading", name: "AMAPI ApplicationPolicy")).to be_visible
  expect(admin_page.get_by_text('"installType": "FORCE_INSTALLED"')).to be_visible
  expect(admin_page.get_by_text('"managedConfiguration"')).to be_visible
  expect(admin_page.get_by_role("button", name: "Copy ApplicationPolicy JSON")).to be_visible
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

test("ポリシー編集で revision が発行され、デバイス詳細に反映される") do |admin_page:|
  # 1) アプリを登録
  admin_page.goto("/apps/new")
  admin_page.locator('input[type="file"]').set_input_files(SAMPLE_APK_PATH)
  admin_page.get_by_role("button", name: "Upload").click
  expect(admin_page.get_by_text("Uploaded com.example.smartest versionCode=1.")).to be_visible

  # 2) 端末を登録（API 経由で簡略化）
  identifier = issue_registration_token(admin_page)
  secret = admin_page.locator("dd code").nth(1).text_content.strip

  api_response = admin_page.context.request.post(
    "/api/devices",
    headers: { "Content-Type" => "application/json" },
    data: JSON.generate(
      identifier: identifier,
      registration_secret: secret,
      display_name: "Smartest Device",
      fcm_push_token: "smartest-fcm"
    )
  )
  raise "device registration failed: #{api_response.status} #{api_response.body}" unless api_response.ok?
  device_auth_token = JSON.parse(api_response.body).fetch("device").fetch("device_auth_token")

  # 3) ポリシー編集画面で対象アプリを FORCE_INSTALLED に設定
  admin_page.goto("/policies/#{identifier}/edit")
  row = admin_page.locator('tr:has-text("com.example.smartest")')
  row.locator('input[name="include[]"]').check
  row.locator('label:has-text("FORCE_INSTALLED")').click
  fcm_deliveries = []
  original_fcm_enabled = ApkInstantDeploy::Server.settings.fcm_push_enabled
  original_fcm_factory = ApkInstantDeploy::Server.settings.fcm_push_client_factory
  fake_fcm_factory = Struct.new(:deliveries) do
    def call(token, logger: nil)
      Struct.new(:token, :deliveries) do
        def send_message(payload)
          deliveries << { token: token, payload: payload }
          { "name" => "fake-fcm-message" }
        end
      end.new(token, deliveries)
    end
  end.new(fcm_deliveries)

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

  policy_response = admin_page.context.request.get(
    "/api/devices/me/policy",
    headers: { "Authorization" => "Bearer #{device_auth_token}" }
  )
  raise "policy fetch failed: #{policy_response.status} #{policy_response.body}" unless policy_response.ok?
  policy_entry = JSON.parse(policy_response.body).fetch("entries").first
  expect(policy_entry.fetch("app").fetch("display_name")).to eq("Smartest Demo")

  # 4) デバイス詳細にポリシーが反映されている
  admin_page.goto("/devices/#{identifier}")
  expect(admin_page.get_by_role("heading", name: "Current policy")).to be_visible
  expect(admin_page.get_by_text("com.example.smartest")).to be_visible
  expect(admin_page.locator('.badge:has-text("FORCE_INSTALLED")')).to be_visible
end

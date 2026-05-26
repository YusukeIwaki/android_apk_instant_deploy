# frozen_string_literal: true

require_relative "test_helper"

SAMPLE_APK_PATH = File.expand_path("files/sample.apk", __dir__)

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
  expect(admin_page.get_by_role("button", name: "Copy")).to be_visible
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

  # 3) ポリシー編集画面で対象アプリを FORCE_INSTALLED に設定
  admin_page.goto("/policies/#{identifier}/edit")
  row = admin_page.locator('tr:has-text("com.example.smartest")')
  row.locator('input[name="include[]"]').check
  row.locator('label:has-text("FORCE_INSTALLED")').click
  admin_page.get_by_role("button", name: "Publish new revision").click

  expect(admin_page.get_by_text("Policy updated for #{identifier}.")).to be_visible

  # 4) デバイス詳細にポリシーが反映されている
  admin_page.goto("/devices/#{identifier}")
  expect(admin_page.get_by_role("heading", name: "Current policy")).to be_visible
  expect(admin_page.get_by_text("com.example.smartest")).to be_visible
  expect(admin_page.locator('.badge:has-text("FORCE_INSTALLED")')).to be_visible
end

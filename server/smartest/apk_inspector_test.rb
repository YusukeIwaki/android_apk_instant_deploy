# frozen_string_literal: true

require_relative "test_helper"
require_relative "../lib/apk_inspector"

def inspect_apk_asset(filename)
  original_path = ENV["PATH"]
  original_aapt_path = ENV["AAPT_PATH"]
  original_apksigner_path = ENV["APKSIGNER_PATH"]

  ENV["PATH"] = ""
  ENV.delete("AAPT_PATH")
  ENV.delete("APKSIGNER_PATH")
  ApkInstantDeploy::ApkInspector.inspect(File.expand_path("assets/#{filename}", __dir__))
ensure
  ENV["PATH"] = original_path
  ENV["AAPT_PATH"] = original_aapt_path
  ENV["APKSIGNER_PATH"] = original_apksigner_path
end

test("extracts a literal application label from a text manifest APK") do
  metadata = inspect_apk_asset("text_literal_label.apk")

  expect(metadata.package_name).to eq("com.example.smartest")
  expect(metadata.version_code).to eq(1)
  expect(metadata.version_name).to eq("1.0.0")
  expect(metadata.display_name).to eq("Smartest Demo")
end

test("falls back to package name when the APK has no application label") do
  metadata = inspect_apk_asset("text_no_label.apk")

  expect(metadata.package_name).to eq("com.example.no_label")
  expect(metadata.version_code).to eq(7)
  expect(metadata.version_name).to eq("7.0.0")
  expect(metadata.display_name).to eq("com.example.no_label")
end

test("resolves application label resource references from a binary manifest APK") do
  metadata = inspect_apk_asset("binary_resource_label.apk")

  expect(metadata.package_name).to eq("com.example.resource_label")
  expect(metadata.version_code).to eq(42)
  expect(metadata.version_name).to eq("4.2.0")
  expect(metadata.display_name).to eq("Resource Label Demo")
end

# frozen_string_literal: true

require "smartest/rails"
require "playwright"

ENV["RACK_ENV"] ||= "test"
require_relative "../../app"

module ApkInstantDeploy
  module CleanDatabase
    TABLES = %w[
      device_policy_sync_reports
      device_policy_app_entries
      device_policy_revisions
      device_policies
      fcm_push_tokens
      device_credentials
      device_profiles
      devices
      device_registration_tokens
      device_identifiers
      releases
      artifacts
      app_icons
      app_profiles
      apps
    ].freeze

    module_function

    def truncate!
      ActiveRecord::Base.connection.execute("SET FOREIGN_KEY_CHECKS = 0")
      TABLES.each do |table|
        ActiveRecord::Base.connection.execute("TRUNCATE TABLE #{table}")
      end
      ActiveRecord::Base.connection.execute("SET FOREIGN_KEY_CHECKS = 1")
    end
  end
end

class SinatraServerFixture < Smartest::Fixture
  suite_fixture :sinatra_server do
    port_env = ENV["SMARTEST_RAILS_TEST_SERVER_PORT"]
    server = Smartest::Rails::TestServer.new(
      app: ApkInstantDeploy::Server,
      host: ENV["SMARTEST_RAILS_TEST_SERVER_HOST"],
      port: (port_env && !port_env.empty? ? port_env.to_i : nil)
    )
    server.start
    server.wait_for_ready

    on_teardown do
      server.stop
      server.wait_for_stopped
    end

    server
  end

  suite_fixture :base_url do |sinatra_server:|
    ENV.fetch("SMARTEST_RAILS_BASE_URL", sinatra_server.base_url)
  end

  suite_fixture :browser do
    ws_endpoint = ENV["PLAYWRIGHT_WS_ENDPOINT"]

    if ws_endpoint && !ws_endpoint.empty?
      execution = Playwright.connect_to_browser_server(
        ws_endpoint,
        browser_type: selected_browser_type.to_s
      )
      on_teardown { execution.stop }
      execution.browser
    else
      execution = Playwright.create(
        playwright_cli_executable_path: ENV.fetch(
          "PLAYWRIGHT_CLI_EXECUTABLE_PATH",
          "./node_modules/.bin/playwright"
        )
      )
      on_teardown { execution.stop }
      browser = execution.playwright.public_send(selected_browser_type).launch(**browser_launch_options)
      on_teardown { browser.close }
      browser
    end
  end

  fixture :browser_context do |base_url:, browser:|
    context = browser.new_context(baseURL: base_url)
    on_teardown { context.close }
    context
  end

  fixture :page do |browser_context:|
    page = browser_context.new_page
    on_teardown { page.close }
    page
  end

  fixture :admin_page do |page:|
    page.goto("/login")
    page.get_by_label("Admin token").fill(ENV.fetch("ADMIN_TOKEN", "dev-admin-token"))
    page.get_by_role("button", name: "Sign in").click
    page.wait_for_url(%r{/$})
    page
  end

  private

  def selected_browser_type
    case ENV.fetch("BROWSER", "chromium")
    when "firefox"
      :firefox
    when "webkit"
      :webkit
    else
      :chromium
    end
  end

  def browser_launch_options
    options = {}
    options[:headless] = !%w[0 false].include?(ENV.fetch("HEADLESS", "true"))
    slow_mo = ENV.fetch("SLOW_MO", "0").to_i
    options[:slowMo] = slow_mo if slow_mo.positive?
    options
  end
end

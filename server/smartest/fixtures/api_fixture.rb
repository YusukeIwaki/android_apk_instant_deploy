# frozen_string_literal: true

require "faraday"
require "faraday/multipart"
require "uri"

SmartestApiResponse = Struct.new(:status, :body, :headers, keyword_init: true) do
  def ok?
    status >= 200 && status < 300
  end
end

class SmartestApiClient
  def initialize(base_url)
    @connection = Faraday.new(url: base_url) do |faraday|
      faraday.request :multipart
      faraday.adapter Faraday.default_adapter
    end
  end

  def json(response)
    JSON.parse(response.body)
  end

  def error_code(response)
    json(response).fetch("error").fetch("code")
  end

  def admin_get(path, auth: :valid)
    request(:get, path, headers: admin_headers_for(auth))
  end

  def admin_post_json(path, payload = nil, auth: :valid, raw_body: nil, **fields)
    payload = fields if payload.nil? && !fields.empty?
    request(
      :post,
      path,
      headers: admin_headers_for(auth).merge("Content-Type" => "application/json"),
      body: raw_body || JSON.generate(payload || {})
    )
  end

  def admin_delete(path, auth: :valid)
    request(:delete, path, headers: admin_headers_for(auth))
  end

  def post_json(path, payload = nil, raw_body: nil, **fields)
    payload = fields if payload.nil? && !fields.empty?
    request(
      :post,
      path,
      headers: { "Content-Type" => "application/json" },
      body: raw_body || JSON.generate(payload || {})
    )
  end

  def device_get(path, device_auth_token: nil)
    request(:get, path, headers: device_headers(device_auth_token))
  end

  def device_post_json(path, payload = nil, device_auth_token: nil, raw_body: nil, **fields)
    payload = fields if payload.nil? && !fields.empty?
    request(
      :post,
      path,
      headers: device_headers(device_auth_token).merge("Content-Type" => "application/json"),
      body: raw_body || JSON.generate(payload || {})
    )
  end

  def device_put_json(path, payload = nil, device_auth_token: nil, raw_body: nil, **fields)
    payload = fields if payload.nil? && !fields.empty?
    request(
      :put,
      path,
      headers: device_headers(device_auth_token).merge("Content-Type" => "application/json"),
      body: raw_body || JSON.generate(payload || {})
    )
  end

  def upload_apk(path)
    response = upload_apk_response(path)
    raise "artifact upload failed: #{response.status} #{response.body}" unless response.ok?

    response
  end

  def upload_apk_response(path, auth: :valid)
    request(
      :post,
      "/admin/artifacts",
      headers: admin_headers_for(auth),
      body: {
        file: Faraday::Multipart::FilePart.new(path, "application/vnd.android.package-archive", File.basename(path))
      }
    )
  end

  def upload_without_file
    request(:post, "/admin/artifacts", headers: admin_headers)
  end

  def register_device(identifier:, registration_secret:, display_name: "Smartest Device", fcm_push_token: "smartest-fcm")
    response = post_json(
      "/api/devices",
      identifier: identifier,
      registration_secret: registration_secret,
      display_name: display_name,
      fcm_push_token: fcm_push_token
    )
    raise "device registration failed: #{response.status} #{response.body}" unless response.ok?

    json(response).fetch("device").fetch("device_auth_token")
  end

  def fetch_app_by_package_name(package_name)
    response = admin_get("/admin/apps")
    raise "apps request failed: #{response.status} #{response.body}" unless response.ok?

    json(response).fetch("apps").find { |candidate| candidate.fetch("package_name") == package_name }
  end

  def fetch_releases(package_name)
    response = admin_get("/admin/apps/#{URI.encode_www_form_component(package_name)}/releases")
    raise "releases request failed: #{response.status} #{response.body}" unless response.ok?

    json(response)
  end

  def delete_app_release(package_name, release_id)
    admin_delete("/admin/apps/#{URI.encode_www_form_component(package_name)}/releases/#{release_id}")
  end

  def touch_policies(package_name)
    escaped_package_name = URI.encode_www_form_component(package_name)
    response = admin_post_json("/admin/apps/#{escaped_package_name}/touch_policies")
    raise "touch request failed: #{response.status} #{response.body}" unless response.ok?

    json(response)
  end

  def fetch_device_policy(device_auth_token)
    response = device_get("/api/devices/me/policy", device_auth_token: device_auth_token)
    raise "policy fetch failed: #{response.status} #{response.body}" unless response.ok?

    json(response)
  end

  private

  def request(method, path, headers: {}, body: nil)
    response = @connection.public_send(method, path) do |request|
      request.headers.update(headers)
      request.body = body if body
    end
    SmartestApiResponse.new(status: response.status, body: response.body.to_s, headers: response.headers)
  end

  def admin_headers(extra = {})
    { "Authorization" => "Bearer #{ENV.fetch('ADMIN_TOKEN', 'dev-admin-token')}" }.merge(extra)
  end

  def admin_headers_for(auth)
    case auth
    when :valid
      admin_headers
    when :invalid
      { "Authorization" => "Bearer invalid-admin-token" }
    when :none
      {}
    else
      raise ArgumentError, "unknown admin auth mode: #{auth.inspect}"
    end
  end

  def device_headers(device_auth_token)
    return {} if device_auth_token.to_s.empty?

    { "Authorization" => "Bearer #{device_auth_token}" }
  end
end

class ApiFixture < Smartest::Fixture
  fixture :api_client do |sinatra_server:|
    SmartestApiClient.new(sinatra_server.base_url.sub("0.0.0.0", "127.0.0.1"))
  end

  fixture :fcm_deliveries do
    []
  end

  fixture :fake_fcm_factory do |fcm_deliveries:|
    Struct.new(:deliveries) do
      def call(token, logger: nil)
        Struct.new(:token, :deliveries) do
          def send_message(payload)
            deliveries << { token: token, payload: payload }
            { "name" => "fake-fcm-message" }
          end
        end.new(token, deliveries)
      end
    end.new(fcm_deliveries)
  end
end

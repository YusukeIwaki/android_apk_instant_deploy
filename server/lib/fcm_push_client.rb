# frozen_string_literal: true

require "base64"
require "json"
require "net/http"
require "openssl"
require "time"
require "uri"

module ApkInstantDeploy
  class FcmPushClient
    TOKEN_URI = URI("https://oauth2.googleapis.com/token")
    SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    DEFAULT_CREDENTIALS_PATH = File.expand_path("../config/firebase-service-account.json", __dir__)
    TOKEN_CACHE = {}
    TOKEN_CACHE_LOCK = Mutex.new

    class Error < StandardError
      attr_reader :status, :response_body

      def initialize(message, status: nil, response_body: nil)
        super(message)
        @status = status
        @response_body = response_body
      end
    end

    class NotConfigured < Error; end
    attr_writer :logger

    def initialize(fcm_push_token_value, credentials_path: nil, logger: nil)
      @fcm_push_token_value = fcm_push_token_value.to_s
      @credentials_path = credentials_path || self.class.credentials_path
      @logger = logger
    end

    def send_message(payload)
      raise Error, "fcm_push_token_value is required" if @fcm_push_token_value.empty?

      credentials = load_credentials
      access_token = self.class.access_token(credentials, credentials_cache_id)
      project_id = URI.encode_www_form_component(credentials.fetch("project_id"))
      uri = URI("https://fcm.googleapis.com/v1/projects/#{project_id}/messages:send")
      post_json(
        uri,
        {
          message: {
            token: @fcm_push_token_value,
            data: data_payload(payload),
            android: {
              priority: "high"
            }
          }
        },
        bearer_token: access_token
      )
    end

    def self.credentials_path
      [
        ENV["FCM_SERVICE_ACCOUNT_JSON"],
        ENV["GOOGLE_APPLICATION_CREDENTIALS"],
        DEFAULT_CREDENTIALS_PATH
      ].find { |path| path && !path.strip.empty? }
    end

    def self.credentials_content
      content = ENV["FCM_SERVICE_ACCOUNT_JSON_CONTENT"].to_s
      content.strip.empty? ? nil : content
    end

    def self.access_token(credentials, credentials_path)
      cache_key = [credentials_path, credentials.fetch("client_email")].join(":")
      now = Time.now.to_i
      TOKEN_CACHE_LOCK.synchronize do
        cached = TOKEN_CACHE[cache_key]
        return cached[:access_token] if cached && cached[:expires_at] > now + 60
      end

      token = request_access_token(credentials)
      TOKEN_CACHE_LOCK.synchronize { TOKEN_CACHE[cache_key] = token }
      token.fetch(:access_token)
    end

    def self.request_access_token(credentials)
      assertion = signed_jwt(credentials)
      response = post_form(
        TOKEN_URI,
        {
          grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
          assertion: assertion
        }
      )
      unless response.is_a?(Net::HTTPSuccess)
        raise Error.new(
          "FCM OAuth token request failed with HTTP #{response.code}",
          status: response.code.to_i,
          response_body: response.body
        )
      end

      body = JSON.parse(response.body)
      {
        access_token: body.fetch("access_token"),
        expires_at: Time.now.to_i + body.fetch("expires_in").to_i
      }
    rescue JSON::ParserError, KeyError => e
      raise Error, "FCM OAuth token response was invalid: #{e.message}"
    end

    def self.signed_jwt(credentials)
      now = Time.now.to_i
      header = base64url(JSON.generate(alg: "RS256", typ: "JWT"))
      claim = base64url(
        JSON.generate(
          iss: credentials.fetch("client_email"),
          scope: SCOPE,
          aud: TOKEN_URI.to_s,
          iat: now,
          exp: now + 3600
        )
      )
      signing_input = "#{header}.#{claim}"
      key = OpenSSL::PKey::RSA.new(credentials.fetch("private_key"))
      signature = key.sign(OpenSSL::Digest::SHA256.new, signing_input)
      "#{signing_input}.#{base64url(signature)}"
    end

    def self.base64url(value)
      Base64.urlsafe_encode64(value).delete("=")
    end

    def self.post_form(uri, form)
      request = Net::HTTP::Post.new(uri)
      request["Accept"] = "application/json"
      request.set_form_data(form)
      perform_request(uri, request)
    end

    def self.perform_request(uri, request)
      Net::HTTP.start(uri.host, uri.port, use_ssl: uri.scheme == "https") do |http|
        http.request(request)
      end
    end

    private

    def credentials_cache_id
      self.class.credentials_content ? "env:FCM_SERVICE_ACCOUNT_JSON_CONTENT" : @credentials_path
    end

    def load_credentials
      raw = self.class.credentials_content
      unless raw
        unless @credentials_path && File.file?(@credentials_path)
          raise NotConfigured, "FCM service account JSON is not configured"
        end

        raw = File.read(@credentials_path)
      end

      credentials = JSON.parse(raw)
      %w[project_id client_email private_key].each do |key|
        raise NotConfigured, "FCM service account JSON is missing #{key}" if credentials[key].to_s.empty?
      end
      credentials
    rescue JSON::ParserError => e
      raise NotConfigured, "FCM service account JSON is invalid: #{e.message}"
    end

    def data_payload(payload)
      unless payload.is_a?(Hash)
        raise Error, "FCM data payload must be a Hash"
      end

      payload.each_with_object({}) do |(key, value), data|
        next if value.nil?

        data[key.to_s] = string_payload_value(value)
      end
    end

    def string_payload_value(value)
      case value
      when String
        value
      when Numeric, TrueClass, FalseClass
        value.to_s
      else
        JSON.generate(value)
      end
    end

    def post_json(uri, body, bearer_token:)
      request = Net::HTTP::Post.new(uri)
      request["Accept"] = "application/json"
      request["Authorization"] = "Bearer #{bearer_token}"
      request["Content-Type"] = "application/json; charset=utf-8"
      request.body = JSON.generate(body)

      log_info("fcm push request uri=#{uri} body=#{JSON.generate(redacted_fcm_request_body(body))}")
      response = begin
        self.class.perform_request(uri, request)
      rescue StandardError => e
        log_warn("fcm push request failed error=#{e.class}: #{e.message}")
        raise
      end
      log_info("fcm push response status=#{response.code} body=#{log_body(response.body)}")
      unless response.is_a?(Net::HTTPSuccess)
        raise Error.new(
          "FCM send failed with HTTP #{response.code}",
          status: response.code.to_i,
          response_body: response.body
        )
      end

      JSON.parse(response.body)
    rescue JSON::ParserError => e
      raise Error, "FCM send response was invalid: #{e.message}"
    end

    def redacted_fcm_request_body(body)
      JSON.parse(JSON.generate(body)).tap do |redacted|
        message = redacted["message"]
        message["token"] = redacted_token(message["token"]) if message.is_a?(Hash)
      end
    end

    def redacted_token(token)
      value = token.to_s
      return "[REDACTED]" if value.empty?
      return "#{value[0, 4]}...[REDACTED]" if value.length <= 12

      "#{value[0, 6]}...[REDACTED]...#{value[-4, 4]}"
    end

    def log_body(body)
      value = body.to_s
      value.length > 4096 ? "#{value[0, 4096]}...[TRUNCATED]" : value
    end

    def log_info(message)
      @logger&.info(message)
    end

    def log_warn(message)
      @logger&.warn(message)
    end
  end

  class FcmPushClientFactory
    def call(fcm_push_token_value, logger: nil)
      FcmPushClient.new(fcm_push_token_value, logger: logger)
    end
  end
end

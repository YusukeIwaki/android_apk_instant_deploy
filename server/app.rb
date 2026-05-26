# frozen_string_literal: true

require "json"
require "logger"
require "securerandom"
require "sinatra/base"
require "time"
require "uri"
require_relative "lib/apk_inspector"
require_relative "lib/fcm_push_client"
require_relative "lib/models"
require_relative "lib/object_store"
require_relative "lib/security"

module ApkInstantDeploy
  class Server < Sinatra::Base
    configure do
      Database.establish_connection

      set :server, :puma
      set :bind, ENV.fetch("HOST", "0.0.0.0")
      set :port, ENV.fetch("PORT", "4567")
      set :show_exceptions, :after_handler
      set :host_authorization, { permitted_hosts: [] }
      set :views, File.expand_path("views", __dir__)
      default_fcm_push_enabled = ENV.fetch("RACK_ENV", "development") == "test" ? "false" : "true"
      fcm_push_enabled = ENV.fetch("FCM_PUSH_ENABLED", default_fcm_push_enabled)
      set :fcm_push_enabled, !%w[0 false no].include?(fcm_push_enabled.downcase)
      set :fcm_push_client_factory, FcmPushClientFactory.new
      fcm_event_logger = Logger.new($stdout)
      fcm_event_logger.level = Logger::INFO
      fcm_event_logger.formatter = proc do |severity, datetime, _progname, message|
        "#{datetime.utc.iso8601} #{severity} #{message}\n"
      end
      set :fcm_event_logger, fcm_event_logger
      enable :sessions
      set :session_secret, SecureRandom.hex(64)
    end

    before do
      path = request.path_info
      @flash = session.delete(:flash)
      if path == "/health" || path.start_with?("/api/") || path.start_with?("/admin/")
        content_type :json
      elsif path.start_with?("/artifact_objects/")
        # signed url path — content type set in handler
      else
        require_ui_session! unless path == "/login"
      end
    end

    helpers do
      def json_response(payload, response_status = 200)
        status response_status
        JSON.pretty_generate(payload)
      end

      def error_response(code, message, response_status)
        content_type :json
        json_response({ error: { code: code, message: message } }, response_status)
      end

      def parsed_json_body
        body = request.body.read
        return {} if body.strip.empty?

        JSON.parse(body)
      rescue JSON::ParserError
        halt 400, error_response("INVALID_REQUEST", "Request body is not valid JSON.", 400)
      end

      def bearer_token
        authorization = request.env["HTTP_AUTHORIZATION"].to_s
        return nil unless authorization.start_with?("Bearer ")

        authorization.delete_prefix("Bearer ").strip
      end

      def require_admin!
        token = bearer_token
        if token.to_s.empty?
          halt 401, error_response("ADMIN_AUTH_REQUIRED", "Admin bearer token is required.", 401)
        end

        expected = ENV.fetch("ADMIN_TOKEN", "dev-admin-token")
        return if Security.secure_compare(Security.hmac(token), Security.hmac(expected))

        halt 401, error_response("ADMIN_AUTH_INVALID", "Admin bearer token is invalid or has been revoked.", 401)
      end

      def current_device
        token = bearer_token
        if token.to_s.empty?
          halt 401, error_response("DEVICE_AUTH_REQUIRED", "Device bearer token is required. Re-register the device if it was previously valid.", 401)
        end

        credential = DeviceCredential.find_by(token_hmac: Security.hmac(token))
        unless credential
          halt 401, error_response("DEVICE_AUTH_REQUIRED", "Device bearer token is required. Re-register the device if it was previously valid.", 401)
        end

        credential.device
      end

      def timestamp(value)
        value&.utc&.iso8601
      end

      def positive_integer(value)
        integer = Integer(value)
        integer.positive? ? integer : nil
      rescue ArgumentError, TypeError
        nil
      end

      def object_store
        @object_store ||= ObjectStore.build(self)
      end

      def artifact_payload(artifact)
        {
          id: artifact.id,
          filename: artifact.filename,
          sha256: artifact.sha256,
          size_bytes: artifact.size_bytes,
          signing_cert_sha256: artifact.signing_cert_sha256
        }
      end

      def apps_payload
        apps = App.includes(:releases).order(updated_at: :desc, id: :desc).map do |app|
          {
            id: app.id,
            package_name: app.package_name,
            releases: app.releases.sort_by { |release| [-release.version_code, -release.id] }.map do |release|
              {
                id: release.id,
                version_code: release.version_code,
                version_name: release.version_name
              }
            end
          }
        end
        { apps: apps }
      end

      def latest_release_for(app)
        app.releases.order(version_code: :desc, id: :desc).first
      end

      def app_display_name(app)
        display_name = app.app_profile&.display_name.to_s.strip
        display_name.empty? ? app.package_name : display_name
      end

      def policy_entry_payload(entry)
        app = entry.app
        release = latest_release_for(app)
        return nil unless release

        {
          app: {
            id: app.id,
            package_name: app.package_name,
            display_name: app_display_name(app)
          },
          install_mode: entry.install_mode,
          install: {
            release: { id: release.id },
            version_code: release.version_code,
            version_name: release.version_name,
            artifact_sha256: release.artifact.sha256
          }
        }
      end

      def admin_policy_payload(device, policy)
        revision = policy.current_revision
        entries = revision ? revision.device_policy_app_entries.includes(app: :app_profile).map { |entry| policy_entry_payload(entry) }.compact : []
        {
          device_policy: {
            identifier: device.identifier,
            current_revision: { id: revision&.id },
            updated_at: timestamp(policy.updated_at),
            entries: entries
          }
        }
      end

      def device_policy_payload(device, policy)
        revision = policy.current_revision
        entries = revision ? revision.device_policy_app_entries.includes(app: :app_profile).map { |entry| policy_entry_payload(entry) }.compact : []
        {
          device_policy_revision: { id: revision.id },
          updated_at: timestamp(policy.updated_at),
          server_time: Time.now.utc.iso8601,
          entries: entries
        }
      end

      def create_empty_policy_for_device!(device)
        DevicePolicy.transaction do
          policy = device.device_policy || DevicePolicy.create!(device: device)
          unless policy.current_revision
            revision = policy.device_policy_revisions.create!
            policy.update!(current_revision: revision)
          end
          policy
        end
      end

      def notification_payload(notification)
        payload = {
          id: notification.id,
          kind: notification.kind,
          title: notification.title,
          body: notification.body,
          created_at: timestamp(notification.created_at)
        }
        if notification.app
          payload[:app] = {
            id: notification.app.id,
            package_name: notification.app.package_name,
            display_name: app_display_name(notification.app)
          }
        end
        payload
      end

      def sync_report_payload(report)
        {
          id: report.id,
          device_policy_revision: { id: report.device_policy_revision_id },
          fetched_policy_updated_at: timestamp(report.fetched_policy_updated_at),
          applied_policy_updated_at: timestamp(report.applied_policy_updated_at),
          reported_at: timestamp(report.reported_at),
          actions: report.actions
        }
      end

      def validate_policy_entries!(payload)
        entries = payload["entries"]
        unless entries.is_a?(Array)
          halt 400, error_response("INVALID_REQUEST", "Request body is not valid JSON.", 400)
        end

        app_ids = []
        entries.each do |entry|
          app_id = positive_integer(entry.dig("app", "id")) if entry.is_a?(Hash)
          mode = entry["install_mode"] if entry.is_a?(Hash)
          unless app_id
            halt 400, error_response("APP_NOT_FOUND_FOR_ENTRY", "One or more entries reference an app that does not exist.", 400)
          end
          unless %w[FORCE_INSTALLED AVAILABLE].include?(mode)
            halt 400, error_response("INSTALL_MODE_INVALID", "install_mode must be one of FORCE_INSTALLED, AVAILABLE.", 400)
          end
          app_ids << app_id
        end

        existing_ids = App.where(id: app_ids).pluck(:id)
        if app_ids.length != app_ids.uniq.length || existing_ids.sort != app_ids.uniq.sort
          halt 400, error_response("APP_NOT_FOUND_FOR_ENTRY", "One or more entries reference an app that does not exist.", 400)
        end

        entries
      end

      def validate_sync_actions!(actions)
        return false unless actions.is_a?(Array)

        actions.all? do |action|
          next false unless action.is_a?(Hash)

          valid = action["package_name"].to_s.strip != "" &&
            %w[INSTALL UNINSTALL].include?(action["action"]) &&
            %w[AMAPI_CUSTOM_APP PACKAGE_INSTALLER].include?(action["route"]) &&
            %w[INSTALLED UNINSTALLED FAILED SKIPPED].include?(action["result"])
          failed = action["result"] == "FAILED"
          valid && (!failed || (%w[DOWNLOAD INSTALL].include?(action["phase"]) && action["code"].to_s.strip != ""))
        end
      end

      def upsert_fcm_push_token!(device, fcm_push_token)
        record = device.fcm_push_token || FcmPushToken.new(device: device)
        record.assign_attributes(
          token_hash: Security.hmac(fcm_push_token),
          encrypted_token: Security.encrypt_token(fcm_push_token),
          last_registered_at: Time.now.utc
        )
        record.save!
        record
      end

      def notify_policy_updated(device, policy)
        settings.fcm_event_logger.info("policy updated for #{device.identifier} at #{timestamp(policy.updated_at)}")
        return unless settings.fcm_push_enabled

        token = device.fcm_push_token
        unless token
          settings.fcm_event_logger.warn("fcm push skipped for #{device.identifier}: fcm token is not registered")
          return
        end

        payload = {
          type: "POLICY_UPDATED",
          identifier: device.identifier,
          device_policy_revision_id: policy.current_revision_id,
          device_policy_updated_at: timestamp(policy.updated_at)
        }
        fcm_push_token_value = Security.decrypt_token(token.encrypted_token)
        fcm_push_client = settings.fcm_push_client_factory.call(fcm_push_token_value)
        fcm_push_client.logger = settings.fcm_event_logger if fcm_push_client.respond_to?(:logger=)
        fcm_push_client.send_message(payload)
      rescue FcmPushClient::NotConfigured => e
        settings.fcm_event_logger.warn("fcm push skipped for #{device.identifier}: #{e.message}")
      rescue FcmPushClient::Error => e
        settings.fcm_event_logger.warn("fcm push failed for #{device.identifier}: #{e.message}")
      rescue ArgumentError, OpenSSL::Cipher::CipherError => e
        settings.fcm_event_logger.warn("fcm token decrypt failed for #{device.identifier}: #{e.message}")
      rescue StandardError => e
        settings.fcm_event_logger.error("fcm push failed for #{device.identifier}: #{e.class}: #{e.message}")
      end

      def public_base_url
        configured = ENV["PUBLIC_BASE_URL"].to_s.strip
        configured.empty? ? request.base_url : configured.delete_suffix("/")
      end

      def device_registration_deep_link(identifier, secret)
        query = URI.encode_www_form(
          identifier: identifier,
          secret: secret,
          server_base_url: public_base_url
        )
        "apkdist://register-device?#{query}"
      end

      def companion_app_package_name
        ENV.fetch("COMPANION_APP_PACKAGE_NAME", "io.github.yusukeiwaki.android_apk_instant_deploy.alpha")
      end

      def amapi_application_policy_payload(identifier, secret, display_name)
        {
          packageName: companion_app_package_name,
          installType: "FORCE_INSTALLED",
          defaultPermissionPolicy: "GRANT",
          permissionGrants: [
            {
              permission: "android.permission.POST_NOTIFICATIONS",
              policy: "GRANT"
            }
          ],
          managedConfiguration: {
            server_base_url: public_base_url,
            device_registration_identifier: identifier,
            device_registration_secret: secret,
            display_name: display_name
          }
        }
      end

      def h(text)
        Rack::Utils.escape_html(text.to_s)
      end

      def require_ui_session!
        redirect "/login" unless session[:authenticated]
      end

      def set_flash(type, message)
        session[:flash] = { type: type, message: message }
      end

      def admin_token_matches?(input)
        expected = ENV.fetch("ADMIN_TOKEN", "dev-admin-token")
        Security.secure_compare(Security.hmac(input.to_s), Security.hmac(expected))
      end

      def render_page(view, title:, locals: {})
        @title = title
        erb view, locals: locals
      end

      def valid_artifact_signature?(key)
        expires_at = Integer(params.fetch("expires_at"))
        return false if expires_at < Time.now.utc.to_i

        expected = Security.hmac("#{key}:#{expires_at}")
        Security.secure_compare(params.fetch("signature").to_s, expected)
      rescue ArgumentError, KeyError
        false
      end
    end

    get "/health" do
      json_response(status: "ok", service: "android_apk_instant_deploy")
    end

    post "/admin/device_registration_tokens" do
      require_admin!
      payload = parsed_json_body
      expires_in = positive_integer(payload["expires_in_minutes"])
      unless expires_in
        halt 400, error_response("EXPIRES_IN_MINUTES_INVALID", "expires_in_minutes must be a positive integer.", 400)
      end

      issued_at = Time.now.utc
      expires_at = issued_at + (expires_in * 60)
      identifier = Security.registration_identifier
      secret = Security.registration_secret

      token = DeviceRegistrationToken.transaction do
        device_identifier = DeviceIdentifier.create!(identifier: identifier)
        DeviceRegistrationToken.create!(
          device_identifier: device_identifier,
          secret_hash: Security.hmac(secret),
          issued_at: issued_at,
          expires_at: expires_at
        )
      end

      deep_link = device_registration_deep_link(identifier, secret)
      json_response(
        {
          device_registration_token: {
            identifier: token.device_identifier.identifier,
            registration_secret: secret,
            issued_at: timestamp(token.issued_at),
            expires_at: timestamp(token.expires_at),
            deep_link: deep_link
          }
        },
        201
      )
    end

    post "/api/devices" do
      payload = parsed_json_body
      display_name = payload["display_name"].to_s.strip
      fcm_push_token = payload["fcm_push_token"].to_s.strip
      halt 400, error_response("DISPLAY_NAME_REQUIRED", "display_name is required.", 400) if display_name.empty?
      halt 400, error_response("FCM_PUSH_TOKEN_REQUIRED", "fcm_push_token is required.", 400) if fcm_push_token.empty?

      identifier = payload["identifier"].to_s.strip
      registration_secret = payload["registration_secret"].to_s
      device_identifier = DeviceIdentifier.find_by(identifier: identifier)

      if device_identifier&.device
        halt 403, error_response("DEVICE_ALREADY_REGISTERED", "A device already exists for this identifier.", 403)
      end

      token = device_identifier&.device_registration_token
      unless token && token.expires_at > Time.now.utc && Security.secure_compare(token.secret_hash, Security.hmac(registration_secret))
        halt 404, error_response("REGISTRATION_TOKEN_NOT_FOUND", "Registration link is expired, already used, or the secret is incorrect.", 404)
      end

      device_auth_token = Security.device_auth_token
      device = nil

      Device.transaction do
        device = Device.create!(device_identifier: device_identifier, registered_at: Time.now.utc)
        DeviceProfile.create!(device: device, display_name: display_name)
        DeviceCredential.create!(device: device, token_hmac: Security.hmac(device_auth_token), issued_at: Time.now.utc)
        upsert_fcm_push_token!(device, fcm_push_token)
        token.destroy!
      end

      json_response(
        {
          device: {
            identifier: device.identifier,
            device_auth_token: device_auth_token,
            profile: { display_name: display_name },
            registered_at: timestamp(device.registered_at)
          }
        },
        201
      )
    rescue ActiveRecord::RecordNotUnique
      halt 403, error_response("DEVICE_ALREADY_REGISTERED", "A device already exists for this identifier.", 403)
    end

    delete "/admin/devices/:identifier" do
      require_admin!
      device_identifier = DeviceIdentifier.find_by(identifier: params.fetch("identifier"))
      device = device_identifier&.device
      halt 404, error_response("DEVICE_NOT_FOUND", "Device was not found.", 404) unless device

      device.destroy!
      status 204
      body ""
    end

    post "/admin/artifacts" do
      require_admin!
      uploaded = params["file"]
      unless uploaded.is_a?(Hash) && uploaded[:tempfile] && uploaded[:filename]
        halt 400, error_response("FILE_REQUIRED", 'multipart field "file" is required (exactly one).', 400)
      end

      begin
        metadata = ApkInspector.inspect(uploaded[:tempfile].path)
      rescue ApkInspector::InvalidApk
        halt 400, error_response("INVALID_APK", "file must be a parseable APK.", 400)
      end

      if Artifact.exists?(sha256: metadata.sha256)
        halt 400, error_response("ARTIFACT_ALREADY_EXISTS", "The exact same APK artifact has already been uploaded. Re-uploading the same package_name and version_code is allowed only when the APK checksum differs.", 400)
      end

      app = App.find_by(package_name: metadata.package_name)
      if app
        existing_cert = app.releases.joins(:artifact).where.not(artifacts: { signing_cert_sha256: "UNKNOWN" }).pick("artifacts.signing_cert_sha256")
        if existing_cert && metadata.signing_cert_sha256 != "UNKNOWN" && existing_cert != metadata.signing_cert_sha256
          halt 400, error_response("SIGNING_CERT_MISMATCH", "Signing certificate differs from the previously registered release for this package.", 400)
        end
      end

      artifact = nil
      stored_key = nil

      Artifact.transaction do
        artifact = Artifact.create!(
          filename: File.basename(uploaded[:filename]),
          content_type: uploaded[:type].to_s.empty? ? "application/vnd.android.package-archive" : uploaded[:type],
          s3_key: "artifacts/pending/#{SecureRandom.uuid}/#{File.basename(uploaded[:filename])}",
          sha256: metadata.sha256,
          size_bytes: metadata.size_bytes,
          signing_cert_sha256: metadata.signing_cert_sha256
        )
        stored_key = "artifacts/#{artifact.id}/#{artifact.filename}"
        object_store.put(stored_key, uploaded[:tempfile].path)
        artifact.update!(s3_key: stored_key)

        app ||= App.create_or_find_by!(package_name: metadata.package_name)
        AppProfile.find_or_initialize_by(app: app).tap do |profile|
          profile.display_name = metadata.display_name
          profile.save!
        end
        AppIcon.find_or_create_by!(app: app)
        Release.create!(
          app: app,
          artifact: artifact,
          version_code: metadata.version_code,
          version_name: metadata.version_name.to_s
        )
      end

      json_response({ artifact: artifact_payload(artifact) }, 201)
    rescue ActiveRecord::RecordNotUnique
      object_store.delete(stored_key) if stored_key
      halt 400, error_response("ARTIFACT_ALREADY_EXISTS", "The exact same APK artifact has already been uploaded. Re-uploading the same package_name and version_code is allowed only when the APK checksum differs.", 400)
    rescue Aws::S3::Errors::ServiceError
      halt 502, error_response("ARTIFACT_UPLOAD_FAILED", "Failed to store the uploaded APK. Retry the upload.", 502)
    end

    get "/admin/apps" do
      require_admin!
      json_response(apps_payload)
    end

    delete "/admin/releases/:release_id" do
      require_admin!
      release = Release.includes(:artifact, :app).find_by(id: params.fetch("release_id"))
      halt 404, error_response("RELEASE_NOT_FOUND", "Release was not found.", 404) unless release

      artifact = release.artifact
      app = release.app

      begin
        object_store.delete(artifact.s3_key)
      rescue Aws::S3::Errors::ServiceError
        halt 502, error_response("ARTIFACT_DELETE_FAILED", "Failed to remove the stored APK object. The release was not deleted.", 502)
      end

      Release.transaction do
        release.destroy!
        artifact.destroy!
        app.destroy! unless app.releases.exists?
      end

      status 204
      body ""
    end

    get "/api/releases/:release_id/artifact_url" do
      current_device
      release = Release.find_by(id: params.fetch("release_id"))
      halt 404, error_response("RELEASE_NOT_FOUND", "Release was not found.", 404) unless release

      detail = object_store.download_url(release.artifact.s3_key)
      json_response(
        release_artifact_url: {
          release: { id: release.id },
          artifact_url: detail.url,
          expires_at: timestamp(detail.expires_at)
        }
      )
    end

    get "/artifact_objects/*" do
      store = object_store
      key = URI.decode_www_form_component(params.fetch("splat").first)
      halt 403, error_response("ARTIFACT_URL_EXPIRED", "Artifact download URL is expired or invalid.", 403) unless valid_artifact_signature?(key)

      content_type "application/vnd.android.package-archive"
      headers "Content-Disposition" => "attachment; filename=\"#{File.basename(key)}\""

      if store.respond_to?(:local_path)
        path = store.local_path(key)
        halt 404 unless File.file?(path)

        send_file path, filename: File.basename(path), disposition: "attachment"
      else
        body store.read(key)
      end
    end

    get "/admin/devices/:identifier/policy" do
      require_admin!
      device_identifier = DeviceIdentifier.find_by(identifier: params.fetch("identifier"))
      device = device_identifier&.device
      halt 404, error_response("DEVICE_NOT_FOUND", "Device was not found.", 404) unless device

      policy = device.device_policy
      unless policy&.current_revision
        halt 404, error_response("DEVICE_POLICY_NOT_FOUND", "Device policy has not been created for this device yet.", 404)
      end

      json_response(admin_policy_payload(device, policy))
    end

    put "/admin/devices/:identifier/policy" do
      require_admin!
      payload = parsed_json_body
      entries = validate_policy_entries!(payload)
      device_identifier = DeviceIdentifier.find_by(identifier: params.fetch("identifier"))
      device = device_identifier&.device
      halt 404, error_response("DEVICE_NOT_FOUND", "Device was not found.", 404) unless device

      policy = nil
      DevicePolicy.transaction do
        policy = device.device_policy || DevicePolicy.create!(device: device)
        revision = policy.device_policy_revisions.create!
        entries.each do |entry|
          DevicePolicyAppEntry.create!(
            device_policy_revision: revision,
            app_id: entry.fetch("app").fetch("id"),
            install_mode: entry.fetch("install_mode")
          )
        end
        policy.update!(current_revision: revision)
      end

      notify_policy_updated(device, policy)
      json_response(admin_policy_payload(device, policy))
    end

    get "/api/devices/me/policy" do
      device = current_device
      policy = create_empty_policy_for_device!(device)
      json_response(device_policy_payload(device, policy))
    end

    put "/api/devices/me/fcm_push_token" do
      device = current_device
      payload = parsed_json_body
      fcm_push_token = payload["fcm_push_token"].to_s.strip
      halt 400, error_response("FCM_PUSH_TOKEN_REQUIRED", "fcm_push_token is required.", 400) if fcm_push_token.empty?

      token = upsert_fcm_push_token!(device, fcm_push_token)
      json_response(
        fcm_push_token: {
          registered_at: timestamp(token.last_registered_at)
        }
      )
    end

    get "/api/notifications" do
      device = current_device
      notifications = device.notifications.includes(app: :app_profile).order(created_at: :desc, id: :desc).map { |notification| notification_payload(notification) }
      json_response({ notifications: notifications })
    end

    post "/api/devices/me/policy_sync_results" do
      device = current_device
      payload = parsed_json_body
      revision_id = positive_integer(payload.dig("device_policy_revision", "id"))
      unless revision_id
        halt 400, error_response("DEVICE_POLICY_REVISION_REQUIRED", "device_policy_revision.id is required.", 400)
      end

      actions = payload["actions"]
      unless validate_sync_actions!(actions)
        halt 400, error_response("ACTIONS_INVALID", "actions must be an array of valid PolicySyncAction objects.", 400)
      end

      revision = DevicePolicyRevision.joins(:device_policy).find_by(id: revision_id, device_policies: { device_id: device.id })
      unless revision
        halt 404, error_response("DEVICE_POLICY_REVISION_NOT_FOUND", "The referenced device_policy_revision was not found for this device.", 404)
      end

      existing = DevicePolicySyncReport.find_by(device: device, device_policy_revision: revision)
      if existing
        json_response({ policy_sync_result: sync_report_payload(existing) }, 200)
      else
        report = DevicePolicySyncReport.create!(
          device: device,
          device_policy_revision: revision,
          fetched_policy_updated_at: Time.parse(payload.fetch("fetched_policy_updated_at")),
          applied_policy_updated_at: Time.parse(payload.fetch("applied_policy_updated_at")),
          actions: actions,
          reported_at: Time.now.utc
        )

        json_response({ policy_sync_result: sync_report_payload(report) }, 201)
      end
    rescue ArgumentError, KeyError
      halt 400, error_response("INVALID_REQUEST", "Request body is not valid JSON.", 400)
    rescue ActiveRecord::RecordNotUnique
      existing = DevicePolicySyncReport.find_by(device: device, device_policy_revision_id: revision_id)
      json_response({ policy_sync_result: sync_report_payload(existing) }, 200)
    end

    # ----- Admin UI -----

    get "/login" do
      redirect "/" if session[:authenticated]
      render_page :login, title: "Sign in"
    end

    post "/login" do
      token = params["token"].to_s.strip
      if !token.empty? && admin_token_matches?(token)
        session[:authenticated] = true
        redirect "/"
      else
        @error = "Admin token is invalid."
        render_page :login, title: "Sign in"
      end
    end

    post "/logout" do
      session.clear
      redirect "/login"
    end

    get "/" do
      device_count = Device.count
      app_count = App.count
      release_count = Release.count
      pending_token_count = DeviceRegistrationToken.where("expires_at > ?", Time.now.utc).count
      policy_count = DevicePolicy.where.not(current_revision_id: nil).count
      recent_reports = DevicePolicySyncReport
        .includes(device: :device_identifier)
        .order(reported_at: :desc)
        .limit(8)
      render_page :dashboard, title: "Dashboard", locals: {
        device_count: device_count,
        app_count: app_count,
        release_count: release_count,
        pending_token_count: pending_token_count,
        policy_count: policy_count,
        recent_reports: recent_reports
      }
    end

    # ----- Devices -----

    get "/devices" do
      devices = Device.includes(:device_identifier, :device_profile, :device_policy)
        .order(registered_at: :desc)
      latest_reports = DevicePolicySyncReport
        .where(device_id: devices.map(&:id))
        .order(reported_at: :desc)
        .group_by(&:device_id)
        .transform_values(&:first)
      pending_tokens = DeviceRegistrationToken
        .includes(:device_identifier)
        .where("expires_at > ?", Time.now.utc)
        .order(expires_at: :asc)
      render_page :"devices/index", title: "Devices", locals: {
        devices: devices,
        latest_reports: latest_reports,
        pending_tokens: pending_tokens
      }
    end

    get "/devices/new" do
      render_page :"devices/new", title: "Issue device registration token", locals: { result: nil }
    end

    post "/devices" do
      expires_in = positive_integer(params["expires_in_minutes"])
      unless expires_in
        @error = "expires_in_minutes must be a positive integer."
        return render_page :"devices/new", title: "Issue device registration token", locals: { result: nil }
      end
      managed_config_display_name = params["managed_config_display_name"].to_s.strip
      managed_config_display_name = "AMAPI test device" if managed_config_display_name.empty?

      issued_at = Time.now.utc
      expires_at = issued_at + (expires_in * 60)
      identifier = Security.registration_identifier
      secret = Security.registration_secret

      token = DeviceRegistrationToken.transaction do
        device_identifier = DeviceIdentifier.create!(identifier: identifier)
        DeviceRegistrationToken.create!(
          device_identifier: device_identifier,
          secret_hash: Security.hmac(secret),
          issued_at: issued_at,
          expires_at: expires_at
        )
      end

      deep_link = device_registration_deep_link(identifier, secret)
      result = {
        identifier: token.device_identifier.identifier,
        secret: secret,
        managed_config_display_name: managed_config_display_name,
        issued_at: token.issued_at,
        expires_at: token.expires_at,
        deep_link: deep_link,
        amapi_application_policy_json: JSON.pretty_generate(
          amapi_application_policy_payload(token.device_identifier.identifier, secret, managed_config_display_name)
        )
      }
      render_page :"devices/new", title: "Issue device registration token", locals: { result: result }
    end

    get "/devices/:identifier" do
      device_identifier = DeviceIdentifier.find_by(identifier: params.fetch("identifier"))
      device = device_identifier&.device
      halt 404, render_page(:not_found, title: "Not found") unless device

      policy = device.device_policy
      revision = policy&.current_revision
      entries = revision ? revision.device_policy_app_entries.includes(:app).to_a : []
      reports = device.device_policy_sync_reports.order(reported_at: :desc).limit(10)
      render_page :"devices/show", title: device.identifier, locals: {
        device: device,
        profile: device.device_profile,
        fcm_token: device.fcm_push_token,
        policy: policy,
        revision: revision,
        entries: entries,
        reports: reports
      }
    end

    post "/devices/:identifier/delete" do
      device_identifier = DeviceIdentifier.find_by(identifier: params.fetch("identifier"))
      device = device_identifier&.device
      if device
        device.destroy!
        set_flash(:success, "Device #{device_identifier.identifier} was deleted.")
      else
        set_flash(:danger, "Device was not found.")
      end
      redirect "/devices"
    end

    post "/device_registration_tokens/:identifier/delete" do
      device_identifier = DeviceIdentifier.find_by(identifier: params.fetch("identifier"))
      token = device_identifier&.device_registration_token
      if token
        DeviceRegistrationToken.transaction do
          token.destroy!
          device_identifier.destroy! unless device_identifier.device
        end
        set_flash(:success, "Registration token was revoked.")
      else
        set_flash(:danger, "Registration token was not found.")
      end
      redirect "/devices"
    end

    # ----- Apps -----

    get "/apps" do
      apps = App.includes(:app_profile, :releases).order(updated_at: :desc, id: :desc)
      render_page :"apps/index", title: "Apps", locals: { apps: apps }
    end

    get "/apps/new" do
      render_page :"apps/new", title: "Upload APK", locals: {}
    end

    post "/apps" do
      uploaded = params["file"]
      unless uploaded.is_a?(Hash) && uploaded[:tempfile] && uploaded[:filename]
        set_flash(:danger, "Choose an APK file to upload.")
        redirect "/apps/new"
      end

      begin
        metadata = ApkInspector.inspect(uploaded[:tempfile].path)
      rescue ApkInspector::InvalidApk => e
        set_flash(:danger, "Invalid APK: #{e.message}")
        redirect "/apps/new"
      end

      if Artifact.exists?(sha256: metadata.sha256)
        set_flash(:danger, "The exact same APK artifact has already been uploaded. The same package_name and versionCode can be uploaded again only when the APK checksum differs.")
        redirect "/apps/new"
      end

      app = App.find_by(package_name: metadata.package_name)
      if app
        existing_cert = app.releases.joins(:artifact).where.not(artifacts: { signing_cert_sha256: "UNKNOWN" }).pick("artifacts.signing_cert_sha256")
        if existing_cert && metadata.signing_cert_sha256 != "UNKNOWN" && existing_cert != metadata.signing_cert_sha256
          set_flash(:danger, "Signing certificate differs from the previously registered release.")
          redirect "/apps/new"
        end
      end

      artifact = nil
      stored_key = nil
      created_app = app

      begin
        Artifact.transaction do
          artifact = Artifact.create!(
            filename: File.basename(uploaded[:filename]),
            content_type: uploaded[:type].to_s.empty? ? "application/vnd.android.package-archive" : uploaded[:type],
            s3_key: "artifacts/pending/#{SecureRandom.uuid}/#{File.basename(uploaded[:filename])}",
            sha256: metadata.sha256,
            size_bytes: metadata.size_bytes,
            signing_cert_sha256: metadata.signing_cert_sha256
          )
          stored_key = "artifacts/#{artifact.id}/#{artifact.filename}"
          object_store.put(stored_key, uploaded[:tempfile].path)
          artifact.update!(s3_key: stored_key)

          created_app ||= App.create_or_find_by!(package_name: metadata.package_name)
          AppProfile.find_or_initialize_by(app: created_app).tap do |profile|
            profile.display_name = metadata.display_name
            profile.save!
          end
          AppIcon.find_or_create_by!(app: created_app)
          Release.create!(
            app: created_app,
            artifact: artifact,
            version_code: metadata.version_code,
            version_name: metadata.version_name.to_s
          )
        end
      rescue ActiveRecord::RecordNotUnique
        object_store.delete(stored_key) if stored_key
        set_flash(:danger, "The exact same APK artifact has already been uploaded. The same package_name and versionCode can be uploaded again only when the APK checksum differs.")
        redirect "/apps/new"
      rescue Aws::S3::Errors::ServiceError
        set_flash(:danger, "Failed to store the uploaded APK. Retry the upload.")
        redirect "/apps/new"
      end

      set_flash(:success, "Uploaded #{metadata.package_name} versionCode=#{metadata.version_code}.")
      redirect "/apps/#{created_app.id}"
    end

    get "/apps/:id" do
      app = App.includes(:app_profile, releases: :artifact).find_by(id: params.fetch("id"))
      halt 404, render_page(:not_found, title: "Not found") unless app

      releases = app.releases.sort_by { |release| [-release.version_code, -release.id] }
      render_page :"apps/show", title: app.package_name, locals: {
        app: app,
        profile: app.app_profile,
        releases: releases
      }
    end

    post "/apps/:id/releases/:release_id/delete" do
      release = Release.includes(:artifact, :app).find_by(id: params.fetch("release_id"))
      app_id = params.fetch("id")
      unless release && release.app_id.to_s == app_id.to_s
        set_flash(:danger, "Release was not found.")
        redirect "/apps/#{app_id}"
      end

      artifact = release.artifact
      app = release.app

      begin
        object_store.delete(artifact.s3_key)
      rescue Aws::S3::Errors::ServiceError
        set_flash(:danger, "Failed to remove the stored APK object. The release was not deleted.")
        redirect "/apps/#{app.id}"
      end

      destroyed_app = false
      Release.transaction do
        release.destroy!
        artifact.destroy!
        if app.releases.exists?
          # leave the app in place
        else
          app.destroy!
          destroyed_app = true
        end
      end

      set_flash(:success, "Release was deleted.")
      redirect(destroyed_app ? "/apps" : "/apps/#{app.id}")
    end

    # ----- Policies -----

    get "/policies" do
      devices = Device.includes(:device_identifier, :device_profile, device_policy: { current_revision: :device_policy_app_entries })
        .order(registered_at: :desc)
      render_page :"policies/index", title: "Policies", locals: { devices: devices }
    end

    get "/policies/:identifier/edit" do
      device_identifier = DeviceIdentifier.find_by(identifier: params.fetch("identifier"))
      device = device_identifier&.device
      halt 404, render_page(:not_found, title: "Not found") unless device

      policy = device.device_policy
      revision = policy&.current_revision
      current_entries = {}
      if revision
        revision.device_policy_app_entries.each do |entry|
          current_entries[entry.app_id] = entry.install_mode
        end
      end
      apps = App.includes(:app_profile).order(:package_name)
      render_page :"policies/edit", title: "Edit policy", locals: {
        device: device,
        apps: apps,
        current_entries: current_entries
      }
    end

    post "/policies/:identifier" do
      device_identifier = DeviceIdentifier.find_by(identifier: params.fetch("identifier"))
      device = device_identifier&.device
      halt 404, render_page(:not_found, title: "Not found") unless device

      include_ids = Array(params["include"]).map { |id| positive_integer(id) }.compact
      install_modes = params["install_mode"] || {}

      entries = include_ids.map do |app_id|
        mode = install_modes[app_id.to_s]
        unless %w[FORCE_INSTALLED AVAILABLE].include?(mode)
          set_flash(:danger, "install_mode must be FORCE_INSTALLED or AVAILABLE for every selected app.")
          redirect "/policies/#{device.identifier}/edit"
        end
        { app_id: app_id, install_mode: mode }
      end

      existing_ids = App.where(id: entries.map { |e| e[:app_id] }).pluck(:id)
      if entries.map { |e| e[:app_id] }.uniq.length != entries.length || existing_ids.sort != entries.map { |e| e[:app_id] }.uniq.sort
        set_flash(:danger, "One or more selected apps no longer exist.")
        redirect "/policies/#{device.identifier}/edit"
      end

      policy = nil
      DevicePolicy.transaction do
        policy = device.device_policy || DevicePolicy.create!(device: device)
        revision = policy.device_policy_revisions.create!
        entries.each do |entry|
          DevicePolicyAppEntry.create!(
            device_policy_revision: revision,
            app_id: entry[:app_id],
            install_mode: entry[:install_mode]
          )
        end
        policy.update!(current_revision: revision)
      end

      notify_policy_updated(device, policy)
      set_flash(:success, "Policy updated for #{device.identifier}.")
      redirect "/policies"
    end

    not_found do
      if request.path_info.start_with?("/api/") || request.path_info.start_with?("/admin/")
        error_response("NOT_FOUND", "The requested endpoint was not found.", 404)
      else
        render_page :not_found, title: "Not found"
      end
    end

    error ActiveRecord::RecordInvalid do
      error_response("INVALID_REQUEST", "Request body is not valid JSON.", 400)
    end

    error do
      error_response("INTERNAL_SERVER_ERROR", "An unexpected server error occurred.", 500)
    end
  end
end

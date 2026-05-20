# frozen_string_literal: true

require "json"
require "securerandom"
require "sinatra/base"
require "time"
require "aws-sdk-s3"
require_relative "lib/models"

module ApkInstantDeploy
  class Server < Sinatra::Base
    configure do
      Database.establish_connection

      set :server, :puma
      set :bind, ENV.fetch("HOST", "0.0.0.0")
      set :port, ENV.fetch("PORT", "4567")
      set :show_exceptions, :after_handler
    end

    before do
      content_type :json
    end

    helpers do
      def now_iso8601
        Time.now.utc.iso8601
      end

      def json_response(payload, response_status = 200)
        status response_status
        JSON.pretty_generate(payload)
      end

      def parsed_json_body
        body = request.body.read
        return {} if body.strip.empty?

        JSON.parse(body)
      rescue JSON::ParserError
        halt 400, json_response(error: "invalid_json")
      end

      def require_fields!(payload, *fields)
        missing = fields.select { |field| payload[field.to_s].to_s.strip.empty? }
        return if missing.empty?

        halt 422, json_response(error: "missing_fields", fields: missing)
      end

      def require_any_field!(payload, *fields)
        return if fields.any? { |field| !payload[field.to_s].to_s.strip.empty? }

        halt 422, json_response(error: "missing_one_of", fields: fields)
      end

      def pagination
        offset = [Integer(params.fetch("offset", "0")), 0].max
        limit = Integer(params.fetch("limit", "30"))
        limit = 30 if limit <= 0
        limit = [limit, 100].min

        { offset: offset, limit: limit }
      rescue ArgumentError
        halt 422, json_response(error: "invalid_pagination")
      end

      def list_response(collection_name, records, total_count, offset)
        {
          collection_name => records,
          total_count: total_count,
          count: records.length,
          offset: offset
        }
      end

      def s3_bucket
        ENV.fetch("S3_BUCKET")
      end

      def s3_region
        ENV.fetch("AWS_REGION", "ap-northeast-1")
      end

      def s3_force_path_style?
        ENV.fetch("S3_FORCE_PATH_STYLE", "false") == "true"
      end

      def s3_credentials
        Aws::Credentials.new(
          ENV.fetch("AWS_ACCESS_KEY_ID"),
          ENV.fetch("AWS_SECRET_ACCESS_KEY")
        )
      end

      def s3_client(endpoint: ENV["S3_ENDPOINT"])
        options = {
          region: s3_region,
          credentials: s3_credentials,
          force_path_style: s3_force_path_style?
        }
        options[:endpoint] = endpoint unless endpoint.to_s.empty?

        Aws::S3::Client.new(options)
      end

      def s3_presigner
        endpoint = ENV.fetch("S3_PUBLIC_ENDPOINT", ENV["S3_ENDPOINT"])
        Aws::S3::Presigner.new(client: s3_client(endpoint: endpoint))
      end

      def presigned_download_url(s3_key)
        s3_presigner.presigned_url(
          :get_object,
          bucket: s3_bucket,
          key: s3_key,
          expires_in: Integer(ENV.fetch("S3_DOWNLOAD_URL_EXPIRES_IN", "900"))
        )
      end

      def timestamp(value)
        value&.utc&.iso8601
      end

      def release_payload(release)
        artifact_url = release.s3_key.to_s.empty? ? release.artifact_url : presigned_download_url(release.s3_key)
        {
          id: release.id,
          package_name: release.package_name,
          version_code: release.version_code,
          version_name: release.version_name,
          s3_key: release.s3_key,
          artifact_url: artifact_url,
          sha256: release.sha256,
          changelog: release.changelog,
          created_at: timestamp(release.created_at),
          updated_at: timestamp(release.updated_at)
        }
      end

      def device_payload(device)
        {
          id: device.id,
          name: device.name,
          amapi_managed: device.amapi_managed,
          model: device.model,
          sdk_int: device.sdk_int,
          last_seen_at: timestamp(device.last_seen_at),
          created_at: timestamp(device.created_at),
          updated_at: timestamp(device.updated_at)
        }
      end

      def job_payload(job)
        {
          id: job.id,
          release_id: job.release_id,
          device_id: job.device_id,
          state: job.state,
          created_at: timestamp(job.created_at),
          updated_at: timestamp(job.updated_at)
        }
      end

      def install_result_payload(result)
        {
          id: result.id,
          job_id: result.rollout_job_id,
          state: result.state,
          reason: result.reason,
          installed_version_code: result.installed_version_code,
          reported_at: timestamp(result.reported_at),
          created_at: timestamp(result.created_at),
          updated_at: timestamp(result.updated_at)
        }
      end
    end

    get "/health" do
      json_response(status: "ok", service: "android_apk_instant_deploy")
    end

    get "/api/storage" do
      json_response(
        storage_configuration: {
          type: "s3",
          bucket: s3_bucket,
          region: s3_region,
          endpoint_configured: !ENV["S3_ENDPOINT"].to_s.empty?,
          public_endpoint_configured: !ENV["S3_PUBLIC_ENDPOINT"].to_s.empty?
        }
      )
    end

    post "/api/artifacts/presigned_uploads" do
      payload = parsed_json_body
      require_fields!(payload, :filename)

      filename = File.basename(payload.fetch("filename"))
      s3_key = payload["s3_key"].to_s.strip
      s3_key = "artifacts/#{SecureRandom.uuid}/#{filename}" if s3_key.empty?
      expires_in = Integer(ENV.fetch("S3_UPLOAD_URL_EXPIRES_IN", "900"))

      upload_url = s3_presigner.presigned_url(
        :put_object,
        bucket: s3_bucket,
        key: s3_key,
        content_type: payload.fetch("content_type", "application/vnd.android.package-archive"),
        expires_in: expires_in
      )

      json_response(
        {
          artifact_presigned_upload: {
            s3_key: s3_key,
            upload_url: upload_url,
            expires_in: expires_in
          }
        },
        201
      )
    end

    get "/api/releases" do
      page = pagination
      scope = Release.order(created_at: :desc)
      releases = scope.offset(page.fetch(:offset)).limit(page.fetch(:limit)).map { |release| release_payload(release) }
      json_response(list_response(:releases, releases, scope.count, page.fetch(:offset)))
    end

    post "/api/releases" do
      payload = parsed_json_body
      require_fields!(payload, :package_name, :version_code)
      require_any_field!(payload, :s3_key, :artifact_url)

      release = Release.create!(
        package_name: payload.fetch("package_name"),
        version_code: payload.fetch("version_code").to_i,
        version_name: payload["version_name"],
        s3_key: payload["s3_key"],
        artifact_url: payload["artifact_url"],
        sha256: payload["sha256"],
        changelog: payload["changelog"]
      )

      json_response({ release: release_payload(release) }, 201)
    end

    get "/api/releases/:release_id" do
      release = Release.find_by(id: params.fetch("release_id"))
      halt 404, json_response(error: "release_not_found") unless release

      json_response(release: release_payload(release))
    end

    get "/api/releases/:release_id/artifact_url" do
      release = Release.find_by(id: params.fetch("release_id"))
      halt 404, json_response(error: "release_not_found") unless release

      json_response(
        release_artifact_url: {
          release_id: release.id,
          artifact_url: release_payload(release)[:artifact_url]
        }
      )
    end

    get "/api/devices" do
      page = pagination
      scope = Device.order(updated_at: :desc)
      devices = scope.offset(page.fetch(:offset)).limit(page.fetch(:limit)).map { |device| device_payload(device) }
      json_response(list_response(:devices, devices, scope.count, page.fetch(:offset)))
    end

    post "/api/devices" do
      payload = parsed_json_body
      device_id = payload["device_id"].to_s.strip
      device_id = SecureRandom.uuid if device_id.empty?

      device = Device.find_or_initialize_by(id: device_id)
      device.assign_attributes(
        name: payload["name"],
        amapi_managed: !!payload["amapi_managed"],
        model: payload["model"],
        sdk_int: payload["sdk_int"],
        last_seen_at: Time.now.utc
      )
      device.save!

      json_response({ device: device_payload(device) }, 201)
    end

    get "/api/devices/:device_id" do
      device = Device.find_by(id: params.fetch("device_id"))
      halt 404, json_response(error: "device_not_found") unless device

      json_response(device: device_payload(device))
    end

    post "/api/jobs" do
      payload = parsed_json_body
      require_fields!(payload, :release_id, :device_id)

      release = Release.find_by(id: payload.fetch("release_id"))
      device = Device.find_by(id: payload.fetch("device_id"))
      halt 404, json_response(error: "release_not_found") unless release
      halt 404, json_response(error: "device_not_found") unless device

      job = RolloutJob.create!(
        release_id: release.id,
        device_id: device.id,
        state: "created"
      )

      json_response({ job: job_payload(job) }, 201)
    end

    get "/api/jobs/:job_id" do
      job = RolloutJob.find_by(id: params.fetch("job_id"))
      halt 404, json_response(error: "job_not_found") unless job

      json_response(job: job_payload(job))
    end

    get "/api/devices/:device_id/sync" do
      device = Device.find_by(id: params.fetch("device_id"))
      halt 404, json_response(error: "device_not_found") unless device

      device.update!(last_seen_at: Time.now.utc)
      jobs = RolloutJob.includes(:release).where(device_id: device.id).order(created_at: :desc).to_a
      releases = jobs.map(&:release).uniq.map { |release| release_payload(release) }

      json_response(device: device_payload(device), jobs: jobs.map { |job| job_payload(job) }, releases: releases)
    end

    post "/api/install_results" do
      payload = parsed_json_body
      require_fields!(payload, :job_id, :state)

      job = RolloutJob.find_by(id: payload.fetch("job_id"))
      halt 404, json_response(error: "job_not_found") unless job

      job.update!(state: payload.fetch("state"))

      result = InstallResult.create!(
        rollout_job_id: job.id,
        state: payload.fetch("state"),
        reason: payload["reason"],
        installed_version_code: payload["installed_version_code"],
        reported_at: Time.now.utc
      )

      json_response({ install_result: install_result_payload(result) }, 201)
    end

    not_found do
      json_response({ error: "not_found", path: request.path_info }, 404)
    end

    error do
      json_response(error: "internal_server_error")
    end
  end
end

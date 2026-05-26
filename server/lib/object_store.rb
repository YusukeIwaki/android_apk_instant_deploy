# frozen_string_literal: true

require "aws-sdk-s3"
require "fileutils"
require "uri"
require_relative "security"

module ApkInstantDeploy
  class ObjectStore
    DownloadUrl = Struct.new(:url, :expires_at, keyword_init: true)

    def self.build(app)
      if ENV.fetch("OBJECT_STORE", ENV["S3_BUCKET"].to_s.empty? ? "local" : "s3") == "s3"
        S3ObjectStore.new(app)
      else
        LocalObjectStore.new(app)
      end
    end
  end

  class LocalObjectStore
    def initialize(app)
      @app = app
      @root = File.expand_path(ENV.fetch("LOCAL_ARTIFACT_DIR", "storage/artifacts"), Dir.pwd)
    end

    def put(key, path)
      target = File.join(@root, key)
      FileUtils.mkdir_p(File.dirname(target))
      FileUtils.cp(path, target)
    end

    def delete(key)
      FileUtils.rm_f(File.join(@root, key))
    end

    def download_url(key)
      expires_at = Time.now.utc + Integer(ENV.fetch("S3_DOWNLOAD_URL_EXPIRES_IN", "900"))
      base = ENV.fetch("PUBLIC_BASE_URL", @app.request.base_url)
      ObjectStore::DownloadUrl.new(
        url: server_download_url(base, key, expires_at),
        expires_at: expires_at
      )
    end

    def local_path(key)
      File.join(@root, key)
    end

    private

    def server_download_url(base, key, expires_at)
      expires_epoch = expires_at.to_i
      signature = Security.hmac("#{key}:#{expires_epoch}")
      "#{base}/artifact_objects/#{URI.encode_www_form_component(key)}?#{URI.encode_www_form(expires_at: expires_epoch, signature: signature)}"
    end
  end

  class S3ObjectStore
    def initialize(app)
      @app = app
    end

    def put(key, path)
      client.put_object(
        bucket: bucket,
        key: key,
        body: File.open(path, "rb"),
        content_type: "application/vnd.android.package-archive"
      )
    end

    def delete(key)
      client.delete_object(bucket: bucket, key: key)
    end

    def download_url(key)
      expires_in = Integer(ENV.fetch("S3_DOWNLOAD_URL_EXPIRES_IN", "900"))
      expires_at = Time.now.utc + expires_in
      public_endpoint = ENV["S3_PUBLIC_ENDPOINT"].to_s.strip
      if public_endpoint.empty?
        base = ENV.fetch("PUBLIC_BASE_URL", @app.request.base_url)
        return ObjectStore::DownloadUrl.new(
          url: server_download_url(base, key, expires_at),
          expires_at: expires_at
        )
      end

      ObjectStore::DownloadUrl.new(
        url: presigner.presigned_url(:get_object, bucket: bucket, key: key, expires_in: expires_in),
        expires_at: expires_at
      )
    end

    def read(key)
      client.get_object(bucket: bucket, key: key).body.read
    end

    private

    def bucket
      ENV.fetch("S3_BUCKET")
    end

    def client(endpoint: ENV["S3_ENDPOINT"])
      options = {
        region: ENV.fetch("AWS_REGION", "ap-northeast-1"),
        credentials: Aws::Credentials.new(
          ENV.fetch("AWS_ACCESS_KEY_ID", "test"),
          ENV.fetch("AWS_SECRET_ACCESS_KEY", "test")
        ),
        force_path_style: ENV.fetch("S3_FORCE_PATH_STYLE", "false") == "true"
      }
      options[:endpoint] = endpoint unless endpoint.to_s.empty?
      Aws::S3::Client.new(options)
    end

    def presigner
      endpoint = ENV.fetch("S3_PUBLIC_ENDPOINT")
      Aws::S3::Presigner.new(client: client(endpoint: endpoint))
    end

    def server_download_url(base, key, expires_at)
      expires_epoch = expires_at.to_i
      signature = Security.hmac("#{key}:#{expires_epoch}")
      "#{base}/artifact_objects/#{URI.encode_www_form_component(key)}?#{URI.encode_www_form(expires_at: expires_epoch, signature: signature)}"
    end
  end
end

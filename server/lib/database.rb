# frozen_string_literal: true

require "active_record"
require "erb"
require "logger"
require "yaml"

module ApkInstantDeploy
  module Database
    module_function

    def establish_connection
      ActiveRecord.default_timezone = :utc
      sql_log_enabled = env_enabled?("SQL_LOG", false)
      ActiveRecord::Base.logger = Logger.new($stdout) if sql_log_enabled
      ActiveRecord.verbose_query_logs = env_enabled?("VERBOSE_QUERY_LOGS", sql_log_enabled)
      ActiveRecord::Base.establish_connection(database_config)
    end

    def database_config
      if ENV["DATABASE_URL"].to_s.strip != ""
        config = { url: ENV.fetch("DATABASE_URL") }
        config[:sslca] = ENV["DATABASE_SSL_CA"] if ENV["DATABASE_SSL_CA"].to_s.strip != ""
        return config
      end

      env = ENV.fetch("RACK_ENV", "development")
      config_path = File.expand_path("../config/database.yml", __dir__)
      YAML.safe_load(ERB.new(File.read(config_path)).result, aliases: true).fetch(env)
    end

    def env_enabled?(key, default)
      value = ENV.fetch(key, nil)
      return default if value.nil?

      !%w[0 false no off].include?(value.downcase)
    end
  end
end

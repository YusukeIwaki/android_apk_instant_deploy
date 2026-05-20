# frozen_string_literal: true

require "securerandom"
require_relative "database"

module ApkInstantDeploy
  class ApplicationRecord < ActiveRecord::Base
    self.abstract_class = true

    before_validation :assign_uuid, on: :create

    private

    def assign_uuid
      self.id = SecureRandom.uuid if has_attribute?(:id) && id.to_s.empty?
    end
  end

  class Device < ApplicationRecord
    self.primary_key = "id"

    has_many :rollout_jobs, dependent: :destroy
  end

  class Release < ApplicationRecord
    self.primary_key = "id"

    has_many :rollout_jobs, dependent: :destroy
  end

  class RolloutJob < ApplicationRecord
    self.primary_key = "id"

    belongs_to :device
    belongs_to :release
    has_many :install_results, dependent: :destroy
  end

  class InstallResult < ApplicationRecord
    self.primary_key = "id"

    belongs_to :rollout_job
  end
end

# frozen_string_literal: true

require_relative "database"

module ApkInstantDeploy
  class ApplicationRecord < ActiveRecord::Base
    self.abstract_class = true
  end

  class DeviceIdentifier < ApplicationRecord
    has_one :device_registration_token, dependent: :destroy
    has_one :device, dependent: :destroy
  end

  class DeviceRegistrationToken < ApplicationRecord
    belongs_to :device_identifier
  end

  class Device < ApplicationRecord
    belongs_to :device_identifier
    has_one :device_profile, dependent: :destroy
    has_one :device_credential, dependent: :destroy
    has_one :fcm_push_token, dependent: :destroy
    has_one :device_policy, dependent: :destroy
    has_many :device_policy_sync_reports, dependent: :destroy
    has_many :notifications, dependent: :destroy

    delegate :identifier, to: :device_identifier
  end

  class DeviceProfile < ApplicationRecord
    belongs_to :device
  end

  class DeviceCredential < ApplicationRecord
    belongs_to :device
  end

  class FcmPushToken < ApplicationRecord
    belongs_to :device
  end

  class App < ApplicationRecord
    has_one :app_profile, dependent: :destroy
    has_one :app_icon, dependent: :destroy
    has_many :releases, dependent: :restrict_with_exception
    has_many :device_policy_app_entries, dependent: :delete_all
    has_many :notifications, dependent: :delete_all
  end

  class AppProfile < ApplicationRecord
    belongs_to :app
  end

  class AppIcon < ApplicationRecord
    belongs_to :app
  end

  class Artifact < ApplicationRecord
    has_one :release, dependent: :destroy
  end

  class Release < ApplicationRecord
    belongs_to :app
    belongs_to :artifact
  end

  class DevicePolicy < ApplicationRecord
    belongs_to :device
    belongs_to :current_revision, class_name: "DevicePolicyRevision", optional: true
    has_many :device_policy_revisions, dependent: :destroy

    before_destroy { update_column(:current_revision_id, nil) if current_revision_id }
  end

  class DevicePolicyRevision < ApplicationRecord
    belongs_to :device_policy
    has_many :device_policy_app_entries, dependent: :delete_all
    has_many :device_policy_sync_reports, dependent: :destroy
  end

  class DevicePolicyAppEntry < ApplicationRecord
    belongs_to :device_policy_revision
    belongs_to :app
  end

  class DevicePolicySyncReport < ApplicationRecord
    belongs_to :device
    belongs_to :device_policy_revision
  end

  class Notification < ApplicationRecord
    KINDS = %w[INSTALL_PERMISSION_REQUIRED DOWNLOAD_RETRY_AVAILABLE].freeze

    belongs_to :device
    belongs_to :app, optional: true
  end
end

# frozen_string_literal: true

require "base64"
require "openssl"
require "securerandom"

module ApkInstantDeploy
  module Security
    module_function

    def registration_identifier
      "drt_#{SecureRandom.urlsafe_base64(18).tr("-_", "AZ")}"
    end

    def registration_secret
      "drs_#{SecureRandom.urlsafe_base64(32)}"
    end

    def device_auth_token
      "dat_#{SecureRandom.urlsafe_base64(36)}"
    end

    def hmac(value, key: hmac_secret)
      OpenSSL::HMAC.hexdigest("SHA256", key, value.to_s)
    end

    def secure_compare(left, right)
      left = left.to_s
      right = right.to_s
      return false unless left.bytesize == right.bytesize

      OpenSSL.fixed_length_secure_compare(left, right)
    end

    def encrypt_token(value)
      key = OpenSSL::Digest::SHA256.digest(encryption_secret)
      cipher = OpenSSL::Cipher.new("aes-256-gcm").encrypt
      cipher.key = key
      iv = SecureRandom.random_bytes(12)
      cipher.iv = iv
      ciphertext = cipher.update(value.to_s) + cipher.final
      Base64.strict_encode64(iv + cipher.auth_tag + ciphertext)
    end

    def hmac_secret
      ENV.fetch("TOKEN_HMAC_SECRET", "development-token-hmac-secret")
    end

    def encryption_secret
      ENV.fetch("TOKEN_ENCRYPTION_SECRET", "development-token-encryption-secret")
    end
  end
end

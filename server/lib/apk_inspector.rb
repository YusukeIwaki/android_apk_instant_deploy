# frozen_string_literal: true

require "digest"
require "open3"
require "openssl"
require "zlib"

module ApkInstantDeploy
  class ApkInspector
    ApkMetadata = Struct.new(
      :package_name,
      :version_code,
      :version_name,
      :display_name,
      :icon_bytes,
      :sha256,
      :size_bytes,
      :signing_cert_sha256,
      keyword_init: true
    )

    class InvalidApk < StandardError; end

    def self.inspect(path)
      new(path).inspect
    end

    def initialize(path)
      @path = path
    end

    def inspect
      metadata = inspect_with_android_tool || inspect_manifest_from_zip
      metadata.sha256 = Digest::SHA256.file(@path).hexdigest
      metadata.size_bytes = File.size(@path)
      metadata.signing_cert_sha256 = signing_certificate_sha256 || "UNKNOWN"
      metadata.display_name = metadata.display_name.to_s.strip
      metadata.display_name = metadata.package_name if metadata.display_name.empty?
      metadata
    rescue InvalidApk
      raise
    rescue StandardError => e
      raise InvalidApk, e.message
    end

    private

    def inspect_with_android_tool
      tool = ENV["AAPT_PATH"].to_s
      tool = find_executable("aapt") if tool.empty?
      tool = find_executable("aapt2") if tool.to_s.empty?
      return nil if tool.to_s.empty?

      stdout, _stderr, status = Open3.capture3(tool, "dump", "badging", @path)
      return nil unless status.success?

      package_line = stdout.lines.find { |line| line.start_with?("package:") }
      raise InvalidApk, "package metadata was not found." unless package_line

      ApkMetadata.new(
        package_name: package_line[/name='([^']+)'/, 1],
        version_code: Integer(package_line[/versionCode='([^']+)'/, 1]),
        version_name: package_line[/versionName='([^']*)'/, 1].to_s,
        display_name: stdout[/application-label:'([^']*)'/, 1]
      )
    rescue ArgumentError
      raise InvalidApk, "versionCode was not an integer."
    end

    def inspect_manifest_from_zip
      manifest = ZipFile.read_entry(@path, "AndroidManifest.xml")
      raise InvalidApk, "AndroidManifest.xml was not found." unless manifest

      if manifest.start_with?("<")
        inspect_text_manifest(manifest)
      else
        inspect_binary_manifest(manifest)
      end
    end

    def inspect_text_manifest(manifest)
      package_name = manifest[/<manifest[^>]*\spackage=["']([^"']+)["']/, 1]
      version_code = manifest[/android:versionCode=["']([^"']+)["']/, 1]
      version_name = manifest[/android:versionName=["']([^"']*)["']/, 1]
      label = manifest[/<application[^>]*android:label=["']([^"']+)["']/, 1]
      raise InvalidApk, "manifest package or versionCode was not found." if package_name.to_s.empty? || version_code.to_s.empty?

      ApkMetadata.new(
        package_name: package_name,
        version_code: Integer(version_code),
        version_name: version_name.to_s,
        display_name: label
      )
    rescue ArgumentError
      raise InvalidApk, "versionCode was not an integer."
    end

    def inspect_binary_manifest(manifest)
      parser = BinaryManifestParser.new(manifest)
      parser.parse
    end

    def signing_certificate_sha256
      from_apksigner = signing_certificate_from_apksigner
      return from_apksigner if from_apksigner

      cert_entry = ZipFile.each_entry(@path).find { |entry| entry.name.match?(%r{\AMETA-INF/.*\.(RSA|DSA|EC)\z}i) }
      return nil unless cert_entry

      pkcs7 = OpenSSL::PKCS7.new(cert_entry.data)
      cert = pkcs7.certificates&.first
      cert && Digest::SHA256.hexdigest(cert.to_der)
    rescue OpenSSL::OpenSSLError
      nil
    end

    def signing_certificate_from_apksigner
      tool = ENV["APKSIGNER_PATH"].to_s
      tool = find_executable("apksigner") if tool.empty?
      return nil if tool.to_s.empty?

      stdout, _stderr, status = Open3.capture3(tool, "verify", "--print-certs", @path)
      return nil unless status.success?

      stdout[/Signer #1 certificate SHA-256 digest: ([0-9a-fA-F:]+)/, 1]&.delete(":")&.downcase
    end

    def find_executable(name)
      ENV.fetch("PATH", "").split(File::PATH_SEPARATOR).map { |dir| File.join(dir, name) }.find { |path| File.executable?(path) }
    end

    class BinaryManifestParser
      UTF8_FLAG = 0x00000100
      RES_STRING_POOL_TYPE = 0x0001
      RES_XML_START_ELEMENT_TYPE = 0x0102
      TYPE_STRING = 0x03
      TYPE_INT_DEC = 0x10
      TYPE_INT_HEX = 0x11

      def initialize(bytes)
        @bytes = bytes
        @strings = []
        @package_name = nil
        @version_code = nil
        @version_name = nil
        @display_name = nil
      end

      def parse
        offset = u16(0) == 0x0003 ? u16(2) : 0
        while offset + 8 <= @bytes.bytesize
          type = u16(offset)
          size = u32(offset + 4)
          raise InvalidApk, "invalid binary xml chunk." if size <= 0

          parse_string_pool(offset) if type == RES_STRING_POOL_TYPE
          parse_start_element(offset) if type == RES_XML_START_ELEMENT_TYPE
          offset += size
        end

        if @package_name.to_s.empty? || @version_code.to_s.empty?
          raise InvalidApk, "manifest package or versionCode was not found."
        end

        ApkMetadata.new(
          package_name: @package_name,
          version_code: Integer(@version_code),
          version_name: @version_name.to_s,
          display_name: @display_name
        )
      end

      private

      def parse_string_pool(offset)
        string_count = u32(offset + 8)
        flags = u32(offset + 16)
        strings_start = offset + u32(offset + 20)
        offsets_start = offset + u16(offset + 2)
        utf8 = (flags & UTF8_FLAG) != 0

        @strings = string_count.times.map do |index|
          string_offset = strings_start + u32(offsets_start + (index * 4))
          utf8 ? utf8_string(string_offset) : utf16_string(string_offset)
        end
      end

      def parse_start_element(offset)
        element_name = string_at(u32(offset + 20))
        attr_start = offset + 16 + u16(offset + 24)
        attr_size = u16(offset + 26)
        attr_count = u16(offset + 28)
        attrs = {}

        attr_count.times do |index|
          attr_offset = attr_start + (index * attr_size)
          name = string_at(u32(attr_offset + 4))
          raw_value_index = u32(attr_offset + 8)
          data_type = @bytes.getbyte(attr_offset + 15)
          data = u32(attr_offset + 16)
          attrs[name] = attribute_value(raw_value_index, data_type, data)
        end

        if element_name == "manifest"
          @package_name = attrs["package"]
          @version_code = attrs["versionCode"]
          @version_name = attrs["versionName"]
        elsif element_name == "application"
          label = attrs["label"]
          @display_name = label if label && !label.start_with?("@") && !label.match?(/\A\d+\z/)
        end
      end

      def attribute_value(raw_value_index, data_type, data)
        return string_at(raw_value_index) if raw_value_index != 0xffffffff
        return string_at(data) if data_type == TYPE_STRING
        return data.to_s if [TYPE_INT_DEC, TYPE_INT_HEX].include?(data_type)

        data.to_s
      end

      def utf8_string(offset)
        _utf16_length, pos = read_length8(offset)
        byte_length, pos = read_length8(pos)
        @bytes.byteslice(pos, byte_length).force_encoding("UTF-8")
      end

      def utf16_string(offset)
        length = u16(offset)
        offset += 2
        if (length & 0x8000) != 0
          length = ((length & 0x7fff) << 16) | u16(offset)
          offset += 2
        end
        @bytes.byteslice(offset, length * 2).force_encoding("UTF-16LE").encode("UTF-8")
      end

      def read_length8(offset)
        first = @bytes.getbyte(offset)
        if (first & 0x80) == 0
          [first, offset + 1]
        else
          second = @bytes.getbyte(offset + 1)
          [((first & 0x7f) << 8) | second, offset + 2]
        end
      end

      def string_at(index)
        return nil if index == 0xffffffff

        @strings[index]
      end

      def u16(offset)
        @bytes.unpack1("@#{offset}v")
      end

      def u32(offset)
        @bytes.unpack1("@#{offset}V")
      end
    end

    class ZipFile
      Entry = Struct.new(:name, :data, keyword_init: true)

      def self.read_entry(path, name)
        each_entry(path).find { |entry| entry.name == name }&.data
      end

      def self.each_entry(path)
        new(path).entries
      end

      def initialize(path)
        @bytes = File.binread(path)
      end

      def entries
        eocd_offset = @bytes.rindex([0x06054b50].pack("V"))
        raise InvalidApk, "zip end of central directory was not found." unless eocd_offset

        entry_count = u16(eocd_offset + 10)
        central_offset = u32(eocd_offset + 16)
        offset = central_offset

        entry_count.times.map do
          raise InvalidApk, "zip central directory entry was invalid." unless u32(offset) == 0x02014b50

          method = u16(offset + 10)
          compressed_size = u32(offset + 20)
          name_length = u16(offset + 28)
          extra_length = u16(offset + 30)
          comment_length = u16(offset + 32)
          local_offset = u32(offset + 42)
          name = @bytes.byteslice(offset + 46, name_length)
          data = local_entry_data(local_offset, method, compressed_size)
          offset += 46 + name_length + extra_length + comment_length
          Entry.new(name: name, data: data)
        end
      end

      private

      def local_entry_data(offset, method, compressed_size)
        raise InvalidApk, "zip local header was invalid." unless u32(offset) == 0x04034b50

        name_length = u16(offset + 26)
        extra_length = u16(offset + 28)
        data_offset = offset + 30 + name_length + extra_length
        compressed = @bytes.byteslice(data_offset, compressed_size)

        case method
        when 0
          compressed
        when 8
          inflater = Zlib::Inflate.new(-Zlib::MAX_WBITS)
          begin
            inflater.inflate(compressed) + inflater.finish
          ensure
            inflater.close
          end
        else
          raise InvalidApk, "unsupported zip compression method."
        end
      end

      def u16(offset)
        @bytes.unpack1("@#{offset}v")
      end

      def u32(offset)
        @bytes.unpack1("@#{offset}V")
      end
    end
  end
end

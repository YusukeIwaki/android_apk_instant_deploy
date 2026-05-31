# frozen_string_literal: true

require "smartest/autorun"

Dir[File.join(__dir__, "fixtures", "**", "*.rb")].sort.each do |fixture_file|
  require fixture_file
end

Dir[File.join(__dir__, "matchers", "**", "*.rb")].sort.each do |matcher_file|
  require matcher_file
end

around_suite do |suite|
  use_fixture SinatraServerFixture
  use_fixture ApiFixture
  use_matcher PlaywrightMatcher

  ApkInstantDeploy::CleanDatabase.truncate!

  around_test do |test|
    test.run
  ensure
    ApkInstantDeploy::CleanDatabase.truncate!
  end

  suite.run
end

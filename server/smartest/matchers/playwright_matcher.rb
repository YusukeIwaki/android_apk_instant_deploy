# frozen_string_literal: true

require "playwright"
require "playwright/test"

module PlaywrightMatcher
  include Playwright::Test::Matchers
end

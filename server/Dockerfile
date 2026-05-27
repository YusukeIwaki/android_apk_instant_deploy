FROM ruby:4.0-alpine

WORKDIR /app

ARG ANDROID_CMDLINE_TOOLS_VERSION=14742923
ARG ANDROID_BUILD_TOOLS_VERSION=35.0.0

ENV BUNDLE_WITHOUT="development:test" \
    ANDROID_HOME="/opt/android-sdk" \
    ANDROID_SDK_ROOT="/opt/android-sdk" \
    APKSIGNER_PATH="/opt/android-sdk/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/apksigner" \
    HOST="0.0.0.0" \
    PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/build-tools/${ANDROID_BUILD_TOOLS_VERSION}:${PATH}" \
    PORT="4567" \
    RACK_ENV="production"

RUN apk add --no-cache bash build-base ca-certificates curl mariadb-dev openjdk17-jre unzip \
  && mkdir -p "${ANDROID_HOME}/cmdline-tools/latest" \
  && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/android-commandline-tools.zip \
  && unzip -q /tmp/android-commandline-tools.zip -d /tmp/android-commandline-tools \
  && mv /tmp/android-commandline-tools/cmdline-tools/* "${ANDROID_HOME}/cmdline-tools/latest/" \
  && yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null \
  && sdkmanager --sdk_root="${ANDROID_HOME}" "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" >/dev/null \
  && rm -rf /tmp/android-commandline-tools /tmp/android-commandline-tools.zip

COPY Gemfile Gemfile.lock ./
RUN bundle install

COPY . .

EXPOSE 4567

CMD ["bundle", "exec", "rackup", "-o", "0.0.0.0", "-p", "4567", "config.ru"]

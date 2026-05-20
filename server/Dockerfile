FROM ruby:4.0-alpine

WORKDIR /app

ENV BUNDLE_WITHOUT="development:test" \
    HOST="0.0.0.0" \
    PORT="4567" \
    RACK_ENV="production"

RUN apk add --no-cache build-base mariadb-dev

COPY Gemfile Gemfile.lock ./
RUN bundle install

COPY . .

EXPOSE 4567

CMD ["bundle", "exec", "rackup", "-o", "0.0.0.0", "-p", "4567", "config.ru"]

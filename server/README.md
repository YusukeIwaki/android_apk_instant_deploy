# Server

Sinatra based control-plane skeleton for APK distribution.

## Run

The server is Docker-first and uses S3-compatible object storage for APK artifacts. The local Compose stack starts:

- Sinatra server on `http://localhost:4567`
- Swagger UI OpenAPI preview on `http://localhost:8000`
- MySQL for ActiveRecord metadata
- Floci AWS emulator on the Compose network only
- `apk-artifacts` bucket creation job

```sh
cd server
docker compose up --build
```

Run database migrations with Ridgepole:

```sh
cd server
docker compose run --rm migrate
```

The `migrate` service runs:

```sh
bundle exec ridgepole --config config/database.yml --env development --file db/Schemafile --apply
```

## Endpoints

- `GET /health`
- `GET /api/storage`
- `POST /api/artifacts/presigned_uploads`
- `POST /api/releases`
- `GET /api/releases`
- `GET /api/releases/:release_id/artifact_url`
- `POST /api/devices`
- `GET /api/devices`
- `POST /api/jobs`
- `GET /api/devices/:device_id/sync`
- `POST /api/install_results`

## S3 artifact flow

Create a presigned upload URL:

```sh
curl -sS http://localhost:4567/api/artifacts/presigned_uploads \
  -H 'Content-Type: application/json' \
  -d '{"filename":"app.apk"}'
```

Upload the APK using the returned `upload_url`. In the local Compose stack, the URL points at `http://floci:4566` and is reachable from containers on the Compose network, not from the host. Keep the content type aligned with the signed request:

```sh
curl -X PUT '<upload_url>' \
  -H 'Content-Type: application/vnd.android.package-archive' \
  --upload-file app.apk
```

Register release metadata using the returned `s3_key`:

```sh
curl -sS http://localhost:4567/api/releases \
  -H 'Content-Type: application/json' \
  -d '{"package_name":"com.example.app","version_code":1,"s3_key":"<s3_key>"}'
```

APK artifacts are stored in S3/Floci. Release, device, rollout job, and install result metadata are stored in MySQL through ActiveRecord.

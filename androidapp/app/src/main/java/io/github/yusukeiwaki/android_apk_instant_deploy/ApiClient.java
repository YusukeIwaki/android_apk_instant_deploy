package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;

final class ApiClient {
    private static final String HTTP_LOG_TAG = "ApiClientHttp";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP_CLIENT = createHttpClient();

    private final String baseUrl;
    private final String deviceAuthToken;

    ApiClient(String baseUrl, String deviceAuthToken) {
        this.baseUrl = baseUrl;
        this.deviceAuthToken = deviceAuthToken == null ? "" : deviceAuthToken;
    }

    private static OkHttpClient createHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);

        if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor(message -> Log.d(HTTP_LOG_TAG, message));
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);
            logging.redactHeader("Authorization");
            builder.addInterceptor(logging);
        }

        return builder.build();
    }

    static OkHttpClient sharedHttpClient() {
        return HTTP_CLIENT;
    }

    RegisteredDevice registerDevice(String identifier, String secret, String displayName, String fcmToken) throws IOException, JSONException {
        JSONObject body = new JSONObject()
                .put("identifier", identifier)
                .put("registration_secret", secret)
                .put("display_name", displayName)
                .put("fcm_push_token", fcmToken);
        JSONObject response = requestJson("POST", "/api/devices", body, "");
        JSONObject device = response.getJSONObject("device");
        return new RegisteredDevice(
                device.getString("identifier"),
                device.getString("device_auth_token"),
                device.getJSONObject("profile").getString("display_name")
        );
    }

    PolicySnapshot fetchPolicy() throws IOException, JSONException {
        String raw = requestText("GET", "/api/devices/me/policy", null, deviceAuthToken);
        return parsePolicy(raw);
    }

    void updateFcmPushToken(String fcmToken) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("fcm_push_token", fcmToken);
        requestJson("PUT", "/api/devices/me/fcm_push_token", body, deviceAuthToken);
    }

    List<DeviceNotification> fetchNotifications() throws IOException, JSONException {
        JSONObject response = requestJson("GET", "/api/notifications", null, deviceAuthToken);
        List<DeviceNotification> notifications = new ArrayList<>();
        JSONArray items = response.getJSONArray("notifications");
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            JSONObject app = item.optJSONObject("app");
            int appId = -1;
            String packageName = "";
            String displayName = "";
            if (app != null) {
                appId = app.optInt("id", -1);
                packageName = app.optString("package_name", "");
                displayName = appDisplayName(app, packageName);
            }
            notifications.add(new DeviceNotification(
                    item.getInt("id"),
                    item.getString("kind"),
                    item.getString("title"),
                    item.getString("body"),
                    item.getString("created_at"),
                    appId,
                    packageName,
                    displayName
            ));
        }
        return notifications;
    }

    static PolicySnapshot parsePolicy(String raw) throws JSONException {
        JSONObject response = new JSONObject(raw);
        List<PolicyEntry> entries = new ArrayList<>();
        JSONArray jsonEntries = response.getJSONArray("entries");
        for (int index = 0; index < jsonEntries.length(); index++) {
            JSONObject item = jsonEntries.getJSONObject(index);
            JSONObject app = item.getJSONObject("app");
            JSONObject install = item.getJSONObject("install");
            JSONObject release = install.getJSONObject("release");
            String packageName = app.getString("package_name");
            entries.add(new PolicyEntry(
                    app.getInt("id"),
                    packageName,
                    appDisplayName(app, packageName),
                    item.getString("install_mode"),
                    release.getInt("id"),
                    install.getInt("version_code"),
                    install.optString("version_name", ""),
                    install.optString("artifact_sha256", "")
            ));
        }
        return new PolicySnapshot(
                response.getJSONObject("device_policy_revision").getInt("id"),
                response.getString("updated_at"),
                raw,
                entries
        );
    }

    private static String appDisplayName(JSONObject app, String packageName) {
        String displayName = app.optString("display_name", "").trim();
        return displayName.isEmpty() ? packageName : displayName;
    }

    ArtifactUrl getArtifactUrl(int releaseId) throws IOException, JSONException {
        JSONObject response = requestJson("GET", "/api/releases/" + releaseId + "/artifact_url", null, deviceAuthToken);
        JSONObject detail = response.getJSONObject("release_artifact_url");
        return new ArtifactUrl(detail.getString("artifact_url"), detail.getString("expires_at"));
    }

    private JSONObject requestJson(String method, String path, JSONObject body, String bearerToken) throws IOException, JSONException {
        return new JSONObject(requestText(method, path, body, bearerToken));
    }

    private String requestText(String method, String path, JSONObject body, String bearerToken) throws IOException, JSONException {
        RequestBody requestBody = body == null ? null : RequestBody.create(body.toString(), JSON_MEDIA_TYPE);
        Request.Builder requestBuilder = new Request.Builder()
                .url(baseUrl + path)
                .method(method, requestBody)
                .header("Accept", "application/json");
        if (!bearerToken.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + bearerToken);
        }

        try (Response response = HTTP_CLIENT.newCall(requestBuilder.build()).execute()) {
            int status = response.code();
            String text = readResponseBody(response.body());
            if (response.isSuccessful()) {
                return text;
            }

            String code = "HTTP_" + status;
            String message = text;
            try {
                JSONObject error = new JSONObject(text).getJSONObject("error");
                code = error.optString("code", code);
                message = error.optString("message", message);
            } catch (JSONException ignored) {
            }
            throw new ApiException(status, code, message);
        }
    }

    private String readResponseBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return body.string();
    }

    static final class RegisteredDevice {
        final String identifier;
        final String deviceAuthToken;
        final String displayName;

        RegisteredDevice(String identifier, String deviceAuthToken, String displayName) {
            this.identifier = identifier;
            this.deviceAuthToken = deviceAuthToken;
            this.displayName = displayName;
        }
    }

    static final class PolicySnapshot {
        final int revisionId;
        final String updatedAt;
        final String rawJson;
        final List<PolicyEntry> entries;

        PolicySnapshot(int revisionId, String updatedAt, String rawJson, List<PolicyEntry> entries) {
            this.revisionId = revisionId;
            this.updatedAt = updatedAt;
            this.rawJson = rawJson;
            this.entries = entries;
        }
    }

    static final class PolicyEntry {
        final int appId;
        final String packageName;
        final String displayName;
        final String installMode;
        final int releaseId;
        final int versionCode;
        final String versionName;
        final String artifactSha256;

        PolicyEntry(int appId, String packageName, String displayName, String installMode, int releaseId, int versionCode, String versionName, String artifactSha256) {
            this.appId = appId;
            this.packageName = packageName;
            this.displayName = displayName == null || displayName.isEmpty() ? packageName : displayName;
            this.installMode = installMode;
            this.releaseId = releaseId;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.artifactSha256 = artifactSha256;
        }

        boolean isAvailable() {
            return "AVAILABLE".equals(installMode);
        }

        boolean isForceInstalled() {
            return "FORCE_INSTALLED".equals(installMode);
        }
    }

    static final class DeviceNotification {
        final int id;
        final String kind;
        final String title;
        final String body;
        final String createdAt;
        final int appId;
        final String packageName;
        final String displayName;

        DeviceNotification(int id, String kind, String title, String body, String createdAt, int appId, String packageName, String displayName) {
            this.id = id;
            this.kind = kind;
            this.title = title;
            this.body = body;
            this.createdAt = createdAt;
            this.appId = appId;
            this.packageName = packageName;
            this.displayName = displayName == null || displayName.isEmpty() ? packageName : displayName;
        }

        boolean hasApp() {
            return appId > 0 || !packageName.isEmpty();
        }
    }

    static final class ArtifactUrl {
        final String url;
        final String expiresAt;

        ArtifactUrl(String url, String expiresAt) {
            this.url = url;
            this.expiresAt = expiresAt;
        }
    }

    static final class ApiException extends IOException {
        final int httpStatus;
        final String code;

        ApiException(int httpStatus, String code, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.code = code;
        }
    }
}

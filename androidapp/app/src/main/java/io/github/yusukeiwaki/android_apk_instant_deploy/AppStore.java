package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.content.Context;
import android.content.SharedPreferences;

final class AppStore {
    private static final String PREFS = "apkdist";
    private static final String KEY_SERVER_BASE_URL = "server_base_url";
    private static final String KEY_IDENTIFIER = "identifier";
    private static final String KEY_DEVICE_AUTH_TOKEN = "device_auth_token";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_FCM_TOKEN = "fcm_token";
    private static final String KEY_PENDING_POLICY_FETCH = "pending_policy_fetch";
    private static final String KEY_FETCHED_POLICY_JSON = "fetched_policy_json";

    private final SharedPreferences preferences;

    AppStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String serverBaseUrl() {
        return preferences.getString(KEY_SERVER_BASE_URL, "http://10.0.2.2:4567");
    }

    void setServerBaseUrl(String value) {
        preferences.edit().putString(KEY_SERVER_BASE_URL, trimTrailingSlash(value)).apply();
    }

    boolean isRegistered() {
        return !deviceAuthToken().isEmpty() && !identifier().isEmpty();
    }

    String identifier() {
        return preferences.getString(KEY_IDENTIFIER, "");
    }

    String deviceAuthToken() {
        return preferences.getString(KEY_DEVICE_AUTH_TOKEN, "");
    }

    String displayName() {
        return preferences.getString(KEY_DISPLAY_NAME, "");
    }

    String fcmToken() {
        return preferences.getString(KEY_FCM_TOKEN, "");
    }

    boolean pendingPolicyFetch() {
        return preferences.getBoolean(KEY_PENDING_POLICY_FETCH, true);
    }

    void setPendingPolicyFetch(boolean value) {
        preferences.edit().putBoolean(KEY_PENDING_POLICY_FETCH, value).apply();
    }

    String fetchedPolicyJson() {
        return preferences.getString(KEY_FETCHED_POLICY_JSON, "");
    }

    void saveFetchedPolicy(String json) {
        preferences.edit()
                .putString(KEY_FETCHED_POLICY_JSON, json)
                .putBoolean(KEY_PENDING_POLICY_FETCH, false)
                .apply();
    }

    void saveRegistration(ApiClient.RegisteredDevice device, String fcmToken) {
        preferences.edit()
                .putString(KEY_IDENTIFIER, device.identifier)
                .putString(KEY_DEVICE_AUTH_TOKEN, device.deviceAuthToken)
                .putString(KEY_DISPLAY_NAME, device.displayName)
                .putString(KEY_FCM_TOKEN, fcmToken)
                .putBoolean(KEY_PENDING_POLICY_FETCH, true)
                .apply();
    }

    private String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? "http://10.0.2.2:4567" : result;
    }
}

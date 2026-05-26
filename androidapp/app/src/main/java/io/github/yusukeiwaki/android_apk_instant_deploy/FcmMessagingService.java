package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.content.Intent;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONException;

import java.io.IOException;
import java.util.Map;

public final class FcmMessagingService extends FirebaseMessagingService {
    static final String ACTION_POLICY_UPDATED = BuildConfig.APPLICATION_ID + ".POLICY_UPDATED";

    private static final String TAG = "FcmMessagingService";
    private static final String TYPE_POLICY_UPDATED = "POLICY_UPDATED";

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> data = message.getData();
        if (TYPE_POLICY_UPDATED.equals(data.get("type"))) {
            markPolicyFetchPending();
        }
    }

    @Override
    public void onDeletedMessages() {
        markPolicyFetchPending();
    }

    @Override
    public void onNewToken(String token) {
        AppStore store = new AppStore(this);
        store.saveFcmToken(token);
        if (!store.isRegistered()) {
            return;
        }

        new Thread(() -> {
            try {
                new ApiClient(store.serverBaseUrl(), store.deviceAuthToken()).updateFcmPushToken(token);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "Failed to update FCM token on server", e);
            }
        }, "fcm-token-refresh").start();
    }

    private void markPolicyFetchPending() {
        new AppStore(this).setPendingPolicyFetch(true);
        sendBroadcast(new Intent(ACTION_POLICY_UPDATED).setPackage(getPackageName()));
    }
}

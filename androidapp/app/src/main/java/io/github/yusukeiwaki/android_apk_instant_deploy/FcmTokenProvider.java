package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.concurrent.TimeUnit;

final class FcmTokenProvider {
    String obtainToken(Context context) throws Exception {
        return Tasks.await(FirebaseMessaging.getInstance().getToken(), 30, TimeUnit.SECONDS);
    }
}

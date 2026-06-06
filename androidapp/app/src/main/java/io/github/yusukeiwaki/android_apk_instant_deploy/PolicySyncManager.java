package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.content.Context;

import java.util.concurrent.TimeUnit;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

final class PolicySyncManager {
    static final String UNIQUE_WORK_NAME = "policy-sync";

    void markPendingAndEnqueue(Context context) {
        Context appContext = context.getApplicationContext();
        AppStore store = new AppStore(appContext);
        store.setPendingPolicyFetch(true);
        if (store.isRegistered()) {
            enqueue(appContext);
        }
    }

    void enqueue(Context context) {
        Context appContext = context.getApplicationContext();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PolicySyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();

        WorkManager.getInstance(appContext).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request);
    }
}

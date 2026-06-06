package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.content.Context;

import org.json.JSONException;

import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class PolicySyncWorker extends Worker {
    public PolicySyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        AppStore store = new AppStore(context);
        if (!store.isRegistered()) {
            return Result.success();
        }
        if (!store.pendingPolicyFetch() && !store.fetchedPolicyJson().isEmpty()) {
            return Result.success();
        }

        try {
            ApiClient apiClient = new ApiClient(store.serverBaseUrl(), store.deviceAuthToken());
            ApiClient.PolicySnapshot snapshot = apiClient.fetchPolicy();
            store.saveFetchedPolicy(snapshot.rawJson);
            new PolicySyncEngine(context).startEarlyDownloads(snapshot);
            return Result.success();
        } catch (ApiClient.ApiException e) {
            return isRetryableHttpStatus(e.httpStatus) ? Result.retry() : Result.failure();
        } catch (IOException e) {
            return Result.retry();
        } catch (JSONException | RuntimeException e) {
            return Result.failure();
        }
    }

    private boolean isRetryableHttpStatus(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }
}

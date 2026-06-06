package io.github.yusukeiwaki.android_apk_instant_deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PolicySyncManagerTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteSharedPreferences("apkdist");
        WorkManagerTestInitHelper.initializeTestWorkManager(context);
    }

    @Test
    public void markPendingAndEnqueueStoresPendingFlagAndSchedulesWorkForRegisteredDevice() throws Exception {
        AppStore store = new AppStore(context);
        store.saveRegistration(new ApiClient.RegisteredDevice("device-1", "auth-token", "QA Device"), "fcm-token");
        store.setPendingPolicyFetch(false);

        new PolicySyncManager().markPendingAndEnqueue(context);

        assertTrue(store.pendingPolicyFetch());
        List<WorkInfo> workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(PolicySyncManager.UNIQUE_WORK_NAME)
                .get();
        assertEquals(1, workInfos.size());
    }

    @Test
    public void markPendingAndEnqueueDoesNotScheduleWorkBeforeRegistration() throws Exception {
        AppStore store = new AppStore(context);

        new PolicySyncManager().markPendingAndEnqueue(context);

        assertTrue(store.pendingPolicyFetch());
        List<WorkInfo> workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(PolicySyncManager.UNIQUE_WORK_NAME)
                .get();
        assertFalse(workInfos.stream().anyMatch(info -> !info.getState().isFinished()));
    }
}

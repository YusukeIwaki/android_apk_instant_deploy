package io.github.yusukeiwaki.android_apk_instant_deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.TestListenableWorkerBuilder;
import androidx.work.testing.WorkManagerTestInitHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.List;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PolicySyncWorkerTest {
    private Context context;
    private MockWebServer server;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();
        context.deleteSharedPreferences("apkdist");
        context.deleteSharedPreferences("apkdist_downloads");
        WorkManagerTestInitHelper.initializeTestWorkManager(context);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void workerFetchesPolicyAndEnqueuesDownloadWithoutActivity() throws Exception {
        startPolicyServer(policyJson());
        AppStore store = new AppStore(context);
        store.setServerBaseUrl(baseUrl());
        store.saveRegistration(new ApiClient.RegisteredDevice("device-1", "auth-token", "QA Device"), "fcm-token");
        store.setPendingPolicyFetch(true);

        PolicySyncWorker worker = TestListenableWorkerBuilder.from(context, PolicySyncWorker.class).build();
        worker.doWork();

        assertFalse(store.pendingPolicyFetch());
        assertEquals(policyJson(), store.fetchedPolicyJson());

        ApkDownloadStore.PendingApkDownload download = new ApkDownloadStore(context).find(301, 7);
        assertNotNull(download);
        assertEquals("com.example.required", download.packageName);
        assertEquals(ApkDownloadStore.STATE_ENQUEUED, download.state);

        List<WorkInfo> workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(ApkDownloadManager.workName(301, 7))
                .get();
        assertEquals(1, workInfos.size());
    }

    private void startPolicyServer(String responseBody) throws IOException {
        server = new MockWebServer();
        server.enqueue(new MockResponse.Builder()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .body(responseBody)
                .build());
        server.start();
    }

    private String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    private String policyJson() {
        return "{"
                + "\"device_policy_revision\":{\"id\":10},"
                + "\"updated_at\":\"2026-06-04T00:00:00Z\","
                + "\"entries\":[{"
                + "\"app\":{\"id\":101,\"package_name\":\"com.example.required\",\"display_name\":\"Required App\"},"
                + "\"install_mode\":\"FORCE_INSTALLED\","
                + "\"install\":{"
                + "\"release\":{\"id\":301},"
                + "\"version_code\":7,"
                + "\"version_name\":\"1.0.7\","
                + "\"artifact_sha256\":\"abcdef\""
                + "}"
                + "}]"
                + "}";
    }
}

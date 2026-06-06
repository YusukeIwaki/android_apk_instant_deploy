package io.github.yusukeiwaki.android_apk_instant_deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class InstallNotificationReceiverTest {
    private Context context;
    private ApkDownloadStore downloadStore;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteSharedPreferences("apkdist");
        context.deleteSharedPreferences("apkdist_downloads");
        WorkManagerTestInitHelper.initializeTestWorkManager(context);
        downloadStore = new ApkDownloadStore(context);
    }

    @Test
    public void installCancelForCurrentPolicyKeepsDownloadedApk() throws Exception {
        File oldApk = writeApk("old.apk");
        downloadStore.saveEnqueued(
                "com.example.required",
                301,
                7,
                ApkDownloadManager.workName(301, 7),
                oldApk.getAbsolutePath(),
                true,
                true
        );
        downloadStore.markInstalling(301, 7, oldApk.length());
        new AppStore(context).saveFetchedPolicy(policyJson(301, 7));

        new InstallNotificationReceiver().onReceive(context, abortedIntent(301, 7));

        ApkDownloadStore.PendingApkDownload restored = downloadStore.find(301, 7);
        assertNotNull(restored);
        assertEquals(ApkDownloadStore.STATE_DOWNLOADED, restored.state);
        assertFalse(restored.installAfterDownload);
        assertTrue(oldApk.isFile());
    }

    @Test
    public void installCancelAfterPolicyUpdatedStartsDownloadForCurrentRelease() throws Exception {
        File oldApk = writeApk("old.apk");
        downloadStore.saveEnqueued(
                "com.example.required",
                301,
                7,
                ApkDownloadManager.workName(301, 7),
                oldApk.getAbsolutePath(),
                true,
                true
        );
        downloadStore.markInstalling(301, 7, oldApk.length());
        new AppStore(context).saveFetchedPolicy(policyJson(302, 8));

        new InstallNotificationReceiver().onReceive(context, abortedIntent(301, 7));

        assertNull(downloadStore.find(301, 7));
        assertFalse(oldApk.exists());
        ApkDownloadStore.PendingApkDownload current = downloadStore.find(302, 8);
        assertNotNull(current);
        assertEquals(ApkDownloadStore.STATE_ENQUEUED, current.state);
        assertFalse(current.installAfterDownload);

        List<WorkInfo> workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(ApkDownloadManager.workName(302, 8))
                .get();
        assertEquals(1, workInfos.size());
    }

    private Intent abortedIntent(int releaseId, int versionCode) {
        return new Intent(context, InstallNotificationReceiver.class)
                .setAction(InstallNotificationReceiver.ACTION_INSTALL_COMMITTED)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
                .putExtra(InstallNotificationReceiver.EXTRA_PACKAGE_NAME, "com.example.required")
                .putExtra(InstallNotificationReceiver.EXTRA_RELEASE_ID, releaseId)
                .putExtra(InstallNotificationReceiver.EXTRA_VERSION_CODE, versionCode);
    }

    private File writeApk(String fileName) throws Exception {
        File file = new File(context.getFilesDir(), fileName);
        if (file.exists()) {
            assertTrue(file.delete());
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{1, 2, 3});
        }
        return file;
    }

    private String policyJson(int releaseId, int versionCode) {
        return "{"
                + "\"device_policy_revision\":{\"id\":" + releaseId + "},"
                + "\"updated_at\":\"2026-06-06T00:00:00Z\","
                + "\"entries\":[{"
                + "\"app\":{\"id\":101,\"package_name\":\"com.example.required\",\"display_name\":\"Required App\"},"
                + "\"install_mode\":\"FORCE_INSTALLED\","
                + "\"install\":{"
                + "\"release\":{\"id\":" + releaseId + "},"
                + "\"version_code\":" + versionCode + ","
                + "\"version_name\":\"1.0\","
                + "\"artifact_sha256\":\"abcdef\""
                + "}"
                + "}]"
                + "}";
    }
}

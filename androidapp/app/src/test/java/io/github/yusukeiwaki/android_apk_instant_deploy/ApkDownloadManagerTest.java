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

import java.io.File;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ApkDownloadManagerTest {
    private Context context;
    private ApkDownloadStore store;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteSharedPreferences("apkdist_downloads");
        WorkManagerTestInitHelper.initializeTestWorkManager(context);
        store = new ApkDownloadStore(context);
    }

    @Test
    public void enqueueDoesNotOverwriteDownloadedStateOrCreateDuplicateWork() throws Exception {
        File apkFile = new File(context.getFilesDir(), "downloaded.apk");
        if (apkFile.exists()) {
            assertTrue(apkFile.delete());
        }
        assertTrue(apkFile.createNewFile());
        store.saveDownloaded("com.example.required", 301, 7, ApkDownloadManager.workName(301, 7), apkFile.getAbsolutePath(), 123);

        ApkDownloadStore.PendingApkDownload result = new ApkDownloadManager().enqueue(context, "com.example.required", 301, 7);

        assertEquals(ApkDownloadStore.STATE_DOWNLOADED, result.state);
        assertFalse(result.installAfterDownload);
        assertFalse(result.allowPackageInstaller);
        List<WorkInfo> workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(ApkDownloadManager.workName(301, 7))
                .get();
        assertTrue(workInfos.isEmpty());
    }

    @Test
    public void enqueueDownloadOnlyDoesNotOverwriteDownloadedStateOrCreateDuplicateWork() throws Exception {
        File apkFile = new File(context.getFilesDir(), "downloaded-only.apk");
        if (apkFile.exists()) {
            assertTrue(apkFile.delete());
        }
        assertTrue(apkFile.createNewFile());
        store.saveDownloaded("com.example.required", 301, 7, ApkDownloadManager.workName(301, 7), apkFile.getAbsolutePath(), 123);

        ApkDownloadStore.PendingApkDownload result = new ApkDownloadManager()
                .enqueueDownloadOnly(context, "com.example.required", 301, 7);

        assertEquals(ApkDownloadStore.STATE_DOWNLOADED, result.state);
        assertFalse(result.installAfterDownload);
        assertFalse(result.allowPackageInstaller);
        List<WorkInfo> workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(ApkDownloadManager.workName(301, 7))
                .get();
        assertTrue(workInfos.isEmpty());
    }

    @Test
    public void managedInstallDisablesPackageInstallerFallback() {
        ApkDownloadStore.PendingApkDownload result = new ApkDownloadManager()
                .enqueueManagedInstall(context, "com.example.required", 301, 7);

        assertEquals(ApkDownloadStore.STATE_ENQUEUED, result.state);
        assertTrue(result.installAfterDownload);
        assertFalse(result.allowPackageInstaller);
    }
}

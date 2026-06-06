package io.github.yusukeiwaki.android_apk_instant_deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.Data;
import androidx.work.testing.TestListenableWorkerBuilder;
import androidx.work.testing.WorkManagerTestInitHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ApkDownloadWorkerTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteSharedPreferences("apkdist_downloads");
        WorkManagerTestInitHelper.initializeTestWorkManager(context);
    }

    @Test
    public void installAfterDownloadWithoutPackageInstallerFallbackStopsAtDownloadedWhenAmapiDoesNotComplete() throws Exception {
        File apkFile = new File(context.getFilesDir(), "managed-only.apk");
        if (apkFile.exists()) {
            assertTrue(apkFile.delete());
        }
        try (FileOutputStream output = new FileOutputStream(apkFile)) {
            output.write(new byte[]{1, 2, 3});
        }

        ApkDownloadStore store = new ApkDownloadStore(context);
        store.saveEnqueued(
                "com.example.required",
                301,
                7,
                ApkDownloadManager.workName(301, 7),
                apkFile.getAbsolutePath(),
                true,
                false
        );
        Data input = new Data.Builder()
                .putString(ApkDownloadWorker.KEY_PACKAGE_NAME, "com.example.required")
                .putInt(ApkDownloadWorker.KEY_RELEASE_ID, 301)
                .putInt(ApkDownloadWorker.KEY_VERSION_CODE, 7)
                .putString(ApkDownloadWorker.KEY_FILE_PATH, apkFile.getAbsolutePath())
                .putBoolean(ApkDownloadWorker.KEY_INSTALL_AFTER_DOWNLOAD, true)
                .putBoolean(ApkDownloadWorker.KEY_ALLOW_PACKAGE_INSTALLER, false)
                .build();

        ApkDownloadWorker worker = TestListenableWorkerBuilder
                .from(context, ApkDownloadWorker.class)
                .setInputData(input)
                .build();
        worker.doWork();

        ApkDownloadStore.PendingApkDownload download = store.find(301, 7);
        assertNotNull(download);
        assertEquals(ApkDownloadStore.STATE_DOWNLOADED, download.state);
        assertFalse(download.installAfterDownload);
        assertFalse(download.allowPackageInstaller);
    }
}

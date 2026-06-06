package io.github.yusukeiwaki.android_apk_instant_deploy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class DownloadProgressRefreshTest {
    private ApkDownloadStore store;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteSharedPreferences("apkdist_downloads");
        store = new ApkDownloadStore(context);
    }

    @Test
    public void downloadedApkDoesNotScheduleProgressRefresh() {
        store.saveDownloaded(
                "com.example.required",
                301,
                7,
                ApkDownloadManager.workName(301, 7),
                "/tmp/downloaded.apk",
                123
        );

        assertFalse(DownloadProgressRefresh.shouldScheduleFor(store.find(301, 7)));
    }

    @Test
    public void blockingDownloadSchedulesProgressRefresh() {
        store.saveEnqueued(
                "com.example.required",
                301,
                7,
                ApkDownloadManager.workName(301, 7),
                "/tmp/downloading.apk",
                false
        );

        assertTrue(DownloadProgressRefresh.shouldScheduleFor(store.find(301, 7)));
    }
}

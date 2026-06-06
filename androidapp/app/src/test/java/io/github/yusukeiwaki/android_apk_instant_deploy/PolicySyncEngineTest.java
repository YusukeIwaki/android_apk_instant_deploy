package io.github.yusukeiwaki.android_apk_instant_deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PolicySyncEngineTest {
    private Context context;
    private ApkDownloadStore downloadStore;
    private Map<String, PolicySyncEngine.InstallState> installStates;
    private RecordingDownloadStarter downloadStarter;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteSharedPreferences("apkdist_downloads");
        downloadStore = new ApkDownloadStore(context);
        installStates = new HashMap<>();
        downloadStarter = new RecordingDownloadStarter();
    }

    @Test
    public void forceInstalledMissingStartsDownloadOnlyWhenCustomInstallIsUnavailable() {
        PolicySyncEngine engine = engine(false);
        ApiClient.PolicyEntry entry = entry("FORCE_INSTALLED", "com.example.required", 301, 7, "");

        PolicySyncEngine.SyncStats stats = engine.startEarlyDownloads(snapshot(entry));

        assertEquals(1, stats.totalStarted());
        assertEquals(Arrays.asList("download-only:com.example.required:301:7"), downloadStarter.calls);
    }

    @Test
    public void forceInstalledMissingStartsDownloadAndInstallWhenCustomInstallIsAvailable() {
        PolicySyncEngine engine = engine(true);
        ApiClient.PolicyEntry entry = entry("FORCE_INSTALLED", "com.example.required", 301, 7, "");

        PolicySyncEngine.SyncStats stats = engine.startEarlyDownloads(snapshot(entry));

        assertEquals(1, stats.totalStarted());
        assertEquals(Arrays.asList("download-install:com.example.required:301:7"), downloadStarter.calls);
    }

    @Test
    public void availableMissingDoesNotStartEarlyDownload() {
        PolicySyncEngine engine = engine(false);
        ApiClient.PolicyEntry entry = entry("AVAILABLE", "com.example.optional", 302, 3, "");

        PolicySyncEngine.SyncStats stats = engine.startEarlyDownloads(snapshot(entry));

        assertEquals(0, stats.totalStarted());
        assertTrue(downloadStarter.calls.isEmpty());
    }

    @Test
    public void availableInstalledWithOlderVersionStartsDownloadOnly() {
        installStates.put("com.example.optional", PolicySyncEngine.InstallState.installed(2, ""));
        PolicySyncEngine engine = engine(false);
        ApiClient.PolicyEntry entry = entry("AVAILABLE", "com.example.optional", 302, 3, "");

        PolicySyncEngine.SyncStats stats = engine.startEarlyDownloads(snapshot(entry));

        assertEquals(1, stats.totalStarted());
        assertEquals(Arrays.asList("download-only:com.example.optional:302:3"), downloadStarter.calls);
    }

    @Test
    public void sameVersionWithDifferentChecksumStartsDownloadOnly() {
        installStates.put("com.example.optional", PolicySyncEngine.InstallState.installed(3, "old-sha"));
        PolicySyncEngine engine = engine(false);
        ApiClient.PolicyEntry entry = entry("AVAILABLE", "com.example.optional", 302, 3, "new-sha");

        PolicySyncEngine.SyncStats stats = engine.startEarlyDownloads(snapshot(entry));

        assertEquals(1, stats.totalStarted());
        assertEquals(Arrays.asList("download-only:com.example.optional:302:3"), downloadStarter.calls);
    }

    @Test
    public void existingBlockingDownloadPreventsDuplicateEnqueue() {
        downloadStore.saveEnqueued(
                "com.example.required",
                301,
                7,
                ApkDownloadManager.workName(301, 7),
                "/tmp/required.apk",
                false
        );
        PolicySyncEngine engine = engine(false);
        ApiClient.PolicyEntry entry = entry("FORCE_INSTALLED", "com.example.required", 301, 7, "");

        PolicySyncEngine.SyncStats stats = engine.startEarlyDownloads(snapshot(entry));

        assertEquals(0, stats.totalStarted());
        assertTrue(downloadStarter.calls.isEmpty());
    }

    private PolicySyncEngine engine(boolean canUseCustomInstall) {
        return new PolicySyncEngine(
                downloadStore,
                packageName -> installStates.getOrDefault(packageName, PolicySyncEngine.InstallState.missing()),
                downloadStarter,
                () -> canUseCustomInstall
        );
    }

    private ApiClient.PolicySnapshot snapshot(ApiClient.PolicyEntry... entries) {
        return new ApiClient.PolicySnapshot(
                10,
                "2026-06-04T00:00:00Z",
                "{}",
                Arrays.asList(entries)
        );
    }

    private ApiClient.PolicyEntry entry(String installMode, String packageName, int releaseId, int versionCode, String artifactSha256) {
        return new ApiClient.PolicyEntry(
                releaseId,
                packageName,
                packageName,
                installMode,
                releaseId,
                versionCode,
                "1.0",
                artifactSha256
        );
    }

    private static final class RecordingDownloadStarter implements PolicySyncEngine.DownloadStarter {
        final List<String> calls = new ArrayList<>();

        @Override
        public void startDownloadAndInstall(String packageName, int releaseId, int versionCode) {
            calls.add("download-install:" + packageName + ":" + releaseId + ":" + versionCode);
        }

        @Override
        public void startDownloadOnly(String packageName, int releaseId, int versionCode) {
            calls.add("download-only:" + packageName + ":" + releaseId + ":" + versionCode);
        }
    }
}

package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.FileInputStream;
import java.security.MessageDigest;

final class PolicySyncEngine {
    private final ApkDownloadStore downloadStore;
    private final InstallStateReader installStateReader;
    private final DownloadStarter downloadStarter;
    private final CustomInstallAvailability customInstallAvailability;

    PolicySyncEngine(Context context) {
        Context appContext = context.getApplicationContext();
        this.downloadStore = new ApkDownloadStore(appContext);
        this.installStateReader = new PackageManagerInstallStateReader(appContext.getPackageManager());
        this.downloadStarter = new ApkDownloadStarter(appContext);
        this.customInstallAvailability = () -> new AmapiCustomAppInstaller().isAvailable(appContext);
    }

    PolicySyncEngine(
            ApkDownloadStore downloadStore,
            InstallStateReader installStateReader,
            DownloadStarter downloadStarter,
            CustomInstallAvailability customInstallAvailability
    ) {
        this.downloadStore = downloadStore;
        this.installStateReader = installStateReader;
        this.downloadStarter = downloadStarter;
        this.customInstallAvailability = customInstallAvailability;
    }

    SyncStats startEarlyDownloads(ApiClient.PolicySnapshot snapshot) {
        SyncStats stats = new SyncStats();
        boolean canUseCustomInstall = customInstallAvailability.canUseCustomInstall();
        for (ApiClient.PolicyEntry entry : snapshot.entries) {
            if (!entry.isForceInstalled() && !entry.isAvailable()) {
                continue;
            }
            if (hasExistingDownload(entry)) {
                continue;
            }

            InstallState installState = installStateReader.read(entry.packageName);
            if (!installActionRequired(entry, installState)) {
                continue;
            }
            if (!entry.isForceInstalled() && !installState.installed) {
                continue;
            }

            if (entry.isForceInstalled() && canUseCustomInstall) {
                downloadStarter.startDownloadAndInstall(entry.packageName, entry.releaseId, entry.versionCode);
                stats.downloadAndInstallStarted += 1;
            } else {
                downloadStarter.startDownloadOnly(entry.packageName, entry.releaseId, entry.versionCode);
                stats.downloadOnlyStarted += 1;
            }
        }
        return stats;
    }

    private boolean hasExistingDownload(ApiClient.PolicyEntry entry) {
        ApkDownloadStore.PendingApkDownload exactDownload = downloadStore.find(entry.releaseId, entry.versionCode);
        if (exactDownload != null && (exactDownload.isBlocking() || exactDownload.isDownloaded())) {
            return true;
        }

        ApkDownloadStore.PendingApkDownload activeReleaseDownload = downloadStore.findActiveForRelease(entry.releaseId);
        return activeReleaseDownload != null && activeReleaseDownload.isBlocking();
    }

    static boolean installActionRequired(ApiClient.PolicyEntry entry, InstallState installState) {
        if (!installState.installed) {
            return true;
        }
        if (installState.versionCode < entry.versionCode) {
            return true;
        }
        if (installState.versionCode == entry.versionCode) {
            return !entry.artifactSha256.isEmpty()
                    && !installState.apkSha256.isEmpty()
                    && !entry.artifactSha256.equalsIgnoreCase(installState.apkSha256);
        }
        return false;
    }

    interface InstallStateReader {
        InstallState read(String packageName);
    }

    interface DownloadStarter {
        void startDownloadAndInstall(String packageName, int releaseId, int versionCode);

        void startDownloadOnly(String packageName, int releaseId, int versionCode);
    }

    interface CustomInstallAvailability {
        boolean canUseCustomInstall();
    }

    static final class InstallState {
        final boolean installed;
        final long versionCode;
        final String apkSha256;

        InstallState(boolean installed, long versionCode, String apkSha256) {
            this.installed = installed;
            this.versionCode = versionCode;
            this.apkSha256 = apkSha256 == null ? "" : apkSha256;
        }

        static InstallState missing() {
            return new InstallState(false, -1, "");
        }

        static InstallState installed(long versionCode, String apkSha256) {
            return new InstallState(true, versionCode, apkSha256);
        }
    }

    static final class SyncStats {
        int downloadAndInstallStarted;
        int downloadOnlyStarted;

        int totalStarted() {
            return downloadAndInstallStarted + downloadOnlyStarted;
        }
    }

    private static final class ApkDownloadStarter implements DownloadStarter {
        private final Context context;

        ApkDownloadStarter(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public void startDownloadAndInstall(String packageName, int releaseId, int versionCode) {
            new ApkDownloadManager().enqueueManagedInstall(context, packageName, releaseId, versionCode);
        }

        @Override
        public void startDownloadOnly(String packageName, int releaseId, int versionCode) {
            new ApkDownloadManager().enqueueDownloadOnly(context, packageName, releaseId, versionCode);
        }
    }

    private static final class PackageManagerInstallStateReader implements InstallStateReader {
        private final PackageManager packageManager;

        PackageManagerInstallStateReader(PackageManager packageManager) {
            this.packageManager = packageManager;
        }

        @Override
        public InstallState read(String packageName) {
            try {
                PackageInfo info = packageManager.getPackageInfo(packageName, 0);
                return InstallState.installed(packageVersionCode(info), installedApkSha256(info));
            } catch (PackageManager.NameNotFoundException e) {
                return InstallState.missing();
            }
        }

        @SuppressWarnings("deprecation")
        private long packageVersionCode(PackageInfo info) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        }

        private String installedApkSha256(PackageInfo info) {
            try {
                if (info.applicationInfo == null || info.applicationInfo.sourceDir == null) {
                    return "";
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileInputStream input = new FileInputStream(info.applicationInfo.sourceDir)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }

                StringBuilder result = new StringBuilder();
                for (byte b : digest.digest()) {
                    result.append(String.format("%02x", b & 0xff));
                }
                return result.toString();
            } catch (Exception e) {
                return "";
            }
        }
    }
}

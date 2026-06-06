package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.content.Context;

import java.io.File;
import java.util.concurrent.TimeUnit;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

final class ApkDownloadManager {
    private static final String DOWNLOAD_DIR = "apk-downloads";

    ApkDownloadStore.PendingApkDownload enqueue(Context context, String packageName, int releaseId, int versionCode) {
        return enqueue(context, packageName, releaseId, versionCode, true, true);
    }

    ApkDownloadStore.PendingApkDownload enqueueManagedInstall(Context context, String packageName, int releaseId, int versionCode) {
        return enqueue(context, packageName, releaseId, versionCode, true, false);
    }

    ApkDownloadStore.PendingApkDownload enqueueDownloadOnly(Context context, String packageName, int releaseId, int versionCode) {
        return enqueue(context, packageName, releaseId, versionCode, false, false);
    }

    ApkDownloadStore.PendingApkDownload recover(Context context, ApkDownloadStore.PendingApkDownload download, boolean installAfterDownload) {
        Context appContext = context.getApplicationContext();
        ApkDownloadStore store = new ApkDownloadStore(appContext);
        String workName = workName(download.releaseId, download.versionCode);
        File apkFile = new File(download.filePath);
        if (!installAfterDownload && apkFile.isFile() && apkFile.length() > 0) {
            store.saveDownloaded(download.packageName, download.releaseId, download.versionCode, workName, apkFile.getAbsolutePath(), apkFile.length());
            return store.find(download.releaseId, download.versionCode);
        }

        boolean allowPackageInstaller = installAfterDownload && download.allowPackageInstaller;
        store.saveEnqueued(download.packageName, download.releaseId, download.versionCode, workName, apkFile.getAbsolutePath(), installAfterDownload, allowPackageInstaller);
        enqueueWork(appContext, download.packageName, download.releaseId, download.versionCode, apkFile, installAfterDownload, allowPackageInstaller);
        return store.find(download.releaseId, download.versionCode);
    }

    private ApkDownloadStore.PendingApkDownload enqueue(Context context, String packageName, int releaseId, int versionCode, boolean installAfterDownload, boolean allowPackageInstaller) {
        Context appContext = context.getApplicationContext();
        ApkDownloadStore store = new ApkDownloadStore(appContext);
        ApkDownloadStore.PendingApkDownload existing = store.find(releaseId, versionCode);
        if (existing != null && (existing.isBlocking() || existing.isDownloaded())) {
            return existing;
        }

        String workName = workName(releaseId, versionCode);
        File apkFile = apkFile(appContext, packageName, releaseId, versionCode);
        if (!installAfterDownload && apkFile.isFile() && apkFile.length() > 0) {
            store.saveDownloaded(packageName, releaseId, versionCode, workName, apkFile.getAbsolutePath(), apkFile.length());
            return store.find(releaseId, versionCode);
        }

        store.saveEnqueued(packageName, releaseId, versionCode, workName, apkFile.getAbsolutePath(), installAfterDownload, allowPackageInstaller);
        enqueueWork(appContext, packageName, releaseId, versionCode, apkFile, installAfterDownload, allowPackageInstaller);
        return store.find(releaseId, versionCode);
    }

    private void enqueueWork(Context appContext, String packageName, int releaseId, int versionCode, File apkFile, boolean installAfterDownload, boolean allowPackageInstaller) {
        Data input = new Data.Builder()
                .putString(ApkDownloadWorker.KEY_PACKAGE_NAME, packageName)
                .putInt(ApkDownloadWorker.KEY_RELEASE_ID, releaseId)
                .putInt(ApkDownloadWorker.KEY_VERSION_CODE, versionCode)
                .putString(ApkDownloadWorker.KEY_FILE_PATH, apkFile.getAbsolutePath())
                .putBoolean(ApkDownloadWorker.KEY_INSTALL_AFTER_DOWNLOAD, installAfterDownload)
                .putBoolean(ApkDownloadWorker.KEY_ALLOW_PACKAGE_INSTALLER, allowPackageInstaller)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ApkDownloadWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(ApkDownloadWorker.TAG)
                .build();

        try {
            WorkManager.getInstance(appContext).enqueueUniqueWork(workName(releaseId, versionCode), ExistingWorkPolicy.KEEP, request);
        } catch (RuntimeException e) {
            new ApkDownloadStore(appContext).remove(releaseId, versionCode);
            throw e;
        }
    }

    void cleanup(Context context, int releaseId, int versionCode) {
        ApkDownloadStore.PendingApkDownload download = new ApkDownloadStore(context).find(releaseId, versionCode);
        if (download != null) {
            cleanup(context, download);
        }
    }

    void cleanup(Context context, String packageName, int releaseId, int versionCode) {
        ApkDownloadStore.PendingApkDownload download = new ApkDownloadStore(context).find(releaseId, versionCode);
        if (download != null) {
            cleanup(context, download);
            return;
        }

        Context appContext = context.getApplicationContext();
        String workName = workName(releaseId, versionCode);
        try {
            WorkManager.getInstance(appContext).cancelUniqueWork(workName);
        } catch (RuntimeException ignored) {
        }
        File apkFile = apkFile(appContext, packageName, releaseId, versionCode);
        deleteQuietly(apkFile);
        deleteQuietly(temporaryFile(apkFile));
    }

    void cleanup(Context context, ApkDownloadStore.PendingApkDownload download) {
        Context appContext = context.getApplicationContext();
        try {
            WorkManager.getInstance(appContext).cancelUniqueWork(download.workName);
        } catch (RuntimeException ignored) {
        }
        deleteQuietly(new File(download.filePath));
        deleteQuietly(temporaryFile(new File(download.filePath)));
        new ApkDownloadStore(appContext).remove(download);
    }

    static File temporaryFile(File apkFile) {
        return new File(apkFile.getAbsolutePath() + ".part");
    }

    static String workName(int releaseId, int versionCode) {
        return "apk-download-" + releaseId + "-" + versionCode;
    }

    private static File apkFile(Context context, String packageName, int releaseId, int versionCode) {
        File directory = new File(context.getFilesDir(), DOWNLOAD_DIR);
        return new File(directory, safeFileName(packageName) + "-" + releaseId + "-" + versionCode + ".apk");
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            file.delete();
        } catch (RuntimeException ignored) {
        }
    }

    private static String safeFileName(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "app";
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < packageName.length(); index++) {
            char c = packageName.charAt(index);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                result.append(c);
            } else {
                result.append('_');
            }
        }
        return result.length() == 0 ? "app" : result.toString();
    }
}

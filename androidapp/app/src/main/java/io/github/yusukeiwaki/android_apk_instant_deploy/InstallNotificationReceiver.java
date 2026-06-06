package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import org.json.JSONException;

import java.io.File;

public final class InstallNotificationReceiver extends BroadcastReceiver {
    static final String ACTION_INSTALL_COMMITTED = "io.github.yusukeiwaki.android_apk_instant_deploy.INSTALL_COMMITTED";
    static final String ACTION_UNINSTALL_COMMITTED = "io.github.yusukeiwaki.android_apk_instant_deploy.UNINSTALL_COMMITTED";
    static final String EXTRA_PACKAGE_NAME = "package_name";
    static final String EXTRA_RELEASE_ID = "release_id";
    static final String EXTRA_VERSION_CODE = "version_code";

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean uninstall = ACTION_UNINSTALL_COMMITTED.equals(intent.getAction());
        if (!ACTION_INSTALL_COMMITTED.equals(intent.getAction()) && !uninstall) {
            return;
        }

        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            if (!uninstall) {
                cleanupDownload(context, intent);
                RequiredAppNotificationReceiver.cancelRequiredInstallNotification(
                        context,
                        packageName,
                        intent.getIntExtra(EXTRA_VERSION_CODE, -1)
                );
            }
            Toast.makeText(context, packageName + (uninstall ? " をアンインストールしました" : " をインストールしました"), Toast.LENGTH_LONG).show();
        } else {
            if (!uninstall) {
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED && restoreDownloadedApk(context, intent)) {
                    Toast.makeText(context, "インストールをキャンセルしました", Toast.LENGTH_LONG).show();
                    return;
                }
                cleanupDownload(context, intent);
            }
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, (uninstall ? "アンインストール" : "インストール") + "できませんでした: " + (message == null ? "" : message), Toast.LENGTH_LONG).show();
        }
    }

    private boolean restoreDownloadedApk(Context context, Intent intent) {
        int releaseId = intent.getIntExtra(EXTRA_RELEASE_ID, -1);
        int versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, -1);
        if (releaseId < 0 || versionCode < 0) {
            return false;
        }

        ApkDownloadStore store = new ApkDownloadStore(context);
        ApkDownloadStore.PendingApkDownload download = store.find(releaseId, versionCode);
        if (download == null) {
            return false;
        }

        if (restartDownloadForUpdatedPolicyIfNeeded(context, intent, download)) {
            return true;
        }

        File apkFile = new File(download.filePath);
        if (!apkFile.isFile() || apkFile.length() <= 0) {
            return false;
        }

        store.markDownloaded(releaseId, versionCode, apkFile.length());
        return true;
    }

    private boolean restartDownloadForUpdatedPolicyIfNeeded(Context context, Intent intent, ApkDownloadStore.PendingApkDownload download) {
        String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }

        String fetchedPolicyJson = new AppStore(context).fetchedPolicyJson();
        if (fetchedPolicyJson.isEmpty()) {
            return false;
        }

        try {
            ApiClient.PolicySnapshot snapshot = ApiClient.parsePolicy(fetchedPolicyJson);
            for (ApiClient.PolicyEntry entry : snapshot.entries) {
                if (!packageName.equals(entry.packageName)) {
                    continue;
                }
                if (entry.releaseId == download.releaseId && entry.versionCode == download.versionCode) {
                    return false;
                }
                new ApkDownloadManager().cleanup(context, download);
                new ApkDownloadManager().enqueueDownloadOnly(context, entry.packageName, entry.releaseId, entry.versionCode);
                return true;
            }
        } catch (JSONException | RuntimeException ignored) {
        }
        return false;
    }

    private void cleanupDownload(Context context, Intent intent) {
        int releaseId = intent.getIntExtra(EXTRA_RELEASE_ID, -1);
        int versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, -1);
        if (releaseId < 0 || versionCode < 0) {
            return;
        }
        new ApkDownloadManager().cleanup(context, intent.getStringExtra(EXTRA_PACKAGE_NAME), releaseId, versionCode);
    }
}

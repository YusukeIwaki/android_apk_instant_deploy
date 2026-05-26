package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public final class InstallNotificationReceiver extends BroadcastReceiver {
    static final String ACTION_INSTALL_COMMITTED = "io.github.yusukeiwaki.android_apk_instant_deploy.INSTALL_COMMITTED";
    static final String EXTRA_PACKAGE_NAME = "package_name";
    static final String EXTRA_RELEASE_ID = "release_id";
    static final String EXTRA_VERSION_CODE = "version_code";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_INSTALL_COMMITTED.equals(intent.getAction())) {
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
            cleanupDownload(context, intent);
            Toast.makeText(context, packageName + " をインストールしました", Toast.LENGTH_LONG).show();
        } else {
            cleanupDownload(context, intent);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, "インストールできませんでした: " + (message == null ? "" : message), Toast.LENGTH_LONG).show();
        }
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

package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class ApkInstaller {
    int install(Context context, String packageName, File apkFile, long sizeBytes, int releaseId, int versionCode) throws IOException {
        try (InputStream input = new FileInputStream(apkFile)) {
            return install(context, packageName, input, sizeBytes, releaseId, versionCode);
        }
    }

    private int install(Context context, String packageName, InputStream apkInput, long sizeBytes, int releaseId, int versionCode) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        if (sizeBytes > 0) {
            params.setSize(sizeBytes);
        }

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        boolean committed = false;
        try {
            try (OutputStream output = session.openWrite(packageName + ".apk", 0, sizeBytes > 0 ? sizeBytes : -1)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = apkInput.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                session.fsync(output);
            }

            Intent callback = new Intent(context, InstallNotificationReceiver.class)
                    .setAction(InstallNotificationReceiver.ACTION_INSTALL_COMMITTED)
                    .putExtra(InstallNotificationReceiver.EXTRA_PACKAGE_NAME, packageName)
                    .putExtra(InstallNotificationReceiver.EXTRA_RELEASE_ID, releaseId)
                    .putExtra(InstallNotificationReceiver.EXTRA_VERSION_CODE, versionCode);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, sessionId, callback, flags);
            session.commit(pendingIntent.getIntentSender());
            committed = true;
            return sessionId;
        } finally {
            if (!committed) {
                try {
                    session.abandon();
                } catch (RuntimeException ignored) {
                }
            }
            session.close();
        }
    }
}

package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.service.notification.StatusBarNotification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

public final class RequiredAppNotificationReceiver extends BroadcastReceiver {
    static final String ACTION_OPEN_REQUIRED_APP = BuildConfig.APPLICATION_ID + ".OPEN_REQUIRED_APP";
    static final String ACTION_INSTALL_REQUIRED_APP = BuildConfig.APPLICATION_ID + ".INSTALL_REQUIRED_APP";
    static final String KIND_INSTALL_PERMISSION_REQUIRED = "INSTALL_PERMISSION_REQUIRED";
    static final String EXTRA_PACKAGE_NAME = "package_name";
    static final String EXTRA_RELEASE_ID = "release_id";
    static final String EXTRA_VERSION_CODE = "version_code";

    private static final String CHANNEL_ID = "required_app_install";
    private static final String CHANNEL_NAME = "必須アプリのインストール";
    private static final String NOTIFICATION_TAG_PREFIX = "required-install:";
    private static final int REQUEST_OPEN_OFFSET = 10_000;
    private static final int REQUEST_INSTALL_OFFSET = 20_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_INSTALL_REQUIRED_APP.equals(intent.getAction())) {
            return;
        }

        String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
        int releaseId = intent.getIntExtra(EXTRA_RELEASE_ID, -1);
        int versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, -1);
        if (packageName == null || packageName.isEmpty() || releaseId < 0 || versionCode < 0) {
            return;
        }

        if (!new AmapiCustomAppInstaller().isAvailable(context) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(settings);
            Toast.makeText(context, "不明なアプリのインストールを許可してから再試行してください", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            new ApkDownloadManager().enqueue(context, packageName, releaseId, versionCode);
            cancelRequiredInstallNotification(context, packageName, versionCode);
            Toast.makeText(context, "ダウンロードを開始しました", Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            Toast.makeText(context, "ダウンロードを開始できませんでした", Toast.LENGTH_LONG).show();
        }
    }

    static void showRequiredInstallNotification(Context context, String packageName, String displayName, int releaseId, int versionCode) {
        Context appContext = context.getApplicationContext();
        ensureChannel(appContext);

        int notificationId = notificationId(packageName, versionCode);
        Intent openIntent = new Intent(appContext, MainActivity.class)
                .setAction(ACTION_OPEN_REQUIRED_APP)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_RELEASE_ID, releaseId)
                .putExtra(EXTRA_VERSION_CODE, versionCode);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                appContext,
                notificationId + REQUEST_OPEN_OFFSET,
                openIntent,
                pendingIntentFlags()
        );

        Intent installIntent = new Intent(appContext, RequiredAppNotificationReceiver.class)
                .setAction(ACTION_INSTALL_REQUIRED_APP)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_RELEASE_ID, releaseId)
                .putExtra(EXTRA_VERSION_CODE, versionCode);
        PendingIntent installPendingIntent = PendingIntent.getBroadcast(
                appContext,
                notificationId + REQUEST_INSTALL_OFFSET,
                installIntent,
                pendingIntentFlags()
        );

        String appName = displayName == null || displayName.isEmpty() ? packageName : displayName;
        String body = appName + " をインストールしてください。";
        Notification notification = new Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("必須アプリのインストールが必要です")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.stat_sys_download_done, "インストール", installPendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();

        notificationManager(appContext).notify(notificationTag(packageName), notificationId, notification);
    }

    static void cancelRequiredInstallNotification(Context context, String packageName, int versionCode) {
        notificationManager(context.getApplicationContext()).cancel(notificationTag(packageName), notificationId(packageName, versionCode));
    }

    static void cancelAllRequiredInstallNotifications(Context context) {
        NotificationManager manager = notificationManager(context.getApplicationContext());
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            String tag = notification.getTag();
            if (tag != null && tag.startsWith(NOTIFICATION_TAG_PREFIX)) {
                manager.cancel(tag, notification.getId());
            }
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("必須アプリのインストールが必要なときに表示します");
        channel.setShowBadge(true);
        notificationManager(context).createNotificationChannel(channel);
    }

    private static NotificationManager notificationManager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private static String notificationTag(String packageName) {
        return NOTIFICATION_TAG_PREFIX + packageName;
    }

    private static int notificationId(String packageName, int versionCode) {
        return (packageName + ":" + versionCode).hashCode();
    }
}

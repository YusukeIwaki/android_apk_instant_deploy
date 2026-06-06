package io.github.yusukeiwaki.android_apk_instant_deploy;

final class DownloadProgressRefresh {
    private DownloadProgressRefresh() {
    }

    static boolean shouldScheduleFor(ApkDownloadStore.PendingApkDownload download) {
        return download != null && download.isBlocking();
    }
}

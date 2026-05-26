package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ApkDownloadStore {
    static final int STATE_ENQUEUED = 1;
    static final int STATE_RUNNING = 2;
    static final int STATE_INSTALLING = 3;

    private static final String PREFS = "apkdist_downloads";
    private static final String KEY_PACKAGE_NAME_PREFIX = "package_name:";
    private static final String KEY_WORK_NAME_PREFIX = "work_name:";
    private static final String KEY_FILE_PATH_PREFIX = "file_path:";
    private static final String KEY_STATE_PREFIX = "state:";
    private static final String KEY_DOWNLOADED_BYTES_PREFIX = "downloaded_bytes:";
    private static final String KEY_TOTAL_BYTES_PREFIX = "total_bytes:";
    private static final String KEY_UPDATED_AT_PREFIX = "updated_at:";

    private final SharedPreferences preferences;

    ApkDownloadStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void saveEnqueued(String packageName, int releaseId, int versionCode, String workName, String filePath) {
        String key = downloadKey(releaseId, versionCode);
        preferences.edit()
                .putString(packageNameKey(key), packageName)
                .putString(workNameKey(key), workName)
                .putString(filePathKey(key), filePath)
                .putInt(stateKey(key), STATE_ENQUEUED)
                .putLong(downloadedBytesKey(key), 0)
                .putLong(totalBytesKey(key), -1)
                .putLong(updatedAtKey(key), System.currentTimeMillis())
                .apply();
    }

    void markEnqueued(int releaseId, int versionCode) {
        String key = downloadKey(releaseId, versionCode);
        if (!preferences.contains(stateKey(key))) {
            return;
        }
        preferences.edit()
                .putInt(stateKey(key), STATE_ENQUEUED)
                .putLong(updatedAtKey(key), System.currentTimeMillis())
                .apply();
    }

    void markRunning(int releaseId, int versionCode, long downloadedBytes, long totalBytes) {
        updateProgress(releaseId, versionCode, STATE_RUNNING, downloadedBytes, totalBytes);
    }

    void markInstalling(int releaseId, int versionCode, long sizeBytes) {
        updateProgress(releaseId, versionCode, STATE_INSTALLING, sizeBytes, sizeBytes);
    }

    PendingApkDownload find(int releaseId, int versionCode) {
        return find(downloadKey(releaseId, versionCode));
    }

    PendingApkDownload findActiveForRelease(int releaseId) {
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String rawKey = entry.getKey();
            if (!rawKey.startsWith(KEY_STATE_PREFIX) || !(entry.getValue() instanceof Integer)) {
                continue;
            }
            String key = rawKey.substring(KEY_STATE_PREFIX.length());
            if (!key.startsWith(releaseId + ":")) {
                continue;
            }
            PendingApkDownload download = find(key);
            if (download != null && download.isBlocking()) {
                return download;
            }
        }
        return null;
    }

    List<PendingApkDownload> all() {
        List<PendingApkDownload> downloads = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String rawKey = entry.getKey();
            if (!rawKey.startsWith(KEY_STATE_PREFIX)) {
                continue;
            }
            PendingApkDownload download = find(rawKey.substring(KEY_STATE_PREFIX.length()));
            if (download != null && download.isBlocking()) {
                downloads.add(download);
            }
        }
        return downloads;
    }

    void remove(int releaseId, int versionCode) {
        remove(downloadKey(releaseId, versionCode));
    }

    void remove(PendingApkDownload download) {
        remove(downloadKey(download.releaseId, download.versionCode));
    }

    private void updateProgress(int releaseId, int versionCode, int state, long downloadedBytes, long totalBytes) {
        String key = downloadKey(releaseId, versionCode);
        if (!preferences.contains(stateKey(key))) {
            return;
        }
        preferences.edit()
                .putInt(stateKey(key), state)
                .putLong(downloadedBytesKey(key), Math.max(0, downloadedBytes))
                .putLong(totalBytesKey(key), totalBytes)
                .putLong(updatedAtKey(key), System.currentTimeMillis())
                .apply();
    }

    private PendingApkDownload find(String key) {
        String packageName = preferences.getString(packageNameKey(key), "");
        String workName = preferences.getString(workNameKey(key), "");
        String filePath = preferences.getString(filePathKey(key), "");
        if (packageName == null || packageName.isEmpty() || workName == null || workName.isEmpty()) {
            return null;
        }

        int separator = key.indexOf(':');
        if (separator <= 0 || separator >= key.length() - 1) {
            return null;
        }
        try {
            int releaseId = Integer.parseInt(key.substring(0, separator));
            int versionCode = Integer.parseInt(key.substring(separator + 1));
            int state = preferences.getInt(stateKey(key), STATE_ENQUEUED);
            long downloadedBytes = preferences.getLong(downloadedBytesKey(key), 0);
            long totalBytes = preferences.getLong(totalBytesKey(key), -1);
            long updatedAt = preferences.getLong(updatedAtKey(key), 0);
            return new PendingApkDownload(packageName, releaseId, versionCode, workName, filePath, state, downloadedBytes, totalBytes, updatedAt);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void remove(String key) {
        preferences.edit()
                .remove(packageNameKey(key))
                .remove(workNameKey(key))
                .remove(filePathKey(key))
                .remove(stateKey(key))
                .remove(downloadedBytesKey(key))
                .remove(totalBytesKey(key))
                .remove(updatedAtKey(key))
                .apply();
    }

    private String downloadKey(int releaseId, int versionCode) {
        return releaseId + ":" + versionCode;
    }

    private String packageNameKey(String key) {
        return KEY_PACKAGE_NAME_PREFIX + key;
    }

    private String workNameKey(String key) {
        return KEY_WORK_NAME_PREFIX + key;
    }

    private String filePathKey(String key) {
        return KEY_FILE_PATH_PREFIX + key;
    }

    private String stateKey(String key) {
        return KEY_STATE_PREFIX + key;
    }

    private String downloadedBytesKey(String key) {
        return KEY_DOWNLOADED_BYTES_PREFIX + key;
    }

    private String totalBytesKey(String key) {
        return KEY_TOTAL_BYTES_PREFIX + key;
    }

    private String updatedAtKey(String key) {
        return KEY_UPDATED_AT_PREFIX + key;
    }

    static final class PendingApkDownload {
        final String packageName;
        final int releaseId;
        final int versionCode;
        final String workName;
        final String filePath;
        final int state;
        final long downloadedBytes;
        final long totalBytes;
        final long updatedAt;

        PendingApkDownload(
                String packageName,
                int releaseId,
                int versionCode,
                String workName,
                String filePath,
                int state,
                long downloadedBytes,
                long totalBytes,
                long updatedAt
        ) {
            this.packageName = packageName;
            this.releaseId = releaseId;
            this.versionCode = versionCode;
            this.workName = workName;
            this.filePath = filePath;
            this.state = state;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.updatedAt = updatedAt;
        }

        boolean isBlocking() {
            return state == STATE_ENQUEUED || state == STATE_RUNNING || state == STATE_INSTALLING;
        }

        int progressPercent() {
            if (downloadedBytes < 0 || totalBytes <= 0) {
                return -1;
            }
            return (int) Math.min(100, Math.max(0, downloadedBytes * 100 / totalBytes));
        }
    }
}

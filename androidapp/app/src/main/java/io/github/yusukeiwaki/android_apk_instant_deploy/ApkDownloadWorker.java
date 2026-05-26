package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.content.Context;
import android.net.Uri;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class ApkDownloadWorker extends Worker {
    static final String TAG = "apk-download";
    static final String KEY_PACKAGE_NAME = "package_name";
    static final String KEY_RELEASE_ID = "release_id";
    static final String KEY_VERSION_CODE = "version_code";
    static final String KEY_FILE_PATH = "file_path";
    static final String PROGRESS_DOWNLOADED_BYTES = "downloaded_bytes";
    static final String PROGRESS_TOTAL_BYTES = "total_bytes";

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_INTERVAL_BYTES = 512 * 1024;

    public ApkDownloadWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String packageName = getInputData().getString(KEY_PACKAGE_NAME);
        int releaseId = getInputData().getInt(KEY_RELEASE_ID, -1);
        int versionCode = getInputData().getInt(KEY_VERSION_CODE, -1);
        String filePath = getInputData().getString(KEY_FILE_PATH);
        if (packageName == null || packageName.isEmpty() || releaseId < 0 || versionCode < 0 || filePath == null || filePath.isEmpty()) {
            return Result.failure();
        }

        ApkDownloadStore store = new ApkDownloadStore(context);
        File apkFile = new File(filePath);
        try {
            ApiClient.ArtifactUrl artifactUrl = fetchFreshArtifactUrl(context, releaseId);
            DownloadAttempt attempt = downloadArtifact(artifactUrl.url, apkFile, store, releaseId, versionCode);
            if (attempt == DownloadAttempt.RETRY) {
                store.markEnqueued(releaseId, versionCode);
                return Result.retry();
            }
            if (attempt == DownloadAttempt.FAILURE) {
                return failAndCleanup(store, apkFile, releaseId, versionCode);
            }
        } catch (ApiClient.ApiException e) {
            if (isRetryableHttpStatus(e.httpStatus)) {
                store.markEnqueued(releaseId, versionCode);
                return Result.retry();
            }
            return failAndCleanup(store, apkFile, releaseId, versionCode);
        } catch (IOException e) {
            store.markEnqueued(releaseId, versionCode);
            return Result.retry();
        } catch (JSONException | RuntimeException e) {
            return failAndCleanup(store, apkFile, releaseId, versionCode);
        }

        try {
            store.markInstalling(releaseId, versionCode, apkFile.length());
            new ApkInstaller().install(context, packageName, apkFile, apkFile.length(), releaseId, versionCode);
            return Result.success();
        } catch (IOException | RuntimeException e) {
            return failAndCleanup(store, apkFile, releaseId, versionCode);
        }
    }

    private ApiClient.ArtifactUrl fetchFreshArtifactUrl(Context context, int releaseId) throws IOException, JSONException {
        AppStore appStore = new AppStore(context);
        ApiClient apiClient = new ApiClient(appStore.serverBaseUrl(), appStore.deviceAuthToken());
        return apiClient.getArtifactUrl(releaseId);
    }

    private DownloadAttempt downloadArtifact(
            String artifactUrl,
            File apkFile,
            ApkDownloadStore store,
            int releaseId,
            int versionCode
    ) throws IOException {
        Uri uri = Uri.parse(artifactUrl);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return DownloadAttempt.FAILURE;
        }

        File parent = apkFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create APK download directory.");
        }

        File partFile = ApkDownloadManager.temporaryFile(apkFile);
        long existingBytes = partFile.exists() ? Math.max(0, partFile.length()) : 0;
        Request.Builder requestBuilder = new Request.Builder().url(artifactUrl).get();
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=" + existingBytes + "-");
        }

        try (Response response = ApiClient.sharedHttpClient().newCall(requestBuilder.build()).execute()) {
            if (existingBytes > 0 && response.code() == 416) {
                deleteQuietly(partFile);
                return DownloadAttempt.RETRY;
            }
            if (!response.isSuccessful()) {
                return isRetryableDownloadStatus(response.code()) ? DownloadAttempt.RETRY : DownloadAttempt.FAILURE;
            }

            ResponseBody body = response.body();
            if (body == null) {
                return DownloadAttempt.RETRY;
            }

            boolean append = existingBytes > 0 && response.code() == 206;
            long startingBytes = append ? existingBytes : 0;
            if (!append && partFile.exists()) {
                deleteQuietly(partFile);
            }

            long totalBytes = totalBytes(response, body.contentLength(), startingBytes);
            streamBody(body, partFile, append, store, releaseId, versionCode, startingBytes, totalBytes);
            if (isStopped()) {
                return DownloadAttempt.RETRY;
            }

            if (apkFile.exists() && !apkFile.delete()) {
                throw new IOException("Unable to replace existing APK file.");
            }
            if (!partFile.renameTo(apkFile)) {
                throw new IOException("Unable to finalize downloaded APK file.");
            }
            return DownloadAttempt.SUCCESS;
        }
    }

    private void streamBody(
            ResponseBody body,
            File partFile,
            boolean append,
            ApkDownloadStore store,
            int releaseId,
            int versionCode,
            long startingBytes,
            long totalBytes
    ) throws IOException {
        long downloadedBytes = startingBytes;
        long lastProgressBytes = startingBytes;
        store.markRunning(releaseId, versionCode, downloadedBytes, totalBytes);
        setProgress(downloadedBytes, totalBytes);

        try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(partFile, append)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (isStopped()) {
                    return;
                }
                output.write(buffer, 0, read);
                downloadedBytes += read;
                if (downloadedBytes - lastProgressBytes >= PROGRESS_INTERVAL_BYTES) {
                    store.markRunning(releaseId, versionCode, downloadedBytes, totalBytes);
                    setProgress(downloadedBytes, totalBytes);
                    lastProgressBytes = downloadedBytes;
                }
            }
        }

        store.markRunning(releaseId, versionCode, downloadedBytes, totalBytes);
        setProgress(downloadedBytes, totalBytes);
    }

    private long totalBytes(Response response, long contentLength, long startingBytes) {
        if (response.code() == 206) {
            long contentRangeTotal = contentRangeTotal(response.header("Content-Range"));
            if (contentRangeTotal > 0) {
                return contentRangeTotal;
            }
        }
        return contentLength >= 0 ? startingBytes + contentLength : -1;
    }

    private long contentRangeTotal(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        int slashIndex = contentRange.indexOf('/');
        if (slashIndex < 0 || slashIndex >= contentRange.length() - 1) {
            return -1;
        }
        String value = contentRange.substring(slashIndex + 1).trim();
        if ("*".equals(value)) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void setProgress(long downloadedBytes, long totalBytes) {
        setProgressAsync(new Data.Builder()
                .putLong(PROGRESS_DOWNLOADED_BYTES, downloadedBytes)
                .putLong(PROGRESS_TOTAL_BYTES, totalBytes)
                .build());
    }

    private Result failAndCleanup(ApkDownloadStore store, File apkFile, int releaseId, int versionCode) {
        deleteQuietly(apkFile);
        deleteQuietly(ApkDownloadManager.temporaryFile(apkFile));
        store.remove(releaseId, versionCode);
        return Result.failure();
    }

    private boolean isRetryableHttpStatus(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    private boolean isRetryableDownloadStatus(int status) {
        return status == 403 || isRetryableHttpStatus(status);
    }

    private void deleteQuietly(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            file.delete();
        } catch (RuntimeException ignored) {
        }
    }

    private enum DownloadAttempt {
        SUCCESS,
        RETRY,
        FAILURE
    }
}

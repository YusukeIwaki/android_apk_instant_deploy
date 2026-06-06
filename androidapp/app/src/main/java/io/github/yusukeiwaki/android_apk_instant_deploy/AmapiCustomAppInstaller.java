package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.net.Uri;

import com.google.android.managementapi.commands.LocalCommandClient;
import com.google.android.managementapi.commands.LocalCommandClient.InstallCustomAppCommandHelper;
import com.google.android.managementapi.commands.LocalCommandClientFactory;
import com.google.android.managementapi.commands.model.Command;
import com.google.android.managementapi.commands.model.IssueCommandRequest;
import com.google.android.managementapi.commands.model.IssueCommandRequest.InstallCustomApp;
import com.google.android.managementapi.commands.model.IssueCommandRequest.UninstallCustomApp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;

final class AmapiCustomAppInstaller {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String ANDROID_DEVICE_POLICY_PACKAGE = "com.google.android.apps.work.clouddpc";

    boolean isAvailable(Context context) {
        try {
            if (!isAndroidDevicePolicyDeviceOwner(context)) {
                return false;
            }
            File directory = customApksStorageDirectory(context);
            return directory != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    void install(Context context, String packageName, File apkFile) throws IOException, InterruptedException, ExecutionException {
        File directory = customApksStorageDirectory(context);
        if (directory == null) {
            throw new IOException("AMAPI custom APK storage directory is not available.");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create AMAPI custom APK storage directory.");
        }

        File commandApkFile = new File(directory, apkFileName(packageName, apkFile));
        copyFile(apkFile, commandApkFile);
        IssueCommandRequest request = IssueCommandRequest.builder()
                .setInstallCustomApp(InstallCustomApp.builder()
                        .setPackageName(packageName)
                        .setPackageUri(Uri.fromFile(commandApkFile).toString())
                        .build())
                .build();
        Command command = LocalCommandClientFactory.create(context)
                .issueCommand(request)
                .get();
        ensureCommandAccepted(command, true);
    }

    void uninstall(Context context, String packageName) throws IOException, InterruptedException, ExecutionException {
        IssueCommandRequest request = IssueCommandRequest.builder()
                .setUninstallCustomApp(UninstallCustomApp.builder()
                        .setPackageName(packageName)
                        .build())
                .build();
        Command command = LocalCommandClientFactory.create(context)
                .issueCommand(request)
                .get();
        ensureCommandAccepted(command, false);
    }

    private File customApksStorageDirectory(Context context) {
        LocalCommandClient client = LocalCommandClientFactory.create(context);
        InstallCustomAppCommandHelper helper = client.getInstallCustomAppCommandHelper();
        return helper.getCustomApksStorageDirectory();
    }

    private boolean isAndroidDevicePolicyDeviceOwner(Context context) {
        Context appContext = context.getApplicationContext();
        DevicePolicyManager devicePolicyManager =
                (DevicePolicyManager) appContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return devicePolicyManager != null &&
                devicePolicyManager.isDeviceOwnerApp(ANDROID_DEVICE_POLICY_PACKAGE);
    }

    private void ensureCommandAccepted(Command command, boolean install) throws IOException {
        if (command.getState() == Command.State.PENDING) {
            return;
        }
        Command.CustomAppOperationStatus status = install ?
                command.getStatus().installCustomAppStatus() :
                command.getStatus().uninstallCustomAppStatus();
        if (status.getOperationStatus() == Command.CustomAppOperationStatus.OperationStatus.STATUS_SUCCESS) {
            return;
        }
        String operation = install ? "InstallCustomApp" : "UninstallCustomApp";
        throw new IOException("AMAPI " + operation + " failed: " + status.getOperationStatus() + " " + status.getStatusMessage());
    }

    private String apkFileName(String packageName, File sourceFile) {
        String safePackageName = packageName == null || packageName.isEmpty() ? "app" : packageName.replaceAll("[^A-Za-z0-9._-]", "_");
        return safePackageName + "-" + sourceFile.length() + "-" + System.currentTimeMillis() + ".apk";
    }

    private void copyFile(File source, File destination) throws IOException {
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

}

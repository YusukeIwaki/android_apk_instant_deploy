package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.net.Uri;
import android.util.Log;

import com.google.android.managementapi.approles.AppRolesListener;
import com.google.android.managementapi.approles.model.AppRolesSetRequest;
import com.google.android.managementapi.approles.model.AppRolesSetResponse;
import com.google.android.managementapi.commands.CommandListener;
import com.google.android.managementapi.commands.model.Command;
import com.google.android.managementapi.notification.NotificationReceiverService;

import java.io.File;

public final class AmapiNotificationReceiverService extends NotificationReceiverService {
    private static final String TAG = "AmapiNotification";

    @Override
    protected AppRolesListener getAppRolesListener() {
        return new AppRolesListener() {
            @Override
            public AppRolesSetResponse onAppRolesSet(AppRolesSetRequest request) {
                Log.i(TAG, "AMAPI app roles set: " + request.getRoles());
                return AppRolesSetResponse.getDefaultInstance();
            }
        };
    }

    @Override
    protected CommandListener getCommandListener() {
        return new CommandListener() {
            @Override
            public void onCommandStatusChanged(Command command) {
                Command.StatusCase statusCase = command.getStatus();
                if (statusCase.getKind() == Command.StatusCase.Kind.INSTALL_CUSTOM_APP_STATUS) {
                    Command.CustomAppOperationStatus status = statusCase.installCustomAppStatus();
                    Log.i(TAG, "AMAPI InstallCustomApp status: " + status.getOperationStatus() + " " + status.getPackageName());
                    deleteStoragePath(status.getStoragePath());
                } else if (statusCase.getKind() == Command.StatusCase.Kind.UNINSTALL_CUSTOM_APP_STATUS) {
                    Command.CustomAppOperationStatus status = statusCase.uninstallCustomAppStatus();
                    Log.i(TAG, "AMAPI UninstallCustomApp status: " + status.getOperationStatus() + " " + status.getPackageName());
                }
            }
        };
    }

    private void deleteStoragePath(String storagePath) {
        if (storagePath == null || storagePath.isEmpty()) {
            return;
        }
        File file = storagePath.startsWith("file:") ? new File(Uri.parse(storagePath).getPath()) : new File(storagePath);
        if (!file.exists()) {
            return;
        }
        try {
            file.delete();
        } catch (RuntimeException ignored) {
        }
    }
}

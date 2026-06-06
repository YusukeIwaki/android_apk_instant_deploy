package io.github.yusukeiwaki.android_apk_instant_deploy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class AmapiCustomAppInstallerTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void isAvailableReturnsFalseWhenAndroidDevicePolicyIsNotDeviceOwner() {
        assertFalse(new AmapiCustomAppInstaller().isAvailable(context));
    }

    @Test
    public void isAvailableReturnsTrueWhenAndroidDevicePolicyIsDeviceOwner() {
        DevicePolicyManager devicePolicyManager =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        Shadows.shadowOf(devicePolicyManager).setDeviceOwner(new ComponentName(
                "com.google.android.apps.work.clouddpc",
                "com.google.android.apps.work.clouddpc.DeviceAdminReceiver"
        ));

        assertTrue(new AmapiCustomAppInstaller().isAvailable(context));
    }
}

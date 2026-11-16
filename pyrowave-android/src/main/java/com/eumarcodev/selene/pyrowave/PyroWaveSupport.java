package com.eumarcodev.selene.pyrowave;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Cheap, load-free check of whether this device can plausibly run PyroWave. */
public final class PyroWaveSupport {
    // VK_MAKE_API_VERSION(0, 1, 1, 0): the value Android reports for Vulkan 1.1.
    private static final int VULKAN_1_1 = 0x401000;

    private PyroWaveSupport() {}

    public static boolean isSupported(Context context) {
        return getUnsupportedReason(context) == null;
    }

    /** Returns null when supported, otherwise a short English reason (for logs). */
    public static String getUnsupportedReason(Context context) {
        if (Build.VERSION.SDK_INT < 24) {
            return "Android 7.0 or newer is required";
        }
        if (Build.SUPPORTED_64_BIT_ABIS.length == 0) {
            return "a 64-bit device is required";
        }
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 1)) {
            return "Vulkan hardware level 1 (compute) is required";
        }
        if (!pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1)) {
            return "Vulkan 1.1 or newer is required";
        }
        return null;
    }
}

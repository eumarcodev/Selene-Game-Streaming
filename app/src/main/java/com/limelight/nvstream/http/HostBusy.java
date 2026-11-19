package com.limelight.nvstream.http;

/**
 * Helios serves one device at a time: the device that opens the host owns it until it leaves the app list or
 * stops streaming. These rules read the host's answers about that ownership.
 */
public final class HostBusy {
    /** serverinfo tag (Helios extension): 1 when another device owns the host. */
    public static final String BUSY_TAG = "BusyWithOtherClient";

    /** serverinfo tag (Helios extension): answer to a "helioslock=1" claim, 1 when this device owns the host. */
    public static final String LOCK_TAG = "HeliosLock";

    /** How often the app list renews its claim; the host releases a claim not renewed for 6 s. */
    public static final long CLAIM_RENEW_MS = 2000;

    /** HTTP status Helios sends when it refuses a request because another device owns the host. */
    public static final int REFUSAL_STATUS = 503;

    /** Client-side error code passed to the stage failure dialog for this refusal (like -408 for timeouts). */
    public static final int HOST_IN_USE_ERROR = -503;

    private static final String REFUSAL_TEXT = "in use by another device";

    private HostBusy() {
    }

    /** Whether the serverinfo value of {@link #BUSY_TAG} says another device owns the host. */
    public static boolean busyWithOtherClient(String serverInfoValue) {
        return serverInfoValue != null && serverInfoValue.trim().equals("1");
    }

    /**
     * Whether a claim was granted. Hosts without the extension do not send {@link #LOCK_TAG}: they keep the old
     * behaviour and the claim counts as granted.
     */
    public static boolean claimGranted(String lockValue) {
        return lockValue == null || !lockValue.trim().equals("0");
    }

    /** Whether a request error is Helios refusing because another device owns the host. */
    public static boolean isOtherDeviceRefusal(int errorCode, String errorMessage) {
        return errorCode == REFUSAL_STATUS && errorMessage != null && errorMessage.contains(REFUSAL_TEXT);
    }
}

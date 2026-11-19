package com.limelight.preferences;

/**
 * Suggests an even frame rate when the panel does not switch to the stream rate.
 *
 * Some panels stay at their own refresh rate whatever the app asks (the Redmi Pad 2 stays at 90 Hz when 60 Hz is
 * requested). A 60 fps stream on 90 Hz then mixes frames shown for one and for two refreshes: only 48 to 49% of
 * the frames stay on screen exactly two refreshes. At half of the applied rate (45 fps) with frame pacing it is
 * 93% (eumarcodev/Helios-Server#113, measured with dumpsys SurfaceFlinger --latency).
 */
public final class EvenFrameRate {
    /** Never suggest less than this: lower rates feel worse than an uneven 60. */
    static final int MIN_SUGGESTED_FPS = 30;

    private EvenFrameRate() {
    }

    /**
     * @param streamFps the frame rate of the stream
     * @param appliedHz the refresh rate the panel actually applies, read after the display mode settled
     * @param lowestLatencyPacing true when frame pacing is "lowest latency" (frames are shown as soon as decoded,
     *                            so an even rate does not make them even)
     * @return the frame rate to suggest, or 0 for no suggestion
     */
    public static int suggest(int streamFps, float appliedHz, boolean lowestLatencyPacing) {
        if (streamFps <= 0 || appliedHz <= 0 || lowestLatencyPacing) {
            return 0;
        }
        int hz = Math.round(appliedHz);
        if (hz % streamFps == 0) {
            // Every frame already stays the same number of refreshes
            return 0;
        }
        int half = Math.round(appliedHz / 2);
        if (half < MIN_SUGGESTED_FPS || half >= streamFps) {
            return 0;
        }
        return half;
    }

    /**
     * The suggestion is shown at most once per host until the frame rate setting changes.
     *
     * @param fpsSettingWhenShown the frame rate setting stored when the suggestion was last shown for this host,
     *                            or null when it was never shown
     * @param currentFpsSetting the current frame rate setting
     */
    public static boolean shouldShow(String fpsSettingWhenShown, String currentFpsSetting) {
        return fpsSettingWhenShown == null || !fpsSettingWhenShown.equals(currentFpsSetting);
    }
}

package com.limelight.binding.video;

import java.util.Locale;

/**
 * Presentation regularity of the PyroWave player (eumarcodev/Helios-Server#129): the share of frames that stayed
 * on screen 1, 2, and 3 or more refresh periods, from the actual present times VK_GOOGLE_display_timing reports.
 * At a frame rate that divides the panel rate every frame should stay the same number of periods (for example
 * 2 at 45 fps on a 90 Hz panel); a mix means an uneven cadence.
 */
public final class PresentRegularity {
    private PresentRegularity() {
    }

    /**
     * @param counted what nativePresentStats returned: the number of frames, or -1 when the device cannot tell
     * @param counts  frames shown 1, 2, and 3 or more periods
     * @return the three shares in percent, or null when unknown (no display timing, or nothing presented)
     */
    public static float[] percentages(int counted, int[] counts) {
        if (counted <= 0 || counts == null || counts.length < 3) {
            return null;
        }
        int total = counts[0] + counts[1] + counts[2];
        if (total <= 0) {
            return null;
        }
        return new float[]{100f * counts[0] / total, 100f * counts[1] / total, 100f * counts[2] / total};
    }

    /** One line for the log; "unknown" when the shares are not known. */
    public static String describe(float[] percent) {
        if (percent == null) {
            return "shown unknown";
        }
        return String.format(Locale.US, "shown 1x %.1f%%, 2x %.1f%%, 3+x %.1f%%", percent[0], percent[1], percent[2]);
    }
}

package com.limelight.preferences;

/**
 * Chooses the automatic PyroWave bitrate.
 *
 * Every PyroWave frame is coded on its own, so it needs more bitrate than HEVC for the same picture: the automatic
 * value starts from {@link PreferenceConfiguration#PYROWAVE_AUTO_FACTOR} times the video bitrate, up to
 * {@link PreferenceConfiguration#PYROWAVE_AUTO_MAX_KBPS}.
 *
 * On Wi-Fi that can be more than the link drains in a frame period: the host sends a frame in a burst, the access
 * point queues it and the last packet reaches the client almost a whole frame later. The time a frame needs to cross
 * the link is bitrate / (fps x goodput), so keeping it under a share of the frame period means
 * bitrate &lt;= share x goodput, whatever the frame rate. Measured on a Redmi Pad 2 (Wi-Fi 5 GHz, link speed 390 to
 * 433 Mbit/s): the goodput was about 40% of the link speed, and a frame needed 15.4 ms at 150 Mbit/s against
 * 7.9 ms at 60 Mbit/s with a picture close to the 150 Mbit/s one.
 */
public final class PyroWaveBitrate {
    /** Share of the Wi-Fi link speed that carries the stream (measured, see the class comment). */
    static final float GOODPUT_PER_LINK = 0.4f;
    /** Share of the frame period a frame may spend crossing the link. */
    static final float TRANSIT_SHARE = 0.35f;
    /** The link limit never takes the automatic bitrate below this: lower looks visibly soft. */
    static final int MIN_AUTO_KBPS = 20000;

    private PyroWaveBitrate() {
    }

    /**
     * @param videoBitrateKbps the video bitrate the user chose
     * @param wifiLinkMbps the Wi-Fi link speed towards this device, or 0 or less when unknown or not on Wi-Fi
     * @return the automatic PyroWave bitrate in kbps
     */
    public static int autoKbps(int videoBitrateKbps, int wifiLinkMbps) {
        int byVideo = Math.max(videoBitrateKbps, Math.min(PreferenceConfiguration.PYROWAVE_AUTO_MAX_KBPS,
                Math.round(videoBitrateKbps * PreferenceConfiguration.PYROWAVE_AUTO_FACTOR)));
        if (wifiLinkMbps <= 0) {
            return byVideo;
        }
        int byLink = Math.max(MIN_AUTO_KBPS, Math.round(wifiLinkMbps * 1000f * GOODPUT_PER_LINK * TRANSIT_SHARE));
        return Math.min(byVideo, byLink);
    }
}

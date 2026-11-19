package com.limelight.preferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Decisions of the one-tap automatic setup (eumarcodev/Helios-Server#130). Deterministic, no random search:
 * <ol>
 * <li>Candidates: at most {@link #MAX_CANDIDATES} (size, fps) pairs where the fps divides a refresh rate the panel
 * offers and the size decodes in time at that fps (local benchmark), largest pixel rate first.</li>
 * <li>Each candidate is streamed from the host test pattern; its measurement passes with less than 1% incomplete
 * frames and at least 85% of the frames shown the expected number of refreshes.</li>
 * <li>The first candidate that passes wins; its PyroWave bitrate is 0.35 x the measured goodput, 20 to 150 Mbit/s.
 * When none passes the settings are kept.</li>
 * </ol>
 */
public final class AutoSetup {
    static final int MAX_CANDIDATES = 3;
    static final int MIN_FPS = 30;
    static final int MAX_FPS = 120;
    /** Share of the goodput the PyroWave bitrate may use (same as the link based automatic bitrate). */
    static final float TRANSIT_SHARE = PyroWaveBitrate.TRANSIT_SHARE;
    static final float GOODPUT_PER_LINK = PyroWaveBitrate.GOODPUT_PER_LINK;
    static final int MIN_KBPS = 20000;
    static final int MAX_KBPS = 150000;
    /** Bitrate of the test sessions when the Wi-Fi link speed is unknown (cable, or not reported). */
    static final int UNKNOWN_LINK_KBPS = 60000;
    static final float MIN_EXPECTED_SHARE = 85f;
    static final float MAX_INCOMPLETE_PERCENT = 1f;

    private AutoSetup() {
    }

    /** A size and frame rate to try. */
    public static final class Candidate {
        public final int width;
        public final int height;
        public final int fps;
        /** Median decode plus presentation time of this size from the local benchmark. */
        public final float decodeMs;

        public Candidate(int width, int height, int fps, float decodeMs) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.decodeMs = decodeMs;
        }

        long pixelRate() {
            return (long) width * height * fps;
        }

        @Override
        public String toString() {
            return width + "x" + height + "@" + fps;
        }
    }

    /** What one test session measured. */
    public static final class Measurement {
        /** Average size of a frame, bytes. */
        public double avgFrameBytes;
        /** Average time from the first to the last packet of a frame, ms. */
        public double avgTransitMs;
        /** Frames that missed packets or were dropped, percent. */
        public double incompletePercent;
        /** Frames shown 1, 2, and 3 or more refreshes, or null when the device cannot tell. */
        public int[] shownCounts;
        /** Refresh rate the panel applied during the session, Hz. */
        public float appliedHz;
        /** Frames measured. */
        public int frames;
    }

    /** The winner and its bitrate. */
    public static final class Decision {
        public final Candidate candidate;
        public final int pyroWaveKbps;

        Decision(Candidate candidate, int pyroWaveKbps) {
            this.candidate = candidate;
            this.pyroWaveKbps = pyroWaveKbps;
        }
    }

    /**
     * Frame rates worth trying: every refresh rate the panel offers and half of it, between 30 and 120.
     */
    public static List<Integer> frameRates(float[] refreshRates) {
        TreeSet<Integer> out = new TreeSet<>(Collections.reverseOrder());
        for (float hz : refreshRates) {
            int r = Math.round(hz);
            int half = Math.round(hz / 2);
            if (r >= MIN_FPS && r <= MAX_FPS) {
                out.add(r);
            }
            if (half >= MIN_FPS && half <= MAX_FPS) {
                out.add(half);
            }
        }
        return new ArrayList<>(out);
    }

    /** At most three sustainable (size, fps) pairs, the highest pixel rate first. */
    public static List<Candidate> candidates(List<DecodeBenchmark.Result> benchmark, float[] refreshRates) {
        List<Candidate> all = new ArrayList<>();
        for (DecodeBenchmark.Result r : benchmark) {
            for (int fps : frameRates(refreshRates)) {
                if (DecodeBenchmark.isSustainable(r.medianMs, fps)) {
                    all.add(new Candidate(r.width, r.height, fps, r.medianMs));
                }
            }
        }
        // Highest pixel rate first; on a tie the higher frame rate (smoother) first
        Collections.sort(all, (a, b) -> a.pixelRate() != b.pixelRate()
                ? Long.compare(b.pixelRate(), a.pixelRate()) : Integer.compare(b.fps, a.fps));
        return new ArrayList<>(all.subList(0, Math.min(MAX_CANDIDATES, all.size())));
    }

    /** Bitrate of the test sessions: 0.35 x 0.4 x the Wi-Fi link speed, 20 to 150 Mbit/s. */
    public static int testBitrateKbps(int wifiLinkMbps) {
        if (wifiLinkMbps <= 0) {
            return UNKNOWN_LINK_KBPS;
        }
        return clampKbps(Math.round(wifiLinkMbps * 1000f * GOODPUT_PER_LINK * TRANSIT_SHARE));
    }

    /** Goodput of a measurement in Mbit/s: average frame size over average transit. 0 when unknown. */
    public static double goodputMbps(Measurement m) {
        if (m == null || m.avgFrameBytes <= 0 || m.avgTransitMs <= 0) {
            return 0;
        }
        return m.avgFrameBytes * 8 / m.avgTransitMs / 1000.0;
    }

    /** The PyroWave bitrate for a measured goodput: 0.35 x goodput, 20 to 150 Mbit/s. */
    public static int bitrateKbps(double goodputMbps) {
        if (goodputMbps <= 0) {
            return MAX_KBPS; // frames crossed in under a millisecond: the link is not the limit
        }
        return clampKbps((int) Math.round(goodputMbps * 1000 * TRANSIT_SHARE));
    }

    static int clampKbps(int kbps) {
        return Math.max(MIN_KBPS, Math.min(MAX_KBPS, kbps));
    }

    /**
     * Share of the frames shown the expected number of refreshes (applied rate / fps), percent; -1 when the
     * device cannot tell or nothing was counted.
     */
    public static float expectedShare(Measurement m, int fps) {
        if (m.shownCounts == null || m.shownCounts.length < 3 || fps <= 0 || m.appliedHz <= 0) {
            return -1;
        }
        int total = m.shownCounts[0] + m.shownCounts[1] + m.shownCounts[2];
        if (total == 0) {
            return -1;
        }
        int expected = Math.round(m.appliedHz / fps);
        int index = expected <= 1 ? 0 : (expected == 2 ? 1 : 2);
        return 100f * m.shownCounts[index] / total;
    }

    /** True when the applied refresh rate is a whole multiple of the frame rate (within 1 Hz). */
    static boolean divides(float appliedHz, int fps) {
        if (appliedHz <= 0 || fps <= 0) {
            return false;
        }
        int multiple = Math.round(appliedHz / fps);
        return multiple >= 1 && Math.abs(appliedHz - multiple * fps) <= 1f;
    }

    /** Whether a candidate passes its measurement. */
    public static boolean passes(Candidate c, Measurement m) {
        if (m == null || m.frames <= 0 || !DecodeBenchmark.isSustainable(c.decodeMs, c.fps)) {
            return false;
        }
        if (m.incompletePercent >= MAX_INCOMPLETE_PERCENT) {
            return false;
        }
        float share = expectedShare(m, c.fps);
        if (share >= 0) {
            return share >= MIN_EXPECTED_SHARE;
        }
        // No display timing on this device: the cadence can only be even when the panel rate is a multiple
        return divides(m.appliedHz, c.fps);
    }

    /** The first candidate that passes, or null to keep the current settings. */
    public static Decision decide(List<Candidate> candidates, List<Measurement> measurements) {
        for (int i = 0; i < candidates.size() && i < measurements.size(); i++) {
            Candidate c = candidates.get(i);
            Measurement m = measurements.get(i);
            if (passes(c, m)) {
                return new Decision(c, bitrateKbps(goodputMbps(m)));
            }
        }
        return null;
    }
}

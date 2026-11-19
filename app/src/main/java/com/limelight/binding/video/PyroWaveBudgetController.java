package com.limelight.binding.video;

/**
 * Adjusts the PyroWave frame budget during a session when the Wi-Fi link changes (eumarcodev/Helios-Server#131).
 *
 * Fed once a second with the average assembly time (first to last packet of a frame). When frames take too long
 * to arrive the budget goes down, and it goes back up slowly once the link has room again:
 * <ul>
 *     <li>average assembly above 0.45 x frame period for 2 consecutive seconds: budget -20%;</li>
 *     <li>average assembly under 0.25 x frame period for 10 consecutive seconds and budget below the session
 *     start: budget +10%;</li>
 *     <li>never above the session start budget, never below the 20 Mbit/s equivalent.</li>
 * </ul>
 * Seconds with fewer frames than half the frame rate are a still screen and are ignored.
 * Pure logic, no Android dependency; the caller sends the new budget to the host.
 */
public class PyroWaveBudgetController {
    static final double HIGH_FRACTION = 0.45;
    static final double LOW_FRACTION = 0.25;
    static final int HIGH_SECONDS = 2;
    static final int LOW_SECONDS = 10;
    static final int MIN_BITRATE_KBPS = 20000;

    /** Returned by {@link #onSecond} when the budget does not change. */
    public static final long NO_CHANGE = -1;

    private final long startBudget;
    private final long minBudget;
    private final double framePeriodMs;
    // Seconds with fewer frames than this are a still screen and do not count (half the frame rate)
    private final int minActiveFrames;
    private long budget;
    private int highSeconds;
    private int lowSeconds;

    /**
     * @param startBudgetBytes frame budget of the session start in bytes
     * @param fps frame rate of the stream
     */
    public PyroWaveBudgetController(long startBudgetBytes, int fps) {
        int rate = Math.max(1, fps);
        this.startBudget = Math.max(1, startBudgetBytes);
        this.minBudget = Math.min(this.startBudget, (long) MIN_BITRATE_KBPS * 1000 / 8 / rate);
        this.framePeriodMs = 1000.0 / rate;
        this.minActiveFrames = Math.max(1, rate / 2);
        this.budget = this.startBudget;
    }

    /**
     * Frame budget in bytes for a bitrate and frame rate, capped by the client frame size cap.
     * @param maxFrameKb cap on one frame in KB, 0 or less for none
     */
    public static long budgetBytes(int bitrateKbps, int fps, int maxFrameKb) {
        long budget = (long) Math.max(1, bitrateKbps) * 1000 / 8 / Math.max(1, fps);
        if (maxFrameKb > 0) {
            budget = Math.min(budget, (long) maxFrameKb * 1024);
        }
        return budget;
    }

    /**
     * One second of statistics.
     * @param framesSeen frames received in that second; under half the frame rate (still screen) the second
     *                   does not count either way
     * @param avgAssemblyMs average assembly time of those frames in ms
     * @return the new budget in bytes, or {@link #NO_CHANGE}
     */
    public long onSecond(int framesSeen, double avgAssemblyMs) {
        // While the screen is still the host only repeats the last frame a few times a second, with a bigger
        // budget (still screen boost): those frames take long to arrive whatever the link, so such a second says
        // nothing about it. Measured 2026-11-19 on the tablet: a still desktop drove the budget to the floor.
        if (framesSeen < minActiveFrames) {
            highSeconds = 0;
            lowSeconds = 0;
            return NO_CHANGE;
        }
        if (avgAssemblyMs > HIGH_FRACTION * framePeriodMs) {
            lowSeconds = 0;
            if (++highSeconds < HIGH_SECONDS) {
                return NO_CHANGE;
            }
            highSeconds = 0;
            return change(Math.max(minBudget, budget * 4 / 5));
        }
        highSeconds = 0;
        if (avgAssemblyMs < LOW_FRACTION * framePeriodMs && budget < startBudget) {
            if (++lowSeconds < LOW_SECONDS) {
                return NO_CHANGE;
            }
            lowSeconds = 0;
            return change(Math.min(startBudget, budget + Math.max(1, budget / 10)));
        }
        lowSeconds = 0;
        return NO_CHANGE;
    }

    private long change(long next) {
        if (next == budget) {
            return NO_CHANGE;
        }
        budget = next;
        return next;
    }

    public long getBudget() {
        return budget;
    }

    public long getStartBudget() {
        return startBudget;
    }

    public long getMinBudget() {
        return minBudget;
    }
}

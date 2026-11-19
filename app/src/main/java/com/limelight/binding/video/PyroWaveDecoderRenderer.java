package com.limelight.binding.video;

import android.content.Context;
import android.os.SystemClock;
import android.view.Surface;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.eumarcodev.selene.pyrowave.PyroWaveNative;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;

/**
 * Decodes PyroWave frames on the GPU and presents them on a Surface through Vulkan.
 * Only complete frames are handled for now: one decode unit is one whole packetized frame.
 */
public class PyroWaveDecoderRenderer extends VideoDecoderRenderer {
    public static final int DR_OK = 0;
    public static final int DR_NEED_IDR = -1;
    // The "lowest latency" policy skips a frame older than this, whatever the decode time
    private static final long MIN_SKIP_AGE_MS = 30;
    public static final String POLICY_LOWEST = "lowest";
    public static final String POLICY_SMOOTH = "smooth";
    public static final String POLICY_CUSTOM = "custom";

    private Surface surface;
    private int width, height;
    private int redrawRate;
    private boolean framePacing;
    private long handle;
    private double averageDecodeMs = 10;

    // Statistics for the performance overlay, reported once a second by a timer so the overlay keeps
    // updating (and shows the loss) even when no frame arrives. Written by the decoder thread.
    private final PerfOverlayListener perfListener;
    private final StreamOverlayFormatter overlayFormatter;
    private final StreamStatsSampler statsSampler = new StreamStatsSampler();
    private final float targetMbps;
    private final String latencyPolicy;
    private final long customSkipAgeMs;
    private final AtomicInteger framesDecoded = new AtomicInteger();
    private final AtomicInteger framesSkipped = new AtomicInteger();
    private final AtomicInteger framesSkippedNewer = new AtomicInteger();
    private final AtomicLong queueWaitSumMs = new AtomicLong();
    private final AtomicLong queueWaitMaxMs = new AtomicLong();
    // First packet received to frame queued: how long the depacketizer held the frame
    private final AtomicLong assemblySumMs = new AtomicLong();
    private final AtomicLong assemblyMaxMs = new AtomicLong();
    private final AtomicInteger framesSeen = new AtomicInteger();
    private final AtomicLong frameBytes = new AtomicLong();
    private final AtomicInteger maxFrameBytes = new AtomicInteger();
    private final AtomicLong hostLatencyTenthsMs = new AtomicLong();
    private final AtomicInteger hostLatencyFrames = new AtomicInteger();
    private long statsStartMs;
    // Time between complete frames reaching the decoder, to tell network and host jitter from decode jitter
    private final Object arrivalLock = new Object();
    private long lastArrivalMs;
    private long arrivalCount, arrivalSumMs, arrivalSumSqMs, arrivalMaxMs;
    // Keeps the player alive while the stats timer reads it
    private final Object handleLock = new Object();
    private final float[] stageMs = new float[PyroWaveNative.STAGE_COUNT];
    private final int[] shownCounts = new int[3];
    private ScheduledExecutorService statsTimer;

    /**
     * @param context used to read the overlay strings and the Wi-Fi state
     * @param lite true for the one line overlay
     * @param bitrateKbps the bitrate requested from the host, shown against the measured speed
     * @param latencyPolicy POLICY_LOWEST, POLICY_SMOOTH or POLICY_CUSTOM: what to do with a late frame
     * @param customSkipAgeMs frame age in ms from which POLICY_CUSTOM skips a frame
     */
    public PyroWaveDecoderRenderer(PerfOverlayListener perfListener, Context context, boolean lite, int bitrateKbps,
                                   String latencyPolicy, int customSkipAgeMs) {
        this.latencyPolicy = latencyPolicy != null ? latencyPolicy : POLICY_LOWEST;
        this.customSkipAgeMs = customSkipAgeMs;
        this.perfListener = perfListener;
        this.overlayFormatter = context != null ? new StreamOverlayFormatter(context, lite) : null;
        this.targetMbps = bitrateKbps / 1000f;
    }

    /** Present frames one frame period apart (true) or as soon as they are decoded (false). Call before start(). */
    public void setFramePacing(boolean framePacing) {
        this.framePacing = framePacing;
    }

    public void setRenderTarget(Surface surface) {
        this.surface = surface;
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        this.width = width;
        this.height = height;
        this.redrawRate = redrawRate;
        return 0;
    }

    @Override
    public void start() {
        if (surface == null) {
            throw new IllegalStateException("PyroWave needs a render target Surface");
        }
        handle = PyroWaveNative.nativeCreate(surface, width, height, false);
        if (handle == 0) {
            throw new IllegalStateException("PyroWave is not supported on this device");
        }
        if (redrawRate > 0) {
            PyroWaveNative.nativeSetFrameRate(handle, redrawRate, framePacing);
        }
        startStatsTimer();
    }

    @Override
    public void stop() {
        stopStatsTimer();
        synchronized (handleLock) {
            long h = handle;
            handle = 0;
            if (h != 0) {
                PyroWaveNative.nativeDestroy(h);
            }
        }
    }

    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeMs, long enqueueTimeMs) {
        long h = handle;
        if (h == 0) {
            return DR_OK;
        }
        // A bad or incomplete frame is simply skipped: every PyroWave frame stands on its own,
        // so there is never a reason to ask the host for an IDR frame.
        //
        // The decoder works on the GPU and blocks for about a frame. When it is slower than the host
        // sends frames, the ones that already waited too long are skipped so the picture stays current
        // instead of falling further behind. This is safe because every frame is intra-only.
        long start = SystemClock.uptimeMillis();
        synchronized (arrivalLock) {
            if (lastArrivalMs != 0 && enqueueTimeMs >= lastArrivalMs) {
                long gap = enqueueTimeMs - lastArrivalMs;
                arrivalCount++;
                arrivalSumMs += gap;
                arrivalSumSqMs += gap * gap;
                arrivalMaxMs = Math.max(arrivalMaxMs, gap);
            }
            lastArrivalMs = enqueueTimeMs;
        }
        framesSeen.incrementAndGet();
        frameBytes.addAndGet(decodeUnitLength);
        maxFrameBytes.accumulateAndGet(decodeUnitLength, Math::max);
        if (frameHostProcessingLatency > 0) {
            hostLatencyTenthsMs.addAndGet(frameHostProcessingLatency);
            hostLatencyFrames.incrementAndGet();
        }
        long assemblyMs = Math.max(0, enqueueTimeMs - receiveTimeMs);
        assemblySumMs.addAndGet(assemblyMs);
        assemblyMaxMs.accumulateAndGet(assemblyMs, Math::max);
        long queueWaitMs = Math.max(0, start - enqueueTimeMs);
        queueWaitSumMs.addAndGet(queueWaitMs);
        queueWaitMaxMs.accumulateAndGet(queueWaitMs, Math::max);
        if (newerFramesWaiting()) {
            // A newer frame is already queued: showing this one would only add a frame of latency
            framesSkipped.incrementAndGet();
            framesSkippedNewer.incrementAndGet();
        } else if (queueWaitMs > skipAgeMs()) {
            framesSkipped.incrementAndGet();
        } else {
            PyroWaveNative.nativeSubmitFrame(h, decodeUnitData, decodeUnitLength);
            averageDecodeMs += (SystemClock.uptimeMillis() - start - averageDecodeMs) * 0.1;
            framesDecoded.incrementAndGet();
        }
        return DR_OK;
    }

    /**
     * Whether the frame about to be decoded should be dropped because newer ones are already waiting.
     *
     * Without pacing, "lowest latency" and "custom" always show the newest frame, so one newer frame is
     * enough. With pacing a newer frame waiting is the normal case (it is presented at the next slot), and
     * dropping the current one would show the previous picture for two slots, so only a second newer
     * frame means the queue is really falling behind. "Smooth" also allows one frame of backlog, which
     * bounds its latency instead of letting the queue grow when the device is slower than the host.
     */
    private boolean newerFramesWaiting() {
        int pending = MoonBridge.getPendingVideoFrames();
        int allowed = (framePacing || POLICY_SMOOTH.equals(latencyPolicy)) ? 1 : 0;
        return pending > allowed;
    }

    /** Frames that waited longer than this are skipped. Long.MAX_VALUE means never. */
    private long skipAgeMs() {
        switch (latencyPolicy) {
            case POLICY_SMOOTH:
                return Long.MAX_VALUE;
            case POLICY_CUSTOM:
                return customSkipAgeMs;
            default:
                return Math.max(MIN_SKIP_AGE_MS, 2 * (long) averageDecodeMs);
        }
    }

    private void startStatsTimer() {
        if (perfListener == null || overlayFormatter == null) {
            return;
        }
        statsStartMs = SystemClock.uptimeMillis();
        statsSampler.sample(statsStartMs); // baseline for the first window
        statsTimer = Executors.newSingleThreadScheduledExecutor();
        statsTimer.scheduleWithFixedDelay(this::reportStats, 1, 1, TimeUnit.SECONDS);
    }

    private void stopStatsTimer() {
        ScheduledExecutorService timer = statsTimer;
        statsTimer = null;
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    private void reportStats() {
        try {
            long now = SystemClock.uptimeMillis();
            long elapsed = Math.max(1, now - statsStartMs);
            statsStartMs = now;

            int seen = framesSeen.getAndSet(0);
            int skippedNewer = framesSkippedNewer.getAndSet(0);
            long queueWaitSum = queueWaitSumMs.getAndSet(0);
            long queueWaitMax = queueWaitMaxMs.getAndSet(0);
            long assemblySum = assemblySumMs.getAndSet(0);
            long assemblyMax = assemblyMaxMs.getAndSet(0);
            StreamOverlayFormatter.VideoInfo info = new StreamOverlayFormatter.VideoInfo();
            info.width = width;
            info.height = height;
            info.fps = framesDecoded.getAndSet(0) * 1000f / elapsed;
            info.decodeMs = (float) averageDecodeMs;
            info.skipped = framesSkipped.getAndSet(0);
            info.avgFrameKb = seen == 0 ? 0 : (int) (frameBytes.getAndSet(0) / seen / 1024);
            info.maxFrameKb = maxFrameBytes.getAndSet(0) / 1024;
            int hostFrames = hostLatencyFrames.getAndSet(0);
            info.hostLatencyMs = hostFrames == 0 ? 0 : hostLatencyTenthsMs.getAndSet(0) / 10f / hostFrames;
            info.targetMbps = targetMbps;
            synchronized (arrivalLock) {
                if (arrivalCount > 1) {
                    double mean = (double) arrivalSumMs / arrivalCount;
                    double variance = (double) arrivalSumSqMs / arrivalCount - mean * mean;
                    info.arrivalJitterMs = (float) Math.sqrt(Math.max(0, variance));
                    info.arrivalMaxGapMs = (int) arrivalMaxMs;
                }
                arrivalCount = arrivalSumMs = arrivalSumSqMs = arrivalMaxMs = 0;
            }
            if (info.arrivalJitterMs >= 0) {
                // Next to the stage times the native player logs once a second
                android.util.Log.i("SelenePyroWave", String.format(java.util.Locale.US,
                        "arrival ms: jitter %.1f, longest gap %d, skipped %d (newer waiting %d), host %.1f, queue wait avg %.1f max %d, assembly avg %.1f max %d",
                        info.arrivalJitterMs, info.arrivalMaxGapMs, info.skipped, skippedNewer, info.hostLatencyMs,
                        seen == 0 ? 0.0 : (double) queueWaitSum / seen, queueWaitMax,
                        seen == 0 ? 0.0 : (double) assemblySum / seen, assemblyMax));
            }
            synchronized (handleLock) {
                if (handle != 0 && PyroWaveNative.nativeStageStats(handle, stageMs) > 0) {
                    info.stageMs = stageMs.clone();
                }
                if (handle != 0) {
                    int counted = PyroWaveNative.nativePresentStats(handle, shownCounts);
                    info.shownPercent = PresentRegularity.percentages(counted, shownCounts);
                }
            }
            // Next to the stage times the native player logs once a second
            android.util.Log.i("SelenePyroWave", "presentation: " + PresentRegularity.describe(info.shownPercent));
            if (seen == 0) {
                frameBytes.set(0);
            }

            perfListener.onPerfUpdate(overlayFormatter.format(info, statsSampler.sample(now)));
        } catch (RuntimeException e) {
            // The overlay must never take the stream down
        }
    }

    @Override
    public void cleanup() {
        stop();
    }

    @Override
    public int getCapabilities() {
        return 0;
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
    }
}

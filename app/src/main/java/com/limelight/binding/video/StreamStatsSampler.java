package com.limelight.binding.video;

import com.limelight.nvstream.jni.MoonBridge;

/**
 * Turns the running counters of moonlight-common-c (MoonBridge.getStreamStats) into per-window rates
 * for the performance overlay. Call sample() once per window from a single thread.
 */
public class StreamStatsSampler {
    public static class Sample {
        public float videoMbps;
        public float windowSeconds;
        public int framesDelivered, framesIncomplete, framesLost;
        public int videoPackets, videoPacketsLost;
        public int audioPackets, audioLost, audioRecovered, audioUnrecoverable;
        public int audioUnderruns;

        // Share of frames that arrived incomplete or not at all
        public float frameLossPercent() {
            int total = framesDelivered + framesLost;
            return total == 0 ? 0 : 100f * (framesIncomplete + framesLost) / total;
        }

        public float packetLossPercent() {
            int total = videoPackets + videoPacketsLost;
            return total == 0 ? 0 : 100f * videoPacketsLost / total;
        }
    }

    private long[] previous;
    private long previousMs;
    private int previousUnderruns;

    /**
     * @return the change since the previous call, or null on the first call or when the native counters are
     * not available (no connection yet).
     */
    public Sample sample(long nowMs) {
        long[] current = MoonBridge.getStreamStats();
        if (current == null || current.length < MoonBridge.STREAM_STATS_COUNT) {
            return null;
        }

        long[] last = previous;
        long lastMs = previousMs;
        previous = current;
        previousMs = nowMs;
        if (last == null || nowMs <= lastMs) {
            return null;
        }

        Sample s = new Sample();
        s.windowSeconds = (nowMs - lastMs) / 1000f;
        s.videoMbps = delta(current, last, MoonBridge.STREAM_STATS_VIDEO_BYTES) * 8f / 1_000_000f / s.windowSeconds;
        s.framesDelivered = delta(current, last, MoonBridge.STREAM_STATS_VIDEO_FRAMES);
        s.framesIncomplete = delta(current, last, MoonBridge.STREAM_STATS_VIDEO_FRAMES_INCOMPLETE);
        s.framesLost = delta(current, last, MoonBridge.STREAM_STATS_VIDEO_FRAMES_LOST);
        s.videoPackets = delta(current, last, MoonBridge.STREAM_STATS_VIDEO_PACKETS);
        s.videoPacketsLost = delta(current, last, MoonBridge.STREAM_STATS_VIDEO_PACKETS_LOST);
        s.audioPackets = delta(current, last, MoonBridge.STREAM_STATS_AUDIO_PACKETS);
        s.audioLost = delta(current, last, MoonBridge.STREAM_STATS_AUDIO_PACKETS_LOST);
        s.audioRecovered = delta(current, last, MoonBridge.STREAM_STATS_AUDIO_PACKETS_RECOVERED);
        s.audioUnrecoverable = delta(current, last, MoonBridge.STREAM_STATS_AUDIO_BLOCKS_UNRECOVERABLE);
        int underruns = MoonBridge.getAudioUnderruns();
        s.audioUnderruns = Math.max(0, underruns - previousUnderruns);
        previousUnderruns = underruns;
        return s;
    }

    // The native counters are unsigned 32 bit values that wrap around
    private static int delta(long[] current, long[] last, int index) {
        return (int) ((current[index] - last[index]) & 0xFFFFFFFFL);
    }
}

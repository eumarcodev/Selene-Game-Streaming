package com.limelight.binding.video;

import android.content.Context;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import com.limelight.BuildConfig;
import com.limelight.R;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.List;

/**
 * Builds the text of the performance overlay for every codec (PyroWave, H.264, HEVC, AV1). Numbers are
 * coloured green, yellow or red so a bad link is visible at a glance. Call it off the decode thread: it
 * makes JNI and Wi-Fi service calls.
 */
public class StreamOverlayFormatter {
    public static final int GOOD = Color.rgb(0x66, 0xE0, 0x6B);
    public static final int WARN = Color.rgb(0xFF, 0xD5, 0x4A);
    public static final int BAD = Color.rgb(0xFF, 0x5C, 0x5C);

    /** What the decoder measured during the last window. */
    public static class VideoInfo {
        public String codec = "PyroWave"; // shown before the resolution
        public String decoderName;        // MediaCodec decoder, null for PyroWave
        public int width, height;
        public float fps;
        public float receivedFps = -1;    // frames that came from the network, -1 when not tracked
        // MediaCodec decodes several frames in flight, so its decode time can exceed the frame time
        // without slowing the stream down; only a blocking decoder (PyroWave) is coloured by it
        public boolean decodeBlocks = true;
        public float decodeMs;
        public int skipped;
        public int avgFrameKb, maxFrameKb;
        public float hostLatencyMs; // 0 when the host did not report it
        public float targetMbps;    // 0 when unknown
        public float arrivalJitterMs = -1; // standard deviation of the time between frames, -1 when unknown
        public int arrivalMaxGapMs;        // longest time between two frames in the window
        // Average time per stage in ms, see PyroWaveNative.nativeStageStats. Null when nothing was decoded
        public float[] stageMs;
        /** Share of frames shown 1, 2, and 3 or more refresh periods (VK_GOOGLE_display_timing); null when unknown. */
        public float[] shownPercent;
    }

    private final Context context;
    private final boolean lite;

    public StreamOverlayFormatter(Context context, boolean lite) {
        this.context = context;
        this.lite = lite;
    }

    public CharSequence format(VideoInfo v, StreamStatsSampler.Sample net) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        long rttInfo = MoonBridge.getEstimatedRttInfo();
        boolean haveRtt = rttInfo >= 0;
        int rtt = (int) (rttInfo >> 32);
        int rttVariance = (int) rttInfo;

        float frameLoss = net != null ? net.frameLossPercent() : 0;
        float packetLoss = net != null ? net.packetLossPercent() : 0;
        float mbps = net != null ? net.videoMbps : 0;

        int bandwidthColor = GOOD;
        if (v.targetMbps > 0 && net != null) {
            // Lots of intra frames are small when the screen is still, so only a big gap is a problem
            bandwidthColor = mbps < v.targetMbps * 0.25f ? WARN : GOOD;
        }

        String hostVersion = MoonBridge.hostVersion;
        String versions = context.getString(R.string.pw_overlay_versions, BuildConfig.VERSION_NAME,
                hostVersion != null ? hostVersion : context.getString(R.string.pw_overlay_unknown_host));

        if (lite) {
            sb.append(versions).append("  ");
            append(sb, context.getString(R.string.pw_overlay_lite_speed, mbps), bandwidthColor);
            sb.append("  ");
            if (haveRtt) {
                append(sb, context.getString(R.string.pw_overlay_lite_rtt, rtt), colorRtt(rtt));
                sb.append("  ");
            }
            append(sb, context.getString(R.string.pw_overlay_lite_decode, v.decodeMs), colorDecode(v));
            sb.append("  ");
            append(sb, context.getString(R.string.pw_overlay_lite_loss, Math.max(frameLoss, packetLoss)),
                    colorLoss(Math.max(frameLoss, packetLoss)));
            sb.append("  ");
            append(sb, context.getString(R.string.pw_overlay_lite_fps, v.fps), GOOD);
            if (net != null && (net.audioLost > 0 || net.audioRecovered > 0 || net.audioUnrecoverable > 0 || net.audioUnderruns > 0)) {
                sb.append("  ");
                append(sb, context.getString(R.string.pw_overlay_lite_audio, net.audioLost + net.audioUnderruns),
                        net.audioLost > 0 || net.audioUnrecoverable > 0 || net.audioUnderruns > 0 ? BAD : WARN);
            }
            if (net != null && net.audioStalls > 0) {
                sb.append("  ");
                append(sb, context.getString(R.string.pw_overlay_lite_audio_stall, net.audioStallMaxMs), BAD);
            }
            return sb;
        }

        sb.append(versions).append('\n');
        if (v.receivedFps >= 0) {
            sb.append(context.getString(R.string.overlay_video_received, v.codec, v.width, v.height, v.fps, v.receivedFps)).append('\n');
        } else {
            sb.append(context.getString(R.string.pw_overlay_video, v.codec, v.width, v.height, v.fps, v.skipped)).append('\n');
        }
        if (v.decoderName != null) {
            sb.append(context.getString(R.string.perf_overlay_decoder, v.decoderName)).append('\n');
        }
        append(sb, context.getString(R.string.pw_overlay_decode, v.decodeMs), colorDecode(v));
        if (v.hostLatencyMs > 0) {
            sb.append("   ");
            sb.append(context.getString(R.string.pw_overlay_host, v.hostLatencyMs));
        }
        sb.append('\n');
        if (v.stageMs != null && v.stageMs.length >= 8) {
            float[] s = v.stageMs;
            if (s[6] >= 0) {
                sb.append(context.getString(R.string.pw_overlay_gpu, s[6], s[7])).append('\n');
            }
            sb.append(context.getString(R.string.pw_overlay_stages, s[0], s[1], s[2], s[3], s[4], s[5])).append('\n');
            if (s.length >= 9 && s[8] > 0.05f) {
                sb.append(context.getString(R.string.pw_overlay_pace, s[8])).append('\n');
            }
        }
        if (v.arrivalJitterMs >= 0) {
            sb.append(context.getString(R.string.pw_overlay_arrival, v.arrivalJitterMs, v.arrivalMaxGapMs)).append('\n');
        }
        if (v.stageMs != null) {
            if (v.shownPercent != null) {
                sb.append(context.getString(R.string.pw_overlay_shown, v.shownPercent[0], v.shownPercent[1], v.shownPercent[2])).append('\n');
            } else {
                sb.append(context.getString(R.string.pw_overlay_shown_unknown)).append('\n');
            }
        }
        append(sb, context.getString(R.string.pw_overlay_speed, mbps), bandwidthColor);
        sb.append("   ").append(context.getString(R.string.pw_overlay_frame_size, v.avgFrameKb, v.maxFrameKb)).append('\n');
        if (net != null) {
            append(sb, context.getString(R.string.pw_overlay_loss, frameLoss, packetLoss),
                    colorLoss(Math.max(frameLoss, packetLoss)));
            sb.append('\n');
        }
        if (haveRtt) {
            append(sb, context.getString(R.string.pw_overlay_rtt, rtt, rttVariance), colorRtt(rtt));
            sb.append('\n');
        }
        if (net != null) {
            int audioColor = net.audioLost > 0 || net.audioUnrecoverable > 0 || net.audioUnderruns > 0 ? BAD : (net.audioRecovered > 0 ? WARN : GOOD);
            append(sb, context.getString(R.string.pw_overlay_audio, net.audioLost, net.audioRecovered, net.audioUnrecoverable, net.audioUnderruns), audioColor);
            sb.append('\n');
            // Late audio: stalls in arrival (Wi-Fi queues) and what the client dropped to catch up. Not network loss.
            int lateColor = net.audioStalls > 0 || net.audioDiscarded > 0 ? BAD : GOOD;
            append(sb, context.getString(R.string.pw_overlay_audio_late, net.audioStalls, net.audioStallMaxMs, net.audioDiscarded), lateColor);
            sb.append('\n');
        }
        if (v.stageMs != null && MoonBridge.pyroWaveBitratePref > 0) {
            sb.append(context.getString(R.string.pw_overlay_bitrate, MoonBridge.pyroWaveBitratePref / 1000)).append('\n');
        }
        if (MoonBridge.pyroWavePacketSizePref > 0 || MoonBridge.pyroWaveMaxFrameKbPref > 0) {
            String packet = MoonBridge.pyroWavePacketSizePref > 0 ? MoonBridge.pyroWavePacketSizePref + " B"
                    : context.getString(R.string.pw_overlay_pref_auto);
            String frame = MoonBridge.pyroWaveMaxFrameKbPref > 0 ? MoonBridge.pyroWaveMaxFrameKbPref + " KB"
                    : context.getString(R.string.pw_overlay_pref_nolimit);
            sb.append(context.getString(R.string.pw_overlay_requested, packet, frame)).append('\n');
        }
        String wifi = wifiText();
        if (wifi != null) {
            sb.append(wifi).append('\n');
        }
        List<String> events = OverlayEvents.recent(3);
        for (String event : events) {
            append(sb, event, WARN);
            sb.append('\n');
        }
        // Drop the trailing new line
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.delete(sb.length() - 1, sb.length());
        }
        return sb;
    }

    private String wifiText() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return null;
            }
            @SuppressWarnings("deprecation")
            WifiInfo info = wm.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) {
                return null;
            }
            return context.getString(R.string.pw_overlay_wifi, info.getLinkSpeed(), info.getRssi());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int colorRtt(int ms) {
        return ms < 15 ? GOOD : ms < 40 ? WARN : BAD;
    }

    private static int colorLoss(float percent) {
        return percent < 0.5f ? GOOD : percent < 3f ? WARN : BAD;
    }

    private static int colorDecode(VideoInfo v) {
        if (v.fps <= 0 || !v.decodeBlocks) {
            return GOOD;
        }
        // The decoder is in trouble when one frame takes more than the time between frames
        float frameMs = 1000f / v.fps;
        return v.decodeMs < frameMs * 0.8f ? GOOD : v.decodeMs < frameMs * 1.2f ? WARN : BAD;
    }

    private static void append(SpannableStringBuilder sb, String text, int color) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}

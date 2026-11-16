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
 * Builds the text of the performance overlay while a PyroWave stream is running. Numbers are coloured
 * green, yellow or red so a bad link is visible at a glance.
 */
public class PyroWaveOverlayFormatter {
    public static final int GOOD = Color.rgb(0x66, 0xE0, 0x6B);
    public static final int WARN = Color.rgb(0xFF, 0xD5, 0x4A);
    public static final int BAD = Color.rgb(0xFF, 0x5C, 0x5C);

    /** What the decoder measured during the last window. */
    public static class VideoInfo {
        public int width, height;
        public float fps;
        public float decodeMs;
        public int skipped;
        public int avgFrameKb, maxFrameKb;
        public float hostLatencyMs; // 0 when the host did not report it
        public float targetMbps;    // 0 when unknown
    }

    private final Context context;
    private final boolean lite;

    public PyroWaveOverlayFormatter(Context context, boolean lite) {
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
            return sb;
        }

        sb.append(versions).append('\n');
        sb.append(context.getString(R.string.pw_overlay_video, v.width, v.height, v.fps, v.skipped)).append('\n');
        append(sb, context.getString(R.string.pw_overlay_decode, v.decodeMs), colorDecode(v));
        if (v.hostLatencyMs > 0) {
            sb.append("   ");
            sb.append(context.getString(R.string.pw_overlay_host, v.hostLatencyMs));
        }
        sb.append('\n');
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
        if (v.fps <= 0) {
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

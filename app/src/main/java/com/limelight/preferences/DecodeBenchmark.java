package com.limelight.preferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Decisions on top of the local PyroWave decode benchmark (eumarcodev/Helios-Server#127).
 *
 * PyroWave decode time grows with the resolution: on the Redmi Pad 2 about 13 to 15 ms at 1920x1080 but about
 * 29 ms at 2560x1600, so the native resolution there gives 34 fps. The benchmark measures, without a stream, the
 * median time one frame of each size takes to decode and present when the GPU is kept busy; a size is sustainable
 * at F fps when that median leaves 30% of the frame period free. The result only informs the user, it never
 * changes a setting.
 */
public final class DecodeBenchmark {
    /** Share of the frame period the decode plus presentation may use. */
    static final float BUDGET_SHARE = 0.7f;
    /** Frame rate whose per-frame budget the benchmark encodes at (smaller frames at higher rates decode faster). */
    public static final float BENCHMARK_FPS = 60f;

    private DecodeBenchmark() {
    }

    /** One measured size. */
    public static final class Result {
        public final int width;
        public final int height;
        public final float medianMs;

        public Result(int width, int height, float medianMs) {
            this.width = width;
            this.height = height;
            this.medianMs = medianMs;
        }

        long pixels() {
            return (long) width * height;
        }
    }

    public static boolean isSustainable(float medianMs, float fps) {
        return medianMs > 0 && fps > 0 && medianMs <= BUDGET_SHARE * 1000f / fps;
    }

    /** The sustainable size with the most pixels, or null when none is. */
    public static Result largestSustainable(List<Result> results, float fps) {
        Result best = null;
        for (Result r : results) {
            if (isSustainable(r.medianMs, fps) && (best == null || r.pixels() > best.pixels())) {
                best = r;
            }
        }
        return best;
    }

    /**
     * Whether the chosen size is more than this device decodes in time. A measured size uses its own result;
     * any other size is compared by pixels with the largest sustainable one. Without results nothing is known,
     * so nothing exceeds.
     */
    public static boolean exceeds(List<Result> results, float fps, int width, int height) {
        if (results.isEmpty() || width <= 0 || height <= 0) {
            return false;
        }
        for (Result r : results) {
            if (r.width == width && r.height == height) {
                return !isSustainable(r.medianMs, fps);
            }
        }
        Result best = largestSustainable(results, fps);
        return best == null || (long) width * height > best.pixels();
    }

    /** Sizes to measure: 1280x720, 1920x1080, 1920x1200 and the native size (landscape, even), smallest first. */
    public static List<int[]> sizes(int displayWidth, int displayHeight) {
        int w = Math.max(displayWidth, displayHeight) & ~1;
        int h = Math.min(displayWidth, displayHeight) & ~1;
        List<int[]> out = new ArrayList<>();
        int[][] fixed = {{1280, 720}, {1920, 1080}, {1920, 1200}};
        for (int[] s : fixed) {
            if ((long) s[0] * s[1] < (long) w * h) {
                out.add(s);
            }
        }
        if (w > 0 && h > 0) {
            out.add(new int[]{w, h});
        }
        return out;
    }

    /** Bytes per frame at this bitrate and frame rate. */
    public static int frameBytes(int bitrateKbps, float fps) {
        if (bitrateKbps <= 0 || fps <= 0) {
            return 0;
        }
        return Math.round(bitrateKbps * 1000f / 8f / fps);
    }

    /** Results are stored per device model and app version: a new version may decode faster or slower. */
    public static String storageKey(String model, int versionCode) {
        return "pyrowave_decode_benchmark_" + model + "_" + versionCode;
    }

    public static String format(List<Result> results) {
        StringBuilder sb = new StringBuilder();
        for (Result r : results) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(r.width).append('x').append(r.height).append('=')
                    .append(String.format(Locale.US, "%.2f", r.medianMs));
        }
        return sb.toString();
    }

    /** Parses what {@link #format} wrote. Anything malformed gives an empty list (the device is measured again). */
    public static List<Result> parse(String stored) {
        if (stored == null || stored.isEmpty()) {
            return Collections.emptyList();
        }
        List<Result> out = new ArrayList<>();
        try {
            for (String item : stored.split(";")) {
                String[] kv = item.split("=");
                String[] wh = kv[0].split("x");
                out.add(new Result(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]), Float.parseFloat(kv[1])));
            }
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        return out;
    }
}

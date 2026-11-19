package com.limelight.binding.video;

import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.eumarcodev.selene.pyrowave.PyroWaveNative;
import com.limelight.LimeLog;
import com.limelight.preferences.DecodeBenchmark;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the local PyroWave decode benchmark (eumarcodev/Helios-Server#127) without a stream: the real player
 * decodes test pictures and presents them on a full-screen surface sized like the stream, the same path a stream
 * takes.
 *
 * An offscreen ImageReader target was tried first and does not work: its consumer paces the swapchain, so the time
 * per frame came out in steps of about 18 ms (18, 36, 54 ms) whatever the resolution.
 */
public final class DecodeBenchmarkRunner {
    /** Frames decoded before measuring (at least; the warm-up also lasts one second), so clocks settle. */
    static final int WARMUP_FRAMES = 30;
    /** Frames measured per size. */
    static final int MEASURED_FRAMES = 120;

    public interface Listener {
        /** About to measure size index (0 based) of total. Called on the main thread. */
        void onProgress(int index, int total, int width, int height);

        /**
         * Called on the main thread when the benchmark ends.
         *
         * @param results the measured sizes, or null when cancelled or failed
         * @param error   a short reason when it failed, null otherwise
         */
        void onFinished(List<DecodeBenchmark.Result> results, String error);
    }

    /** Gives the surface the size of the stream being measured, as a stream does (the display scales it). */
    public interface SurfaceSizer {
        /** Blocks until the surface has this buffer size; false when it did not happen in time. */
        boolean resize(int width, int height);
    }

    private final Thread thread;
    private volatile boolean cancelled;

    private DecodeBenchmarkRunner(Surface surface, SurfaceSizer sizer, int screenWidth, int screenHeight, int bitrateKbps, Listener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        thread = new Thread(() -> run(surface, sizer, screenWidth, screenHeight, bitrateKbps, listener, main), "PyroWaveBenchmark");
    }

    /**
     * Starts measuring on a background thread, presenting on surface and encoding at the per-frame budget of
     * bitrateKbps at 60 fps. screenWidth x screenHeight is the real display size: the native size measured.
     */
    public static DecodeBenchmarkRunner start(Surface surface, SurfaceSizer sizer, int screenWidth, int screenHeight,
                                              int bitrateKbps, Listener listener) {
        DecodeBenchmarkRunner runner = new DecodeBenchmarkRunner(surface, sizer, screenWidth, screenHeight, bitrateKbps, listener);
        PyroWaveNative.nativeBenchmarkCancel(false);
        runner.thread.start();
        return runner;
    }

    /** Stops after the current frame; onFinished then reports null results. */
    public void cancel() {
        cancelled = true;
        PyroWaveNative.nativeBenchmarkCancel(true);
    }

    /** Cancels and waits until the surface is no longer used (call before the surface goes away). */
    public void cancelAndWait() {
        cancel();
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run(Surface surface, SurfaceSizer sizer, int screenWidth, int screenHeight, int bitrateKbps, Listener listener, Handler main) {
        // Same priority as the video threads of a stream
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        List<int[]> sizes = DecodeBenchmark.sizes(screenWidth, screenHeight);
        int frameBytes = DecodeBenchmark.frameBytes(bitrateKbps, DecodeBenchmark.BENCHMARK_FPS);

        List<DecodeBenchmark.Result> results = new ArrayList<>();
        String error = null;
        long start = System.nanoTime();
        try {
            for (int i = 0; i < sizes.size() && !cancelled; i++) {
                final int index = i;
                final int[] size = sizes.get(i);
                main.post(() -> listener.onProgress(index, sizes.size(), size[0], size[1]));
                // A stream's swapchain has the stream size and the display scales it: presenting at the screen size
                // instead made 1080p about 2.5 ms slower per frame on the Redmi Pad 2
                if (!sizer.resize(size[0], size[1])) {
                    error = "surface";
                    break;
                }
                long handle = PyroWaveNative.nativeCreate(surface, size[0], size[1], false);
                if (handle == 0) {
                    error = "player";
                    break;
                }
                float[] out = new float[3];
                int r;
                try {
                    r = PyroWaveNative.nativeBenchmark(handle, frameBytes, WARMUP_FRAMES, MEASURED_FRAMES, out);
                } finally {
                    PyroWaveNative.nativeDestroy(handle);
                }
                if (r == -20) {
                    break;
                }
                if (r != 0) {
                    error = "error " + r;
                    break;
                }
                LimeLog.info("PyroWave decode benchmark " + size[0] + "x" + size[1] + ": " + out[0]
                        + " ms per frame (median), " + Math.round(out[1]) + " bytes per frame (budget " + frameBytes + ")");
                results.add(new DecodeBenchmark.Result(size[0], size[1], out[0]));
            }
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        LimeLog.info("PyroWave decode benchmark took " + (System.nanoTime() - start) / 1000000 + " ms");

        final List<DecodeBenchmark.Result> done = (cancelled || error != null) ? null : results;
        final String reason = cancelled ? null : error;
        main.post(() -> listener.onFinished(done, reason));
    }
}

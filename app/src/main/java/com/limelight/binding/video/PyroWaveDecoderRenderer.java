package com.limelight.binding.video;

import android.os.SystemClock;
import android.view.Surface;

import com.eumarcodev.selene.pyrowave.PyroWaveNative;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;

/**
 * Decodes PyroWave frames on the GPU and presents them on a Surface through Vulkan.
 * Only complete frames are handled for now: one decode unit is one whole packetized frame.
 */
public class PyroWaveDecoderRenderer extends VideoDecoderRenderer {
    public static final int DR_OK = 0;
    public static final int DR_NEED_IDR = -1;
    // A frame older than this is skipped, whatever the decode time
    private static final long MIN_SKIP_AGE_MS = 30;

    private Surface surface;
    private int width, height;
    private long handle;
    private double averageDecodeMs = 10;

    public void setRenderTarget(Surface surface) {
        this.surface = surface;
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        this.width = width;
        this.height = height;
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
    }

    @Override
    public void stop() {
        long h = handle;
        handle = 0;
        if (h != 0) {
            PyroWaveNative.nativeDestroy(h);
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
        if (start - enqueueTimeMs > Math.max(MIN_SKIP_AGE_MS, 2 * (long) averageDecodeMs)) {
            return DR_OK;
        }
        PyroWaveNative.nativeSubmitFrame(h, decodeUnitData, decodeUnitLength);
        averageDecodeMs += (SystemClock.uptimeMillis() - start - averageDecodeMs) * 0.1;
        return DR_OK;
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

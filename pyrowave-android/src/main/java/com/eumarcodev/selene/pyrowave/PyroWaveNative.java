package com.eumarcodev.selene.pyrowave;

import android.view.Surface;

/** JNI bridge to the PyroWave decoder and its Vulkan presentation. */
public final class PyroWaveNative {
    private PyroWaveNative() {}

    static {
        System.loadLibrary("pyrowave-shared");
    }

    /** Returns a handle, or 0 when the device cannot run PyroWave. */
    public static native long nativeCreate(Surface surface, int width, int height, boolean chroma444);

    /** Decodes one complete packetized frame and presents it. 0 = presented, 1 = dropped, negative = error. */
    public static native int nativeSubmitFrame(long handle, byte[] data, int length);

    /** Encodes and shows a generated test pattern. Used to verify the pipeline without a server. */
    public static native int nativeSelfTestFrame(long handle, int frame, int lossPercent);

    public static native void nativeDestroy(long handle);

    /**
     * Frame rate of the stream. The display is asked to use a refresh rate that fits it. With paced on, frames
     * are presented one frame period apart instead of as soon as they are decoded.
     */
    public static native void nativeSetFrameRate(long handle, float fps, boolean paced);

    public static native long nativeFramesPresented(long handle);

    public static native long nativeFramesDropped(long handle);

    /** Number of values {@link #nativeStageStats} writes. */
    public static final int STAGE_COUNT = 9;

    /**
     * Average time of each stage since the last call, in milliseconds: parse, fence wait, acquire, record,
     * submit, present, GPU decode, GPU present, pacing wait. The two GPU values are -1 when the device cannot
     * measure them.
     *
     * @return the number of frames averaged, 0 when there is nothing new
     */
    public static native int nativeStageStats(long handle, float[] out);
}

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
    public static native int nativeSelfTestFrame(long handle, int frame);

    public static native void nativeDestroy(long handle);

    public static native long nativeFramesPresented(long handle);

    public static native long nativeFramesDropped(long handle);
}

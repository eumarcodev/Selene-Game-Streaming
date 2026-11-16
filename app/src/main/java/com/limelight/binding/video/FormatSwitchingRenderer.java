package com.limelight.binding.video;

import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * The host decides the video format during the RTSP handshake, after the renderer has been
 * handed to the connection. This renderer forwards to the PyroWave decoder when PyroWave was
 * negotiated and to the MediaCodec decoder for every other format.
 */
public class FormatSwitchingRenderer extends VideoDecoderRenderer {
    private final VideoDecoderRenderer mediaCodec;
    private final VideoDecoderRenderer pyroWave;
    private VideoDecoderRenderer active;
    private volatile int activeFormat;

    public FormatSwitchingRenderer(VideoDecoderRenderer mediaCodec, VideoDecoderRenderer pyroWave) {
        this.mediaCodec = mediaCodec;
        this.pyroWave = pyroWave;
        this.active = mediaCodec;
    }

    /** Format negotiated with the host, or 0 before setup. */
    public int getActiveVideoFormat() {
        return activeFormat;
    }

    public boolean isPyroWaveActive() {
        return (activeFormat & MoonBridge.VIDEO_FORMAT_MASK_PYROWAVE) != 0;
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        activeFormat = format;
        active = (format & MoonBridge.VIDEO_FORMAT_MASK_PYROWAVE) != 0 ? pyroWave : mediaCodec;
        return active.setup(format, width, height, redrawRate);
    }

    @Override
    public void start() {
        active.start();
    }

    @Override
    public void stop() {
        active.stop();
    }

    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeMs, long enqueueTimeMs) {
        return active.submitDecodeUnit(decodeUnitData, decodeUnitLength, decodeUnitType,
                frameNumber, frameType, frameHostProcessingLatency, receiveTimeMs, enqueueTimeMs);
    }

    @Override
    public void cleanup() {
        active.cleanup();
    }

    @Override
    public int getCapabilities() {
        // Queried before the format is negotiated, so it describes the MediaCodec path. Direct submit is
        // removed: it runs the decoder on the network receive thread, which is fine for MediaCodec (it
        // only queues the buffer) but PyroWave decodes on the GPU and blocks for a whole frame, so
        // packets would pile up in the socket buffer and add hundreds of milliseconds of delay.
        return mediaCodec.getCapabilities() & ~MoonBridge.CAPABILITY_DIRECT_SUBMIT;
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        active.setHdrMode(enabled, hdrMetadata);
    }
}

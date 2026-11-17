package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

public class AndroidAudioRenderer implements AudioRenderer {

    private final Context context;
    private final boolean enableAudioFx;

    private AudioTrack track;

    // Number of buffer underruns of the current track (API 24+), the audible clicks and gaps
    public int getUnderrunCount() {
        AudioTrack t = track;
        if (t == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return 0;
        }
        try {
            return t.getUnderrunCount();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Use FLAG_LOW_LATENCY on L through N
            if (lowLatency) {
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributesBuilder.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);

            // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }

            return trackBuilder.build();
        }
        else {
            return new AudioTrack(attributesBuilder.build(),
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channelConfig;
        int bytesPerFrame;

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                // in 5.0, so just hardcode the constant so we can work on Lollipop.
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        this.sampleRate = sampleRate;
        this.channelCount = audioConfiguration.channelCount;
        this.framesWritten = 0;
        this.catchingUp = false;

        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    bufferSize = bytesPerFrame * 2;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 2);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            // Skip low latency options when using audio effects, since low latency mode
            // precludes the use of the audio effect pipeline (as of Android 13).
            if (enableAudioFx && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                track.play();

                // Successfully created working AudioTrack. We're done here.
                LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
                break;
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (track == null) {
            // Couldn't create any audio track for playback
            return -2;
        }

        return 0;
    }

    // Catch-up after a late burst (Selene #53). The backlog is what waits in the native queue plus what the
    // AudioTrack still has to play. Above CATCH_UP_START_MS the track plays a little faster (pitch kept)
    // until the backlog is back under CATCH_UP_STOP_MS, instead of dropping a block of sound at once.
    // Frames are only skipped when the native queue alone holds more than SKIP_QUEUE_MS.
    private static final int CATCH_UP_START_MS = 70;
    private static final int CATCH_UP_STOP_MS = 35;
    private static final float CATCH_UP_SPEED = 1.06f;
    private static final int SKIP_QUEUE_MS = 100;

    private int sampleRate;
    private int channelCount;
    private long framesWritten;
    private boolean catchingUp;
    private boolean stretchSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    private volatile int skippedFrames;

    // Frames skipped to catch up since the stream started
    public int getSkippedFrames() {
        return skippedFrames;
    }

    // Milliseconds of audio written to the track and not played yet
    private int trackBacklogMs() {
        long played = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        long buffered = framesWritten - played;
        if (buffered < 0) {
            buffered = 0;
        }
        return (int) (buffered * 1000 / sampleRate);
    }

    private void setCatchUp(boolean on) {
        if (on == catchingUp) {
            return;
        }
        catchingUp = on;
        if (!stretchSupported) {
            return;
        }
        try {
            track.setPlaybackParams(track.getPlaybackParams().setSpeed(on ? CATCH_UP_SPEED : 1.0f).setPitch(1.0f));
        } catch (RuntimeException e) {
            // Time stretching not available for this track: fall back to skipping frames
            LimeLog.warning("Audio catch-up speed not supported: " + e);
            stretchSupported = false;
        }
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        int queuedMs = MoonBridge.getPendingAudioDuration();
        if (queuedMs > SKIP_QUEUE_MS || (!stretchSupported && queuedMs >= 40)) {
            // Far behind (or no time stretching): skip this frame
            skippedFrames++;
            return;
        }

        int backlogMs = queuedMs + trackBacklogMs();
        if (backlogMs > CATCH_UP_START_MS) {
            setCatchUp(true);
        }
        else if (backlogMs < CATCH_UP_STOP_MS) {
            setCatchUp(false);
        }

        // This blocks while the track is full, which keeps the native queue as the jitter buffer
        int written = track.write(audioData, 0, audioData.length);
        if (written > 0) {
            framesWritten += written / channelCount;
        }
    }

    @Override
    public void start() {
        if (enableAudioFx) {
            // Open an audio effect control session to allow equalizers to apply audio effects
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }
    }

    @Override
    public void stop() {
        if (enableAudioFx) {
            // Close our audio effect control session when we're stopping
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        // Immediately drop all pending data
        track.pause();
        track.flush();

        track.release();
    }
}

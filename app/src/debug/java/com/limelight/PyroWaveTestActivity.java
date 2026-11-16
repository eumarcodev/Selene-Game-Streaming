package com.limelight;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import com.eumarcodev.selene.pyrowave.PyroWaveNative;

/** Debug only: shows a PyroWave encoded test pattern, to check decoding and presentation on a device. */
public class PyroWaveTestActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "PyroWaveTest";
    private volatile boolean running;
    private Thread thread;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        setContentView(view);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        final int w = getIntent().getIntExtra("w", 1920);
        final int h = getIntent().getIntExtra("h", 1080);
        final int loss = getIntent().getIntExtra("loss", 0);
        final int frames = getIntent().getIntExtra("frames", 600);
        running = true;
        thread = new Thread(() -> {
            long handle = PyroWaveNative.nativeCreate(holder.getSurface(), w, h, false);
            Log.i(TAG, "create " + w + "x" + h + " -> " + handle);
            if (handle == 0) {
                return;
            }
            long start = System.nanoTime();
            int i = 0;
            while (running && i < frames) {
                int r = PyroWaveNative.nativeSelfTestFrame(handle, i++, loss);
                if (r != 0) {
                    Log.e(TAG, "frame " + i + " result " + r);
                    if (r < 0) break;
                }
            }
            double secs = (System.nanoTime() - start) / 1e9;
            Log.i(TAG, "done " + PyroWaveNative.nativeFramesPresented(handle) + " presented, "
                    + PyroWaveNative.nativeFramesDropped(handle) + " dropped, " + (i / secs) + " fps (encode + decode + present)");
            PyroWaveNative.nativeDestroy(handle);
        }, "pyrowave-test");
        thread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        try {
            if (thread != null) thread.join();
        } catch (InterruptedException ignored) {
        }
    }
}

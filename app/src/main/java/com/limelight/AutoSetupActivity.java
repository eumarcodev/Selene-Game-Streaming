package com.limelight;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.limelight.binding.video.DecodeBenchmarkRunner;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.AutoSetup;
import com.limelight.preferences.DecodeBenchmark;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.ServerHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-tap automatic setup (eumarcodev/Helios-Server#130), a state machine:
 * <ol>
 * <li>Local: the decode benchmark of this device (stored, or measured now, about 15 s) and the refresh rates the
 * panel offers.</li>
 * <li>Candidates: at most three (size, fps) pairs ({@link AutoSetup#candidates}).</li>
 * <li>Host: a 15 s PyroWave test session per candidate with the host test pattern, until one passes.</li>
 * <li>Result: one line with Apply and Cancel. Apply saves resolution, frame rate, pacing, PyroWave and its
 * bitrate into the settings; nothing is stored on the host.</li>
 * </ol>
 * Cancel works at every step and every test session is quit on the host.
 */
public class AutoSetupActivity extends Activity {
    public static final String EXTRA_PC_UUID = "UUID";

    static final int MEASURE_SECONDS = 15;
    static final int TEST_PATTERN_APP_ID = AutoSetup.TEST_PATTERN_APP_ID;
    /** Time for the host to close a test session before the next one starts. */
    private static final long BETWEEN_SESSIONS_MS = 2000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private ComputerDetails computer;
    private boolean cancelled;
    private long startMs;

    private SurfaceView surfaceView;
    private TextView status;
    private DecodeBenchmarkRunner benchmark;

    private List<AutoSetup.Candidate> candidates = new ArrayList<>();
    private final List<AutoSetup.Measurement> measurements = new ArrayList<>();
    private int testKbps;
    // Test session in progress: its token, and whether its screen has covered this one yet
    private int waitingToken;
    private int sessionIndex;
    private boolean awayForSession;
    private static int nextToken = 1;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder = (ComputerManagerService.ComputerManagerBinder) binder;
            new Thread(() -> {
                localBinder.waitForReady();
                managerBinder = localBinder;
                String uuid = getIntent().getStringExtra(EXTRA_PC_UUID);
                if (uuid != null) {
                    ComputerDetails details = localBinder.getComputer(uuid);
                    main.post(() -> onComputer(details));
                } else {
                    findPairedComputer(localBinder);
                }
            }).start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            managerBinder = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startMs = SystemClock.uptimeMillis();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setBackgroundColor(0xAA000000);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        status.setPadding(pad, pad, pad, pad);
        status.setText(R.string.auto_setup_starting);
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
        Button cancel = new Button(this);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(v -> cancel());
        root.addView(cancel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));
        setContentView(root);
        hideSystemBars();

        bindService(new Intent(this, ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** Without a host given (settings entry): the paired host that answers, or a choice when there are several. */
    private void findPairedComputer(ComputerManagerService.ComputerManagerBinder binder) {
        final List<ComputerDetails> found = new ArrayList<>();
        binder.startPolling(details -> {
            synchronized (found) {
                if (details.state == ComputerDetails.State.ONLINE && details.pairState == PairingManager.PairState.PAIRED) {
                    for (ComputerDetails d : found) {
                        if (d.uuid != null && d.uuid.equals(details.uuid)) {
                            return;
                        }
                    }
                    found.add(details);
                }
            }
        });
        main.postDelayed(() -> {
            binder.stopPolling();
            List<ComputerDetails> online;
            synchronized (found) {
                online = new ArrayList<>(found);
            }
            if (cancelled) {
                return;
            }
            if (online.isEmpty()) {
                fail(getString(R.string.auto_setup_no_host));
            } else if (online.size() == 1) {
                onComputer(online.get(0));
            } else {
                String[] names = new String[online.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = online.get(i).name;
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.auto_setup_choose_host)
                        .setItems(names, (d, which) -> onComputer(online.get(which)))
                        .setOnCancelListener(d -> cancel())
                        .show();
            }
        }, 4000);
    }

    private void onComputer(ComputerDetails details) {
        if (cancelled) {
            return;
        }
        if (details == null || details.activeAddress == null || details.pairState != PairingManager.PairState.PAIRED) {
            fail(getString(R.string.auto_setup_no_host));
            return;
        }
        if (AutoSetup.hostBusy(details.runningGameId)) {
            // A test session would end the session running on the host
            LimeLog.info("Automatic setup: host " + details.name + " is streaming app " + details.runningGameId);
            fail(getString(R.string.auto_setup_host_busy));
            return;
        }
        computer = details;
        LimeLog.info("Automatic setup for " + computer.name);
        List<DecodeBenchmark.Result> results = loadBenchmark();
        if (results.isEmpty()) {
            runBenchmark();
        } else {
            onBenchmark(results);
        }
    }

    private List<DecodeBenchmark.Result> loadBenchmark() {
        return DecodeBenchmark.parse(PreferenceManager.getDefaultSharedPreferences(this)
                .getString(DecodeBenchmark.storageKey(Build.MODEL, BuildConfig.VERSION_CODE), null));
    }

    // Local phase: the same full-screen benchmark as "Measure this device"
    private void runBenchmark() {
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(this);
        final int kbps = config.pyroWaveBitrate > 0 ? config.pyroWaveBitrate
                : com.limelight.preferences.PyroWaveBitrate.autoKbps(config.bitrate, 0);
        final int[] wanted = new int[2];
        final AtomicReference<CountDownLatch> sized = new AtomicReference<>();
        final DecodeBenchmarkRunner.SurfaceSizer sizer = (width, height) -> {
            CountDownLatch latch = new CountDownLatch(1);
            synchronized (wanted) {
                wanted[0] = width;
                wanted[1] = height;
            }
            sized.set(latch);
            main.post(() -> {
                Rect frame = surfaceView.getHolder().getSurfaceFrame();
                if (frame.width() == width && frame.height() == height) {
                    latch.countDown();
                } else {
                    surfaceView.getHolder().setFixedSize(width, height);
                }
            });
            try {
                return latch.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
        final DecodeBenchmarkRunner.Listener listener = new DecodeBenchmarkRunner.Listener() {
            @Override
            public void onProgress(int index, int total, int width, int height) {
                status.setText(getString(R.string.auto_setup_step_device, width, height, index + 1, total));
            }

            @Override
            public void onFinished(List<DecodeBenchmark.Result> results, String error) {
                benchmark = null;
                if (cancelled) {
                    return;
                }
                if (results == null) {
                    fail(getString(R.string.pyrowave_measure_failed, error != null ? error : "?"));
                    return;
                }
                PreferenceManager.getDefaultSharedPreferences(AutoSetupActivity.this).edit()
                        .putString(DecodeBenchmark.storageKey(Build.MODEL, BuildConfig.VERSION_CODE), DecodeBenchmark.format(results))
                        .apply();
                onBenchmark(results);
            }
        };
        SurfaceHolder holder = surfaceView.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder h) {
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder h, int format, int width, int height) {
                CountDownLatch latch = sized.get();
                synchronized (wanted) {
                    if (latch != null && width == wanted[0] && height == wanted[1]) {
                        latch.countDown();
                    }
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder h) {
                if (benchmark != null) {
                    benchmark.cancelAndWait();
                }
            }
        });
        Runnable start = () -> {
            DisplayMetrics real = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(real);
            benchmark = DecodeBenchmarkRunner.start(holder.getSurface(), sizer, real.widthPixels, real.heightPixels, kbps, listener);
        };
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            start.run();
        } else {
            holder.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder h) {
                    if (benchmark == null && !cancelled) {
                        start.run();
                    }
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder h, int format, int width, int height) {
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder h) {
                }
            });
        }
    }

    private float[] refreshRates() {
        Display display = getWindowManager().getDefaultDisplay();
        TreeSet<Float> rates = new TreeSet<>();
        rates.add(display.getRefreshRate());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode current = display.getMode();
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth() == current.getPhysicalWidth() && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                    rates.add(mode.getRefreshRate());
                }
            }
        }
        float[] out = new float[rates.size()];
        int i = 0;
        for (Float r : rates) {
            out[i++] = r;
        }
        return out;
    }

    private void onBenchmark(List<DecodeBenchmark.Result> results) {
        if (cancelled) {
            return;
        }
        float[] rates = refreshRates();
        candidates = AutoSetup.candidates(results, rates);
        testKbps = AutoSetup.testBitrateKbps(wifiLinkMbps());
        LimeLog.info("Automatic setup: candidates " + candidates + ", test bitrate " + testKbps + " kbps");
        if (candidates.isEmpty()) {
            showResult(null);
            return;
        }
        measurements.clear();
        startSession(0);
    }

    // Same reading as the stream uses for its automatic bitrate
    private int wifiLinkMbps() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.Network network = cm != null ? cm.getActiveNetwork() : null;
                android.net.NetworkCapabilities caps = network != null ? cm.getNetworkCapabilities(network) : null;
                if (caps == null || !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                    return 0;
                }
            }
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
            if (info == null) {
                return 0;
            }
            int rx = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? info.getRxLinkSpeedMbps() : -1;
            return Math.max(0, rx > 0 ? rx : info.getLinkSpeed());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // Host phase: one test session per candidate, until one passes
    private void startSession(int index) {
        if (cancelled) {
            return;
        }
        AutoSetup.Candidate c = candidates.get(index);
        status.setText(getString(R.string.auto_setup_step_host, c.width, c.height, c.fps, index + 1, candidates.size()));
        // Helios starts no app in a test session; it reports a placebo app with this id (proc::test_pattern_app_id),
        // so a second session launched while the first one is still closing is accepted too
        NvApp app = new NvApp(getString(R.string.auto_setup_title), null, TEST_PATTERN_APP_ID, false);
        Intent intent = ServerHelper.createStartIntent(this, app, computer, managerBinder, false);
        intent.putExtra(Game.EXTRA_TEST_PATTERN, true);
        intent.putExtra(Game.EXTRA_SETUP_SECONDS, MEASURE_SECONDS);
        intent.putExtra(Game.EXTRA_SETUP_WIDTH, c.width);
        intent.putExtra(Game.EXTRA_SETUP_HEIGHT, c.height);
        intent.putExtra(Game.EXTRA_SETUP_FPS, c.fps);
        intent.putExtra(Game.EXTRA_SETUP_PYROWAVE_KBPS, testKbps);
        waitingToken = nextToken++;
        sessionIndex = index;
        awayForSession = false;
        intent.putExtra(Game.EXTRA_SETUP_TOKEN, waitingToken);
        startActivity(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (waitingToken != 0) {
            awayForSession = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (waitingToken == 0 || !awayForSession || cancelled) {
            return;
        }
        // Back from a test session
        Game.SetupOutcome outcome = Game.lastSetupOutcome;
        int token = waitingToken;
        waitingToken = 0;
        if (outcome == null || outcome.token != token) {
            // The session ended without a measurement (connection failed or the stream was closed)
            fail(getString(R.string.auto_setup_session_failed));
            return;
        }
        if (outcome.measurement == null) {
            cancel();
            return;
        }
        onMeasurement(sessionIndex, outcome.measurement);
    }

    private void onMeasurement(int requestCode, AutoSetup.Measurement m) {
        measurements.add(m);
        AutoSetup.Candidate c = candidates.get(requestCode);
        LimeLog.info(String.format(Locale.US, "Automatic setup %s: %d frames, goodput %.0f Mbit/s, incomplete %.2f%%, on time %.1f%%, panel %.1f Hz -> %s",
                c, m.frames, AutoSetup.goodputMbps(m), m.incompletePercent, AutoSetup.expectedShare(m, c.fps), m.appliedHz,
                AutoSetup.passes(c, m) ? "pass" : "fail"));
        AutoSetup.Decision decision = AutoSetup.decide(candidates, measurements);
        if (decision != null || requestCode + 1 >= candidates.size()) {
            showResult(decision);
        } else {
            main.postDelayed(() -> startSession(requestCode + 1), BETWEEN_SESSIONS_MS);
        }
    }

    private void showResult(AutoSetup.Decision decision) {
        if (cancelled || isFinishing()) {
            return;
        }
        long seconds = (SystemClock.uptimeMillis() - startMs) / 1000;
        LimeLog.info("Automatic setup finished in " + seconds + " s: " + (decision == null ? "no candidate passed"
                : decision.candidate + " " + decision.pyroWaveKbps + " kbps"));
        status.setText("");
        if (decision == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.auto_setup_title)
                    .setMessage(R.string.auto_setup_none)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
            return;
        }
        AutoSetup.Candidate c = decision.candidate;
        String line = getString(R.string.auto_setup_result, c.width, c.height, c.fps, Math.round(decision.pyroWaveKbps / 1000f));
        new AlertDialog.Builder(this)
                .setTitle(R.string.auto_setup_title)
                .setMessage(line)
                .setPositiveButton(R.string.auto_setup_apply, (d, w) -> {
                    apply(decision);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void apply(AutoSetup.Decision decision) {
        AutoSetup.Candidate c = decision.candidate;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit()
                .putString("list_resolution", c.width + "x" + c.height)
                .putString("list_fps", Integer.toString(c.fps))
                .putBoolean("checkbox_enable_pyrowave", true)
                .putString("list_pyrowave_bitrate", Integer.toString(decision.pyroWaveKbps));
        // The cadence was measured with frame pacing on; keep a pacing mode the user already chose
        if ("latency".equals(prefs.getString("frame_pacing", "latency"))) {
            editor.putString("frame_pacing", "balanced");
        }
        editor.apply();
        android.widget.Toast.makeText(this, R.string.auto_setup_applied, android.widget.Toast.LENGTH_LONG).show();
    }

    private void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        LimeLog.info("Automatic setup cancelled");
        if (benchmark != null) {
            benchmark.cancel();
        }
        // A test session only runs while its own screen is in front, where Back ends it on the host
        finish();
    }

    private void fail(String message) {
        if (isFinishing()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.auto_setup_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    @Override
    public void onBackPressed() {
        cancel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (benchmark != null) {
            benchmark.cancel();
        }
        if (managerBinder != null) {
            managerBinder.stopPolling();
        }
        try {
            unbindService(serviceConnection);
        } catch (IllegalArgumentException ignored) {
        }
    }
}

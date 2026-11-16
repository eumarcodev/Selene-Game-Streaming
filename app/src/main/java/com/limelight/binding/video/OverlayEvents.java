package com.limelight.binding.video;

import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Short history of connection events (poor link, termination, restarts) shown by the performance overlay.
 * Thread safe: events come from native callback threads and the UI thread.
 */
public final class OverlayEvents {
    private static final int MAX_EVENTS = 20;

    private static final ArrayDeque<String> events = new ArrayDeque<>();
    private static long streamStartMs = SystemClock.elapsedRealtime();

    private OverlayEvents() {
    }

    /** Forget the old events and restart the clock used for the time stamps. */
    public static synchronized void reset() {
        events.clear();
        streamStartMs = SystemClock.elapsedRealtime();
    }

    public static synchronized void add(String text) {
        long seconds = (SystemClock.elapsedRealtime() - streamStartMs) / 1000;
        if (events.size() >= MAX_EVENTS) {
            events.removeFirst();
        }
        events.addLast(String.format(Locale.US, "%d:%02d %s", seconds / 60, seconds % 60, text));
    }

    /** The newest events, newest last. */
    public static synchronized List<String> recent(int count) {
        List<String> all = new ArrayList<>(events);
        return all.subList(Math.max(0, all.size() - count), all.size());
    }
}

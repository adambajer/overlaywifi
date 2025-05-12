package com.example.overlaywifi;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps a 10-second RSSI log for the last 8 hours (2 880 samples) and exposes:
 *   • {@link #start()} / {@link #stop()}  – begin / halt background sampling
 *   • {@link #captureNow()}               – force one immediate sample
 *   • {@link #snapshot()}                 – thread-safe copy of the buffer
 *   • {@link #currentSsid()}              – best-effort SSID for UI titles
  * Singleton – obtain via {@code RssiRecorder.getInstance(context)}.
 * No runtime permissions requested here; caller handles ACCESS_FINE_LOCATION.
 */
public class RssiRecorder {

    /* ---------- singleton boilerplate ---------- */

    private static volatile RssiRecorder sInstance;

    public static RssiRecorder getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (RssiRecorder.class) {
                if (sInstance == null) sInstance = new RssiRecorder(ctx);
            }
        }
        return sInstance;
    }

    private RssiRecorder(Context ctx) {
        wifi = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
    }

    /* ---------- public API ---------- */

    /** Start 10-second sampler (idempotent). */
    public void start() {
        if (!running) {
            running = true;
            handler.post(tick);        // first run in 10 s
        }
    }

    /** Stop sampler (idempotent). */
    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    /** Force one sample immediately (used by stats dialog). */
    public void captureNow() { sample(); }

    /** Copy of the ring buffer; safe for any thread. */
    public Deque<Entry> snapshot() {
        synchronized (ring) { return new ArrayDeque<>(ring); }
    }

    /** Best-effort SSID (quotes stripped); returns "Unknown" if unavailable. */
    public String currentSsid() {
        WifiInfo i = wifi.getConnectionInfo();
        if (i != null && i.getNetworkId() != -1) {
            String s = i.getSSID();
            if (s != null && !s.equals("<unknown ssid>")) {
                return s.replace("\"", "");
            }
        }
        return "Unknown";
    }

    /* ---------- implementation details ---------- */

    private static final int PERIOD_MS   = 10_000;          // 10 s
    private static final int MAX_SAMPLES = 8 * 60 * 60 / 10;    // 2 880 (8 h)

    public static class Entry {
        public final long  t;     // wall-clock millis
        public final int   rssi;  // dBm (0 when disconnected)
        Entry(long t, int r) { this.t = t; this.rssi = r; }
    }

    private final WifiManager wifi;
    private final Handler     handler = new Handler(Looper.getMainLooper());
    private final Deque<Entry> ring   = new ArrayDeque<>(MAX_SAMPLES + 10);

    private boolean running = false;

    /** Runnable scheduled every 10 s. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            sample();
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    /** Obtain RSSI (or 0) and push into ring buffer. */
    private void sample() {
        WifiInfo info = wifi.getConnectionInfo();
        int rssi = (info != null && info.getNetworkId() != -1) ? info.getRssi() : 0;

        synchronized (ring) {
            ring.addLast(new Entry(System.currentTimeMillis(), rssi));
            while (ring.size() > MAX_SAMPLES) ring.removeFirst();
        }
    }
}

package com.example.overlaywifi;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.*;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.view.*;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import eightbitlab.com.blurview.BlurView;

/**
 * Foreground service that shows a draggable Wi‑Fi badge.
 * Long‑press opens a graph; every connection change is stored in a CSV file.
 */
public class OverlayService extends Service {
    /** internal‑storage CSV — timestamp(ms), 0|1, ssid */
    public static final String EVENT_FILE = "wifi_events.csv";

    private static final String CHANNEL_ID = "overlay_wifi";
    private static final int    NOTIF_ID   = 1;

    private WindowManager wm;
    private View          badge;
    private TextView      tvName, tvSince;
    private ImageView     iv;

    private WifiManager         wifi;
    private ConnectivityManager cm;
    private final Handler       ui = new Handler(Looper.getMainLooper());

    private boolean connected  = false;
    private long    stateStart = System.currentTimeMillis();
    private String  ssid       = "–";

    /*──────────────────────── lifecycle ───────────────────────*/

    @Override public void onCreate() {
        super.onCreate();
        startForeground(NOTIF_ID, buildNotification());

        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        cm   = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        wm   = (WindowManager) getSystemService(WINDOW_SERVICE);

        cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), netCb);

        inflateBadge();
        onConnChange(isWifiValidated());
        ui.post(tick);
    }

    @Override public void onDestroy() {
        cm.unregisterNetworkCallback(netCb);
        ui.removeCallbacks(tick);
        ui.removeCallbacks(ssidRetry);
        if (badge != null) wm.removeView(badge);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent i) { return null; }

    /*──────────────────────── network callback ─────────────────*/

    private final ConnectivityManager.NetworkCallback netCb = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n)          { onConnChange(true); }
        @Override public void onLost(Network n)               { onConnChange(false);}
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
            boolean ok = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            onConnChange(ok);
        }
    };

    private boolean isWifiValidated() {
        Network n = cm.getActiveNetwork(); if (n == null) return false;
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void onConnChange(boolean now) {
        if (now == connected) return;
        connected  = now;
        stateStart = System.currentTimeMillis();
        ui.removeCallbacks(ssidRetry);
        if (connected) { ssid = "–"; if (!tryCacheSsid()) ui.postDelayed(ssidRetry, 500); } else ssid = "–";
        logEvent();
        updateUi();
    }

    /*──────────────────────── CSV logger ──────────────────────*/
    private void logEvent() {
        String line = System.currentTimeMillis()+","+(connected?1:0)+","+(connected?ssid:"-")+"\n";
        try (FileOutputStream fos = openFileOutput(EVENT_FILE, MODE_APPEND)) { fos.write(line.getBytes()); } catch (IOException ignored) {}
    }

    /*──────────────────────── badge + blur ───────────────────*/

    private void inflateBadge() {
        badge   = View.inflate(this, R.layout.overlay_badge, null);
        tvName  = badge.findViewById(R.id.text);
        tvSince = badge.findViewById(R.id.since);
        iv      = badge.findViewById(R.id.icon);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        // Optionally, add vertical offset in pixels:
        // lp.y = 50;  // move down 50px from top

        wm.addView(badge, lp);
        badge.post(this::initBlur);
        makeDraggableAndLongPress(badge);
    }

    private void initBlur() {
        BlurView blur = badge.findViewById(R.id.blur_view);
        blur.setupWith((ViewGroup) badge.getRootView()).setBlurRadius(16f).setOverlayColor(0x26FFFFFF);
    }

    /*──────────────────────── drag & long‑press ─────────────────*/

    private void makeDraggableAndLongPress(View v) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public void onLongPress(MotionEvent e) {
                Intent g = new Intent(OverlayService.this, GraphActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(g);
            }
        });
        v.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float dx, dy;
            @Override public boolean onTouch(View view, MotionEvent e) {
                detector.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: sx = lp.x; sy = lp.y; dx = e.getRawX(); dy = e.getRawY(); break;
                    case MotionEvent.ACTION_MOVE: lp.x = sx + Math.round(e.getRawX()-dx); lp.y = sy + Math.round(e.getRawY()-dy); wm.updateViewLayout(view, lp); break;
                }
                return true;
            }
        });
    }

    /*──────────────────────── UI update loop ───────────────────*/

    private final Runnable ssidRetry = () -> {
        if (connected && tryCacheSsid()) updateUi();
        else ui.postDelayed(this.ssidRetry, 500);
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateUi();
            ui.postDelayed(this, 1000);
        }
    };

    /*──────────────────────── utils ───────────────────────────*/

    private boolean tryCacheSsid() {
        WifiInfo i = wifi.getConnectionInfo();
        if (i == null) return false;
        String s = i.getSSID();
        if (s == null || s.equals("<unknown ssid>")) return false;
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        ssid = s;
        return true;
    }

    private void updateUi() {
        long d = System.currentTimeMillis() - stateStart;
        final String since = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                TimeUnit.MILLISECONDS.toHours(d),
                (TimeUnit.MILLISECONDS.toMinutes(d) % 60),
                (TimeUnit.MILLISECONDS.toSeconds(d) % 60));

        final int color = connected ? 0xFF00C853 : 0xFFD32F2F;
        ui.post(() -> {
            tvName.setText(connected ? ssid : "NENÍ SIGNÁL");
            tvSince.setText(since);
            tvName.setTextColor(0xFF000000);      // keep text black
            tvSince.setTextColor(0xFF000000);
            DrawableCompat.setTint(iv.getDrawable(), color);
        });
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Overlay Wi‑Fi", NotificationManager.IMPORTANCE_MIN);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_wifi)
                .setContentTitle("Wi‑Fi Overlay běží")
                .setOngoing(true)
                .build();
    }
}
package com.example.overlaywifi;

import android.app.AlertDialog; import android.graphics.Typeface; import android.os.Bundle; import android.os.Handler; import android.os.Looper; import android.view.MotionEvent; import android.view.ScaleGestureDetector; import android.view.View; import android.widget.Button; import android.widget.HorizontalScrollView; import android.widget.ScrollView; import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader; import java.io.FileInputStream; import java.io.InputStreamReader; import java.text.SimpleDateFormat; import java.util.Date; import java.util.Locale;

public class GraphActivity extends AppCompatActivity { private TimelineView timeline; private HorizontalScrollView scroll; private View handleLine; private TextView info; private Button btnZoomIn, btnZoomOut, btnViewCsv; private final Handler handler = new Handler(Looper.getMainLooper());

// Drag state
private float downX, startTX;

// Zoom support
private ScaleGestureDetector scaleDetector;
private float scaleFactor = 1.0f;

// Refresh every 30 seconds
private final Runnable refresher = new Runnable() {
    @Override
    public void run() {
        timeline.refresh();
        handler.postDelayed(this, 30_000);
    }
};

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_graph);

    // Bind views
    timeline   = findViewById(R.id.timeline);
    scroll     = findViewById(R.id.scroll);
    handleLine = findViewById(R.id.handle_line);
    info       = findViewById(R.id.info);
    btnZoomIn  = findViewById(R.id.btn_zoom_in);
    btnZoomOut = findViewById(R.id.btn_zoom_out);
    btnViewCsv = findViewById(R.id.btn_view_csv);

    // Ensure handle and bubble are visible and on top
    handleLine.setVisibility(View.VISIBLE);
    info.setVisibility(View.VISIBLE);
    handleLine.setElevation(8f);
    info.setElevation(16f);

    // Initial draw
    timeline.refresh();

    // Pinch-to-zoom setup
    scaleDetector = new ScaleGestureDetector(this,
        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                scaleFactor = Math.max(0.5f, Math.min(scaleFactor, 3.0f));
                timeline.setScaleX(scaleFactor);
                timeline.setScaleY(scaleFactor);
                timeline.refresh();
                return true;
            }
        });
    timeline.setOnTouchListener((v, ev) -> {
        scaleDetector.onTouchEvent(ev);
        return false;
    });

    // Zoom buttons
    btnZoomIn.setOnClickListener(v -> {
        scaleFactor = Math.min(scaleFactor * 1.25f, 3.0f);
        timeline.setScaleX(scaleFactor);
        timeline.setScaleY(scaleFactor);
        timeline.refresh();
    });
    btnZoomOut.setOnClickListener(v -> {
        scaleFactor = Math.max(scaleFactor / 1.25f, 0.5f);
        timeline.setScaleX(scaleFactor);
        timeline.setScaleY(scaleFactor);
        timeline.refresh();
    });

    // Drag info bubble (serves as handle)
    info.setOnTouchListener((v, ev) -> {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                startTX = v.getTranslationX();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getRawX() - downX;
                float newTX = startTX + dx;
                // clamp within scaled content width
                float contentW = scroll.getChildAt(0).getWidth() * scaleFactor;
                float halfW    = v.getWidth() / 2f;
                newTX = Math.max(-halfW, Math.min(newTX, contentW - halfW));
                v.setTranslationX(newTX);

                // Move white line above bubble
                float centerX = newTX + halfW;
                handleLine.setTranslationX(centerX - handleLine.getWidth() / 2f);
                updateInfo(centerX);
                return true;
            default:
                return false;
        }
    });

    // Initial padding, scroll to end, position handles
    scroll.post(() -> {
        int halfVp = scroll.getWidth() / 2;
        timeline.setPadding(
            timeline.getPaddingLeft(),
            timeline.getPaddingTop(),
            halfVp,
            timeline.getPaddingBottom()
        );
        int rawW = scroll.getChildAt(0).getWidth();
        scroll.scrollTo(rawW - scroll.getWidth(), 0);

        // Calculate translations
        float endX = rawW * scaleFactor;
        float tx   = endX - scroll.getScrollX() - (handleLine.getWidth() / 2f);
        handleLine.setTranslationX(tx);
        info.setTranslationX(tx + (handleLine.getWidth() / 2f) - (info.getWidth() / 2f));
        updateInfo(endX);

        // Align bubble at bottom of scroll area
        int marginPx = (int) (8 * getResources().getDisplayMetrics().density);
        float infoY = scroll.getY() + scroll.getHeight() - info.getHeight() - marginPx;
        info.setY(infoY);

        handleLine.bringToFront();
        info.bringToFront();
    });

    // CSV viewer
    btnViewCsv.setOnClickListener(v -> showCsvDialog());
}

@Override
protected void onResume() {
    super.onResume();
    handler.post(refresher);
}

@Override
protected void onPause() {
    super.onPause();
    handler.removeCallbacks(refresher);
}

/**
 * Given X in content coordinates, update the info bubble text.
 */
private void updateInfo(float contentX) {
    long ts = timeline.getTimestampForX((int) contentX);
    String ssid = timeline.getSsidAtTime(ts);
    String time = new SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(new Date(ts));
    info.setText(time + "\n" + ssid);
}

/**
 * Show raw CSV in a scrollable, monospace dialog.
 */
private void showCsvDialog() {
    StringBuilder sb = new StringBuilder();
    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    try (FileInputStream fis = openFileInput(OverlayService.EVENT_FILE);
         BufferedReader br = new BufferedReader(new InputStreamReader(fis))) {
        sb.append("Time                | Connected | SSID\n");
        sb.append("-------------------------------------\n");
        String line;
        while ((line = br.readLine()) != null) {
            String[] p = line.split(",", 3);
            if (p.length < 3) continue;
            long ts = Long.parseLong(p[0]);
            boolean conn = Float.parseFloat(p[1]) == 1f;
            sb.append(String.format(Locale.getDefault(),
                    "%s | %s       | %s\n",
                    fmt.format(new Date(ts)),
                    conn ? "ON" : "OFF",
                    p[2]
            ));
        }
    } catch (Exception e) {
        sb.append("Error reading CSV: ").append(e.getMessage());
    }
    ScrollView sv = new ScrollView(this);
    TextView tv = new TextView(this);
    tv.setTypeface(Typeface.MONOSPACE);
    tv.setText(sb.toString());
    tv.setPadding(16, 16, 16, 16);
    sv.addView(tv);
    new AlertDialog.Builder(this)
        .setTitle("Raw CSV Data")
        .setView(sv)
        .setPositiveButton("Close", (d, w) -> d.dismiss())
        .show();
}

}

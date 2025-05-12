package com.example.overlaywifi;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class GraphActivity extends AppCompatActivity {
    private TimelineView         timeline;
    private HorizontalScrollView scroll;
    private View                 dragKnob, handleLine;
    private TextView             info;
    private Button               btnZoomIn, btnZoomOut, btnViewCsv;
    private final Handler        handler = new Handler(Looper.getMainLooper());
    private float                knobStartX, knobInitialTranslate;

    // pinch-zoom detector
    private ScaleGestureDetector scaleDetector;
    private float                scaleFactor = 1.0f;

    // slower refresher: every 30 seconds
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (timeline != null) timeline.refresh();
            handler.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graph);

        // bind views
        timeline   = findViewById(R.id.timeline);
        scroll     = findViewById(R.id.scroll);
        handleLine = findViewById(R.id.handle_line);
        info       = findViewById(R.id.info);
        dragKnob   = findViewById(R.id.info);
        btnZoomIn  = findViewById(R.id.btn_zoom_in);
        btnZoomOut = findViewById(R.id.btn_zoom_out);
        btnViewCsv = findViewById(R.id.btn_view_csv);

        // initial draw
        timeline.refresh();

        // ensure overlay views are on top
        handleLine.bringToFront();
        info.bringToFront();
        dragKnob.bringToFront();

        // setup pinch-to-zoom
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
            return false; // allow scroll & knob touches
        });

        // zoom button handlers
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

        // drag knob listener (clamped to full viewport width)
        dragKnob.setOnTouchListener((v, ev) -> {
            scaleDetector.onTouchEvent(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    knobStartX = ev.getRawX();
                    knobInitialTranslate = v.getTranslationX();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - knobStartX;
                    float newTX = knobInitialTranslate + dx;
                    // clamp to visible scroll width
                    float viewportW = scroll.getWidth();
                    float maxTX     = viewportW - v.getWidth();
                    newTX = Math.max(0, Math.min(newTX, maxTX));
                    v.setTranslationX(newTX);
                    float centerX = newTX + v.getWidth() / 2f;
                    handleLine.setTranslationX(centerX - handleLine.getWidth() / 2f);
                    info.setTranslationX(centerX - info.getWidth() / 2f);
                    updateKnobInfo(centerX);
                    return true;
                default:
                    return false;
            }
        });

        // update info on scroll under the fixed white line
        scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldX, oldY) -> {
            float centerInViewport = scroll.getWidth() / 2f;
            // keep overlays centered
            handleLine.setTranslationX(centerInViewport - handleLine.getWidth() / 2f);
            info.setTranslationX(centerInViewport - info.getWidth() / 2f);
            // update bubble content
            updateKnobInfo(centerInViewport);
            // ensure it stays visible
            handleLine.bringToFront();
            info.bringToFront();
            dragKnob.bringToFront();
        });

        // initial padding + scroll-to-end + center overlays
        scroll.post(() -> {
            int halfView = scroll.getWidth() / 2;
            timeline.setPadding(
                    timeline.getPaddingLeft(),
                    timeline.getPaddingTop(),
                    halfView,
                    timeline.getPaddingBottom()
            );
            int fullW = scroll.getChildAt(0).getWidth();
            scroll.scrollTo(fullW - scroll.getWidth(), 0);

            float centerVp = scroll.getWidth() / 2f;
            dragKnob.setTranslationX(centerVp - dragKnob.getWidth() / 2f);
            handleLine.setTranslationX(centerVp - handleLine.getWidth() / 2f);
            info.setTranslationX(centerVp - info.getWidth() / 2f);
            updateKnobInfo(centerVp);

            // re-bring to front after layout
            handleLine.bringToFront();
            info.bringToFront();
            dragKnob.bringToFront();
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

    // Show parsed CSV with readable times
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
                String ss = p[2];
                String tstr = fmt.format(new Date(ts));
                sb.append(String.format("%s | %s       | %s\n",
                        tstr,
                        conn ? "ON" : "OFF",
                        ss
                ));
            }
        } catch (Exception e) {
            sb.append("Error reading CSV: ").append(e.getMessage());
        }
        // scrollable monospace view
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setText(sb.toString());
        tv.setPadding(16,16,16,16);
        scrollView.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Raw CSV Data")
                .setView(scrollView)
                .setPositiveButton("Close", (dlg, w) -> dlg.dismiss())
                .show();
    }

    /**
     * Given center X inside the TimelineView, show time & SSID.
     */
    private void updateKnobInfo(float knobCenterX) {
        float viewX = scroll.getScrollX() + knobCenterX;
        long ts = timeline.getTimestampForX((int)viewX);
        String ssid = timeline.getSsidAtTime(ts);
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(ts));
        info.setText(time + "\n" + ssid);
    }
}

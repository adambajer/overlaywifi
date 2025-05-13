package com.example.overlaywifi;

import android.content.Context; import android.graphics.Canvas; import android.graphics.Color; import android.graphics.Paint; import android.util.AttributeSet; import android.view.View;

import java.io.BufferedReader; import java.io.FileInputStream; import java.io.InputStreamReader; import java.text.SimpleDateFormat; import java.util.ArrayList; import java.util.Collections; import java.util.Date; import java.util.List; import java.util.Locale;

/**

A custom View that draws a timeline of Wi-Fi connectivity:

• horizontal green/red bars for on/off periods

• vertical markers labeled with SSID (or "NENÍ SIGNÁL")

• hourly tick marks on the time axis

Call refresh() to reload the CSV and redraw. */ public class TimelineView extends View { private static final int   COLOR_ON        = 0xFF00C853; private static final int   COLOR_OFF       = 0xFFD32F2F; private static final int   BG_COLOR        = 0xFF222222; private static final int   AXIS_COLOR      = 0xFF888888; private static final long  HOUR_MS         = 3_600_000L; private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm", Locale.getDefault());

private final Paint paintBar  = new Paint(Paint.ANTI_ALIAS_FLAG); private final Paint paintLine = new Paint(Paint.ANTI_ALIAS_FLAG); private final Paint paintText = new Paint(Paint.ANTI_ALIAS_FLAG);

private final List<Event> events = new ArrayList<>();

// Holds one CSV record private static class Event { long timestamp; boolean connected; String ssid; Event(long ts, boolean c, String s) { timestamp = ts; connected = c; ssid = s; } }

public TimelineView(Context ctx, AttributeSet attrs) { super(ctx, attrs); paintLine.setColor(AXIS_COLOR); paintLine.setStrokeWidth(2f); paintText.setColor(Color.WHITE); paintText.setTextSize(32f); paintText.setTextAlign(Paint.Align.CENTER); loadEvents(); }

@Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) { if (events.isEmpty()) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return; }

long start = (events.get(0).timestamp / HOUR_MS) * HOUR_MS;
 long end   = ((events.get(events.size() - 1).timestamp + HOUR_MS - 1)
         / HOUR_MS) * HOUR_MS;
 float pxPerMs = 200f / HOUR_MS;                      // 200 px per hour
 int measuredW = Math.round((end - start) * pxPerMs);
 int width = resolveSize(measuredW, widthMeasureSpec);
 int height = MeasureSpec.getSize(heightMeasureSpec);
 setMeasuredDimension(width, height);

}

/** Load events from internal CSV */ private void loadEvents() { events.clear(); try (FileInputStream fis = getContext() .openFileInput(OverlayService.EVENT_FILE); BufferedReader br = new BufferedReader(new InputStreamReader(fis))) { String line; while ((line = br.readLine()) != null) { String[] p = line.split(",", 3); if (p.length < 3) continue; long ts    = Long.parseLong(p[0]); boolean c  = Float.parseFloat(p[1]) == 1f; String ss  = p[2]; events.add(new Event(ts, c, ss)); } } catch (Exception ignored) {} Collections.sort(events, (a, b) -> Long.compare(a.timestamp, b.timestamp)); }

/** Public: reload CSV and redraw */ public void refresh() { loadEvents(); requestLayout();            // ensure onMeasure runs again invalidate(); }

@Override protected void onDraw(Canvas c) { super.onDraw(c); if (events.isEmpty()) return;

int w = getWidth(), h = getHeight();
 c.drawColor(BG_COLOR);

 int barTop    = h / 3;
 int barBottom = h * 2 / 3;

 long start = (events.get(0).timestamp / HOUR_MS) * HOUR_MS;
 long end   = ((events.get(events.size() - 1).timestamp + HOUR_MS - 1)
         / HOUR_MS) * HOUR_MS;
 float pxPerMs = (float) w / (end - start);

 // draw on/off segments
 for (int i = 0; i < events.size(); i++) {
     Event e = events.get(i);
     long segEnd = (i + 1 < events.size()) ? events.get(i + 1).timestamp : end;
     float x1 = (e.timestamp - start) * pxPerMs;
     float x2 = (segEnd - start) * pxPerMs;
     paintBar.setColor(e.connected ? COLOR_ON : COLOR_OFF);
     c.drawRect(x1, barTop, x2, barBottom, paintBar);
 }

 // vertical markers + SSID labels
 float lastLabelX = -Float.MAX_VALUE;
 float minLabelSpacing = paintText.measureText("NENÍ SIGNÁL") * 1.1f;
 for (Event e : events) {
     float x = (e.timestamp - start) * pxPerMs;
     paintLine.setStrokeWidth(2f);
     c.drawLine(x, barTop, x, barBottom, paintLine);
     if (x - lastLabelX >= minLabelSpacing) {
         String label = e.connected ? e.ssid : "NENÍ SIGNÁL";
         c.drawText(label, x, barTop - 16f, paintText);
         lastLabelX = x;
     }
 }

 // hourly tick marks
 paintLine.setStrokeWidth(1f);
 for (long t = start; t <= end; t += HOUR_MS) {
     float x = (t - start) * pxPerMs;
     c.drawLine(x, barBottom, x, barBottom + 8f, paintLine);
     String lbl = TIME_FMT.format(new Date(t));
     c.drawText(lbl, x, barBottom + 32f, paintText);
 }

}

public long getTimestampForX(float x) { if (events.isEmpty()) return -1L; long start = (events.get(0).timestamp / HOUR_MS) * HOUR_MS; long end   = ((events.get(events.size() - 1).timestamp + HOUR_MS - 1) / HOUR_MS) * HOUR_MS; float pxPerMs = (float) getWidth() / (end - start); return start + (long) (x / pxPerMs); }

/** Returns the SSID (or “NENÍ SIGNÁL”) at or before the given timestamp */ public String getSsidAtTime(long timestamp) { String label = "NENÍ SIGNÁL"; for (Event e : events) { if (e.timestamp <= timestamp) { label = e.connected ? e.ssid : "NENÍ SIGNÁL"; } else { break; } } return label; } }



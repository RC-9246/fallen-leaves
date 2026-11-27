package dev.pagetrim.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.Locale;

/** Shows the foreground samples collected by the memory page's existing refresh loop. */
final class MemoryTrend extends LinearLayout {
  private static final long INTERVAL = 1_000L;
  private static final long WINDOW = 120_000L;
  private static final long MAX_GAP = 3 * INTERVAL;
  private static final int LIMIT = (int) (WINDOW / INTERVAL) + 1;
  private static final long MIB = 1L << 20;
  private static final long GIB = 1L << 30;

  private static final class Point {
    final long time, available;
    final boolean startsSegment;

    Point(long time, long available, boolean startsSegment) {
      this.time = time;
      this.available = available;
      this.startsSegment = startsSegment;
    }
  }

  /** Activity-retainable data. There are no listeners, Views, Contexts or scheduled tasks here. */
  static final class Samples {
    private final ArrayDeque<Point> points = new ArrayDeque<>(LIMIT);
    private boolean interrupted;

    private void expire(long now) {
      while (!points.isEmpty() && now - points.getFirst().time > WINDOW) points.removeFirst();
    }

    private void add(long now, long available) {
      expire(now);
      if (available < 0) {
        interrupted = true;
        return;
      }
      Point last = points.peekLast();
      if (last != null && now - last.time < INTERVAL) return;
      if (points.size() == LIMIT) points.removeFirst();
      points.addLast(new Point(now, available, interrupted || last == null || now - last.time > MAX_GAP));
      interrupted = false;
    }
  }

  private final Samples samples;
  private final Plot plot;
  private final TextView summary;
  private long now;
  private double low, high;
  private String lowLabel = "", highLabel = "";

  MemoryTrend(Context context, Samples samples) {
    super(context);
    this.samples = samples;
    setOrientation(VERTICAL);
    plot = new Plot(context);
    plot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(plot, LayoutParams.MATCH_PARENT, dp(70) + Math.round(plot.axis.getTextSize() * 3));
    summary = new TextView(context);
    summary.setTextSize(14);
    summary.setTextColor(context.getColor(R.color.leaf_secondary));
    summary.setPadding(0, dp(4), 0, 0);
    addView(summary, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    now = SystemClock.elapsedRealtime();
    samples.expire(now);
    refresh(false);
  }

  void record(MemStats sample) {
    now = SystemClock.elapsedRealtime();
    samples.add(now, sample.available);
    refresh(sample.available < 0);
  }

  private int dp(float size) {
    return Math.round(size * getResources().getDisplayMetrics().density);
  }

  private static String gib(double bytes) {
    return String.format(Locale.ROOT, "%.2f GiB", bytes / GIB);
  }

  private void refresh(boolean unavailable) {
    Point last = samples.points.peekLast();
    String text = "等待可用运存样本";
    if (last != null) {
      long min = last.available, max = last.available;
      Point firstInSegment = samples.points.getFirst();
      for (Point p : samples.points) {
        min = Math.min(min, p.available);
        max = Math.max(max, p.available);
        if (p.startsSegment) firstInSegment = p;
      }
      // Leave at least 128 MiB of vertical span so normal jitter is not exaggerated.
      double padding = Math.max(64.0 * MIB, (max - min) * 0.1);
      low = Math.max(0, min - padding);
      high = max + padding;
      lowLabel = gib(low);
      highLabel = gib(high);
      text = "当前可用 " + gib(last.available);
      long seconds = (last.time - firstInSegment.time) / 1000;
      if (seconds > 0) {
        long delta = Math.round((last.available - firstInSegment.available) / (double) MIB);
        String change = delta == 0 ? "变化小于 1 MiB"
            : (delta > 0 ? "增加 " : "减少 ") + Math.abs(delta) + " MiB";
        text += "，近 " + seconds + " 秒" + change;
      }
      if (samples.points.size() > 1) {
        text += "\n观测范围 " + gib(min) + " 至 " + gib(max);
      }
    }
    if (unavailable) {
      text = last == null ? "当前可用运存读数暂不可用" : "当前读数暂不可用，上次可用 " + gib(last.available);
    }
    if (!text.contentEquals(summary.getText())) summary.setText(text);
    plot.invalidate();
  }

  private final class Plot extends View {
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int guideColor, trendColor;

    Plot(Context context) {
      super(context);
      guideColor = context.getColor(R.color.leaf_rule);
      trendColor = context.getColor(R.color.leaf_accent);
      axis.setColor(context.getColor(R.color.leaf_secondary));
      axis.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
          getResources().getDisplayMetrics()));
      pen.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (samples.points.isEmpty()) return;
      float left = Math.max(axis.measureText(lowLabel), axis.measureText(highLabel)) + dp(8);
      float right = getWidth() - dp(3);
      float top = -axis.ascent() + dp(3);
      float bottom = getHeight() - axis.getFontSpacing() - dp(9);
      if (right <= left || bottom <= top) return;
      canvas.drawText(highLabel, 0, top, axis);
      canvas.drawText(lowLabel, 0, bottom, axis);
      if (right - left >= axis.measureText("2 分钟前") + axis.measureText("现在") + dp(6)) {
        canvas.drawText("2 分钟前", left, getHeight() - axis.descent(), axis);
      }
      canvas.drawText("现在", right - axis.measureText("现在"), getHeight() - axis.descent(), axis);
      pen.setColor(guideColor);
      pen.setStrokeWidth(dp(1));
      canvas.drawLine(left, top, right, top, pen);
      canvas.drawLine(left, bottom, right, bottom, pen);
      pen.setColor(trendColor);
      pen.setStrokeWidth(dp(2));
      float previousX = 0, previousY = 0;
      boolean hasPrevious = false;
      for (Point p : samples.points) {
        float x = right - (right - left) * (now - p.time) / WINDOW;
        float y = bottom - (bottom - top) * (float) ((p.available - low) / (high - low));
        if (hasPrevious && !p.startsSegment) canvas.drawLine(previousX, previousY, x, y, pen);
        if (!hasPrevious || p.startsSegment || p == samples.points.peekLast()) {
          canvas.drawCircle(x, y, dp(2), pen);
        }
        previousX = x;
        previousY = y;
        hasPrevious = true;
      }
    }
  }
}

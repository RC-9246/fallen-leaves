package dev.pagetrim.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.Locale;

/** Foreground memory counters. Reading and scheduling are owned by the activity. */
final class MemoryDashboard extends LinearLayout {
  private static final int MATCH = LayoutParams.MATCH_PARENT, WRAP = LayoutParams.WRAP_CONTENT;
  private static final int INK = 0xff152d28, MUTED = 0xff526860, RULE = 0xffd9e3db;
  private static final int NORMAL = 0xff17614e, HIGH = 0xff855100, VERY_HIGH = 0xffa13636;
  private static final String UNAVAILABLE = "读数暂不可用";

  private final SharedPreferences preferences;
  private final Group physical, swap, cache;
  private final TextView physicalUsed, physicalAvailable, physicalTotal;
  private final TextView swapUsed, swapAvailable, swapTotal;
  private final TextView fileCache, slab, psi;
  private MemStats latest;

  MemoryDashboard(Context context) {
    super(context);
    preferences = context.getSharedPreferences("memory_dashboard", Context.MODE_PRIVATE);
    setOrientation(VERTICAL);
    GradientDrawable surface = new GradientDrawable();
    surface.setColor(Color.WHITE);
    surface.setCornerRadius(dp(20));
    surface.setStroke(dp(1), RULE);
    setBackground(surface);
    setPadding(dp(16), dp(4), dp(16), dp(12));

    physical = new Group("physical", "物理内存", true);
    physicalUsed = physical.metric("已用");
    physicalAvailable = physical.metric("可用");
    physicalTotal = physical.metric("总量");
    physical.note("已用按总量减去可用估算。可用包括内核可调给应用的部分缓存。");
    divider();
    swap = new Group("swap", "交换空间", false);
    swapUsed = swap.metric("已用");
    swapAvailable = swap.metric("可用");
    swapTotal = swap.metric("总量");
    swap.note("交换空间按逻辑页大小统计。使用 zRAM 时，实际占用的物理内存取决于压缩效果和管理开销。");
    divider();
    cache = new Group("cache", "系统缓存", false);
    fileCache = cache.metric("文件页缓存估算");
    slab = cache.metric("可回收内核缓存");
    psi = cache.metric("内存等待时间占比");
    cache.note("占比由文件页缓存（Cached − Shmem）与可回收内核缓存（SReclaimable）之和除以物理内存总量估算。实际可回收量由内核决定。等待时间取近 10 秒至少一个任务因内存受阻的时间占比（PSI some）。");
    divider();
    TextView legend = label("物理内存与交换空间的已用占比达到 70% 时标为较高，达到 90% 时标为很高。系统缓存占比用蓝色深浅表示。", 12, MUTED);
    legend.setPadding(0, dp(10), 0, 0);
    addView(legend, MATCH, WRAP);
  }

  void update(MemStats sample) {
    latest = sample;
    long usedRam = sample.usedRam();
    physical.usage(usedRam, sample.total, false);
    if (physical.expanded()) {
      value(physicalUsed, usedRam);
      value(physicalAvailable, sample.available);
      value(physicalTotal, sample.total > 0 ? sample.total : -1);
    }

    long usedSwap = sample.usedSwap();
    swap.usage(usedSwap, sample.swapTotal, true);
    if (swap.expanded()) {
      value(swapUsed, usedSwap);
      value(swapAvailable, sample.swapFree);
      value(swapTotal, sample.swapTotal);
    }

    int share = permille(sample.estimatedCache(), sample.total);
    int cacheColor = share < 0 ? MUTED : Color.rgb(54 - 38 * share / 1000,
        112 - 55 * share / 1000, 153 - 49 * share / 1000);
    cache.indicator(share < 0 ? "缓存占比暂不可用" : "占物理内存 " + percent(share), share, cacheColor);
    if (cache.expanded()) {
      value(fileCache, sample.cached);
      value(slab, sample.reclaimable);
      assign(psi, sample.psiSome < 0 ? UNAVAILABLE : String.format(Locale.ROOT, "%.2f%%", sample.psiSome));
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private TextView label(String text, int size, int color) {
    TextView view = new TextView(getContext());
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    return view;
  }

  private void divider() {
    View line = new View(getContext());
    line.setBackgroundColor(RULE);
    addView(line, MATCH, dp(1));
  }

  private static void assign(TextView view, String text) {
    if (!text.contentEquals(view.getText())) view.setText(text);
  }

  private static void value(TextView view, long bytes) {
    assign(view, bytes < 0 ? UNAVAILABLE : MainActivity.fmt(bytes));
  }

  private static int permille(long used, long total) {
    if (used < 0 || total <= 0 || used > total) return -1;
    return (int) (1000.0 * used / total);
  }

  private static String percent(int permille) {
    return (permille / 10) + "." + (permille % 10) + "%";
  }

  private final class Group {
    final String key, name;
    final LinearLayout header, details;
    final TextView summary, toggle;
    final ProgressBar meter;
    int lastColor;

    Group(String key, String name, boolean initiallyExpanded) {
      this.key = key;
      this.name = name;
      Context context = getContext();
      header = new LinearLayout(context);
      header.setGravity(Gravity.CENTER_VERTICAL);
      header.setMinimumHeight(dp(55));
      header.setPadding(0, dp(8), 0, dp(6));
      header.setBackground(new RippleDrawable(ColorStateList.valueOf(0x193a6d55), null, null));
      header.setFocusable(true);
      header.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
      header.setAccessibilityDelegate(new View.AccessibilityDelegate() {
        @Override public void onInitializeAccessibilityNodeInfo(View v, AccessibilityNodeInfo info) {
          super.onInitializeAccessibilityNodeInfo(v, info);
          info.setClassName("android.widget.Button");
        }
      });
      LinearLayout titles = new LinearLayout(context);
      titles.setOrientation(VERTICAL);
      titles.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      TextView title = label(name, 18, INK);
      title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      titles.addView(title, MATCH, WRAP);
      summary = label("等待读数", 14, MUTED);
      titles.addView(summary, MATCH, WRAP);
      header.addView(titles, new LayoutParams(0, WRAP, 1));
      toggle = label("", 14, NORMAL);
      toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      header.addView(toggle, WRAP, WRAP);
      addView(header, MATCH, WRAP);

      meter = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
      meter.setMax(1000);
      meter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      meter.setProgressBackgroundTintList(ColorStateList.valueOf(RULE));
      LayoutParams barLayout = new LayoutParams(MATCH, dp(5));
      barLayout.bottomMargin = dp(8);
      addView(meter, barLayout);
      details = new LinearLayout(context);
      details.setOrientation(VERTICAL);
      details.setPadding(0, 0, 0, dp(10));
      addView(details, MATCH, WRAP);
      setExpanded(preferences.getBoolean(key, initiallyExpanded));
      header.setOnClickListener(v -> {
        boolean next = !expanded();
        setExpanded(next);
        preferences.edit().putBoolean(key, next).apply();
        if (next && latest != null) update(latest);
      });
    }

    boolean expanded() {
      return details.getVisibility() == View.VISIBLE;
    }

    void setExpanded(boolean expanded) {
      details.setVisibility(expanded ? View.VISIBLE : View.GONE);
      toggle.setText(expanded ? "收起" : "展开");
      describe();
    }

    void describe() {
      String description = name + "，" + summary.getText() + "，" + toggle.getText();
      if (!description.contentEquals(header.getContentDescription() == null
          ? "" : header.getContentDescription())) header.setContentDescription(description);
    }

    TextView metric(String name) {
      LinearLayout line = new LinearLayout(getContext());
      line.setGravity(Gravity.CENTER_VERTICAL);
      line.setPadding(0, dp(2), 0, dp(2));
      TextView title = label(name, 14, MUTED);
      line.addView(title, new LayoutParams(0, WRAP, 1));
      TextView reading = label("—", 14, INK);
      line.addView(reading, WRAP, WRAP);
      details.addView(line, MATCH, WRAP);
      return reading;
    }

    void note(String text) {
      TextView explanation = label(text, 12, MUTED);
      explanation.setPadding(0, dp(6), 0, 0);
      details.addView(explanation, MATCH, WRAP);
    }

    void usage(long used, long total, boolean isSwap) {
      int part = permille(used, total);
      if (part < 0) {
        indicator(isSwap && total == 0 ? "未启用交换空间" : "使用率暂不可用", -1, MUTED);
        return;
      }
      String level = part >= 900 ? "很高" : part >= 700 ? "较高" : "常规";
      int color = part >= 900 ? VERY_HIGH : part >= 700 ? HIGH : NORMAL;
      indicator("已用 " + percent(part) + " · " + level, part, color);
    }

    void indicator(String text, int part, int color) {
      if (!text.contentEquals(summary.getText())) {
        summary.setText(text);
        describe();
      }
      if (color != lastColor) {
        summary.setTextColor(color);
        meter.setProgressTintList(ColorStateList.valueOf(color));
        lastColor = color;
      }
      meter.setVisibility(part < 0 ? View.INVISIBLE : View.VISIBLE);
      if (part >= 0 && part != meter.getProgress()) meter.setProgress(part);
    }
  }
}

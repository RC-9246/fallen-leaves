package dev.pagetrim.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** On-demand, unprivileged measurements. Missing kernel counters remain unknown. */
final class PublicMemorySample {
  enum Section {
    SYSTEM("系统低内存状态"), SWAP("交换与重大缺页"), PRESSURE("内存等待时间"), EXITS("落叶的进程退出记录");
    final String label;
    Section(String label) { this.label = label; }
  }

  final Map<String, Long> values = new HashMap<>();
  long elapsed;
  int boot = -1;
  private static PublicMemorySample processBaseline;

  static PublicMemorySample read(Context context, EnumSet<Section> selected) {
    PublicMemorySample now = new PublicMemorySample();
    if (selected.contains(Section.SYSTEM)) {
      ActivityManager manager = context.getSystemService(ActivityManager.class);
      ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
      try {
        if (manager != null) {
          manager.getMemoryInfo(info);
          now.values.put("available", info.availMem);
          now.values.put("threshold", info.threshold);
          now.values.put("low", info.lowMemory ? 1L : 0L);
        }
      } catch (RuntimeException ignored) {
        // Other sources may still be available when the system service fails.
      }
    }
    if (selected.contains(Section.SWAP)) {
      try (BufferedReader file = new BufferedReader(new FileReader("/proc/meminfo"))) {
        Map<String, Long> info = new HashMap<>();
        MemStats.readMeminfo(file, info);
        long free = info.getOrDefault("SwapFree", -1L), total = info.getOrDefault("SwapTotal", -1L);
        if (free >= 0 && total >= free) now.values.put("swap", total - free);
      } catch (IOException | SecurityException ignored) {
        // The kernel may restrict either source independently.
      }
      now.readFile("/proc/vmstat", false);
    }
    if (selected.contains(Section.PRESSURE)) now.readFile("/proc/pressure/memory", true);
    now.elapsed = SystemClock.elapsedRealtime();
    try {
      now.boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    } catch (RuntimeException ignored) {
      // A device that withholds its boot count can still compare within this process.
    }
    return now;
  }

  private void readFile(String path, boolean pressure) {
    try (BufferedReader file = new BufferedReader(new FileReader(path))) {
      readCounters(file, pressure);
    } catch (IOException | SecurityException ignored) {
      // Never substitute a zero for a denied or absent source.
    }
  }

  void readCounters(BufferedReader file, boolean pressure) throws IOException {
    for (String line; (line = file.readLine()) != null; ) {
      String[] fields = line.trim().split("\\s+");
      if (fields.length < 2) continue;
      String key = fields[0];
      if (pressure && (key.equals("some") || key.equals("full"))) {
        for (int i = 1; i < fields.length; i++) {
          if (fields[i].startsWith("total=")) readNumber(key, fields[i].substring(6));
        }
      } else if (!pressure && fields.length == 2
          && (key.equals("pswpin") || key.equals("pswpout") || key.equals("pgmajfault"))) {
        readNumber(key, fields[1]);
      }
    }
  }

  private void readNumber(String key, String number) {
    try {
      long value = Long.parseLong(number);
      if (value >= 0) values.put(key, value);
    } catch (NumberFormatException ignored) {
      // Ignore malformed and overflowing counters independently.
    }
  }

  public void saveBaseline(Context context) {
    processBaseline = this;
    SharedPreferences.Editor edit = context.getSharedPreferences("public_memory_start", Context.MODE_PRIVATE)
        .edit().clear();
    if (boot >= 0) {
      edit.putLong("elapsed", elapsed).putInt("boot", boot);
      for (Map.Entry<String, Long> entry : values.entrySet()) edit.putLong(entry.getKey(), entry.getValue());
    }
    edit.apply();
  }

  PublicMemorySample baseline(Context context) {
    if (processBaseline != null && processBaseline.elapsed <= elapsed) return processBaseline;
    SharedPreferences saved = context.getSharedPreferences("public_memory_start", Context.MODE_PRIVATE);
    if (boot < 0 || saved.getInt("boot", -1) != boot) return null;
    long startTime = saved.getLong("elapsed", -1);
    if (startTime < 0 || startTime > elapsed) return null;
    PublicMemorySample start = new PublicMemorySample();
    start.boot = boot;
    start.elapsed = startTime;
    for (String key : values.keySet()) if (saved.contains(key)) start.values.put(key, saved.getLong(key, -1));
    return start;
  }

  private String gauge(String key, PublicMemorySample start) {
    Long value = values.get(key);
    if (value == null) return "未提供";
    String amount = MainActivity.fmt(value);
    Long prior = start == null ? null : start.values.get(key);
    if (prior == null) return amount;
    long diff = value - prior;
    return amount + "（" + (diff < 0 ? "−" : diff > 0 ? "+" : "") + MainActivity.fmt(Math.abs(diff)) + "）";
  }

  String counter(String key, PublicMemorySample start, boolean pressure) {
    Long value = values.get(key);
    if (value == null) return "未提供";
    Long prior = start == null ? null : start.values.get(key);
    if (prior == null) {
      return pressure ? String.format(Locale.ROOT, "累计 %.3f 秒", value / 1e6) : "累计 " + value;
    }
    if (value < prior) return "计数已重置，请重设起点";
    long delta = value - prior;
    if (!pressure) return "区间 " + delta;
    long duration = elapsed - start.elapsed;
    String seconds = String.format(Locale.ROOT, "区间 %.3f 秒", delta / 1e6);
    double percent = duration > 0 ? delta / (duration * 10.0) : -1;
    return percent >= 0 && percent <= 100
        ? seconds + String.format(Locale.ROOT, "（%.2f%%）", percent) : seconds;
  }

  String report(EnumSet<Section> selected, PublicMemorySample start) {
    StringBuilder report = new StringBuilder();
    if (selected.contains(Section.SYSTEM)) {
      report.append("系统低内存信号　");
      Long low = values.get("low");
      report.append(low == null ? "未提供" : low == 1 ? "已触发" : "未触发")
          .append("\n系统可用运存　").append(gauge("available", start))
          .append("\n系统低内存阈值　").append(gauge("threshold", null));
      if (values.containsKey("available") && values.containsKey("threshold")) {
        long margin = values.get("available") - values.get("threshold");
        report.append(margin < 0 ? "\n低于阈值　" : "\n高于阈值的余量　").append(MainActivity.fmt(Math.abs(margin)));
      }
    }
    if (selected.contains(Section.SWAP)) {
      separate(report);
      report.append("逻辑交换区已用　").append(gauge("swap", start))
          .append("\n换入页数　").append(counter("pswpin", start, false))
          .append("\n换出页数　").append(counter("pswpout", start, false))
          .append("\n重大缺页次数　").append(counter("pgmajfault", start, false));
    }
    if (selected.contains(Section.PRESSURE)) {
      separate(report);
      report.append("至少一个任务等待内存　").append(counter("some", start, true))
          .append("\n全部活跃任务同时等待　").append(counter("full", start, true));
    }
    if (report.length() == 0) return "";
    String label = start == null ? "设置起点后，可对照切换应用前后的整机变化。"
        : "距起点 " + (elapsed - start.elapsed) / 1000 + " 秒（含休眠），差值反映同期整机活动。";
    return label + "\n\n" + report;
  }

  private static void separate(StringBuilder text) {
    if (text.length() > 0) text.append("\n\n");
  }
}

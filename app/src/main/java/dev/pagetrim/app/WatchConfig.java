package dev.pagetrim.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class WatchConfig {
  public final boolean thresholdEnabled, intervalEnabled, lowMemoryOnly;
  public final int usedPercent, intervalMinutes;
  public final WatchService.Mode mode;

  public WatchConfig(boolean thresholdEnabled, int usedPercent, boolean intervalEnabled,
      int intervalMinutes, WatchService.Mode mode) {
    this(thresholdEnabled, usedPercent, intervalEnabled, intervalMinutes, mode, false);
  }

  public WatchConfig(boolean thresholdEnabled, int usedPercent, boolean intervalEnabled,
      int intervalMinutes, WatchService.Mode mode, boolean lowMemoryOnly) {
    if (usedPercent < 1 || usedPercent > 99 || intervalMinutes < 1 || intervalMinutes > 1440)
      throw new IllegalArgumentException("已用运存须为 1–99%，间隔须为 1–1440 分钟。");
    if (mode == null || mode == WatchService.Mode.OFF)
      throw new IllegalArgumentException("请选择提醒或回收模式。");
    this.thresholdEnabled = thresholdEnabled;
    this.usedPercent = usedPercent;
    this.intervalEnabled = intervalEnabled;
    this.intervalMinutes = intervalMinutes;
    this.mode = mode;
    this.lowMemoryOnly = lowMemoryOnly;
  }

  public boolean hasTriggers() {
    return thresholdEnabled || intervalEnabled;
  }

  boolean sameSettings(WatchConfig other) {
    return thresholdEnabled == other.thresholdEnabled && usedPercent == other.usedPercent
        && intervalEnabled == other.intervalEnabled && intervalMinutes == other.intervalMinutes
        && mode == other.mode && lowMemoryOnly == other.lowMemoryOnly;
  }

  public static WatchConfig load(Context context) {
    SharedPreferences p = context.getSharedPreferences("memory_watch", Context.MODE_PRIVATE);
    // Previous releases persisted cooldowns only. Never infer consent from a previous attempt.
    return new WatchConfig(
        p.getBoolean("threshold_enabled", false), clamp(p.getInt("used_percent", 90), 1, 99),
        p.getBoolean("interval_enabled", false), clamp(p.getInt("interval_minutes", 30), 1, 1440),
        WatchService.Mode.ROOT_RECLAIM.name().equals(p.getString("action_mode", ""))
            ? WatchService.Mode.ROOT_RECLAIM : WatchService.Mode.ALERT_ONLY, p.getBoolean("low_memory_only", false));
  }

  public void save(Context context) {
    context.getSharedPreferences("memory_watch", Context.MODE_PRIVATE).edit()
        .putBoolean("threshold_enabled", thresholdEnabled).putInt("used_percent", usedPercent)
        .putBoolean("interval_enabled", intervalEnabled).putInt("interval_minutes", intervalMinutes)
        .putString("action_mode", mode.name()).putBoolean("low_memory_only", lowMemoryOnly).apply();
  }

  private static int clamp(int value, int low, int high) {
    return Math.max(low, Math.min(high, value));
  }
}

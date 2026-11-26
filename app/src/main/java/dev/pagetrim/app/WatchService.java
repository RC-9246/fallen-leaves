package dev.pagetrim.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WatchService extends Service {
  public enum Mode {
    OFF,
    ALERT_ONLY,
    ROOT_RECLAIM
  }

  public static final String ACTION_CONFIGURE = "dev.pagetrim.app.watch.CONFIGURE";
  private static final String TAG = "PageTrimWatch";
  private static final String ACTION_START = "dev.pagetrim.app.watch.START";
  private static final String ACTION_STOP = "dev.pagetrim.app.watch.STOP";
  private static final String EXTRA_THRESHOLD = "threshold";
  private static final String EXTRA_USED = "used_percent";
  private static final String EXTRA_THRESHOLD_ENABLED = "threshold_enabled";
  private static final String EXTRA_INTERVAL = "interval_minutes";
  private static final String EXTRA_INTERVAL_ENABLED = "interval_enabled";
  private static final String EXTRA_MODE = "mode";
  private static final String ALERT_CHANNEL = "low_memory_alerts";
  private static final int ALERT_ID = 105;
  private static final String CHANNEL = "memory_watch";
  private static final int NOTIFICATION_ID = 104;

  private static final String PREFS = "memory_watch";
  private static final String LAST_ATTEMPT = "last_attempt_elapsed_ms";
  private static final String LAST_ALERT = "last_alert_elapsed_ms";
  private static final Object ATTEMPT_LOCK = new Object();

  private static volatile Mode activeMode = Mode.OFF;
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile Sampler sampler;


  public static boolean isRunning() {
    return activeMode != Mode.OFF;
  }

  public static Mode mode() {
    return activeMode;
  }

  public static boolean notificationsEnabled(Context context, Mode mode) {
    if (mode == null || mode == Mode.OFF) return false;
    if (Build.VERSION.SDK_INT >= 33
        && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return false;
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null || !manager.areNotificationsEnabled()) return false;
    return channelEnabled(manager, CHANNEL)
        && (mode != Mode.ALERT_ONLY || channelEnabled(manager, ALERT_CHANNEL));
  }

  private static boolean channelEnabled(NotificationManager manager, String id) {
    NotificationChannel channel = manager.getNotificationChannel(id);
    return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
  }

  public static void start(Context context, int thresholdPercent) {
    start(context, thresholdPercent, Mode.ROOT_RECLAIM);
  }

  public static void start(Context context, int thresholdPercent, Mode mode) {
    if (!validThreshold(thresholdPercent))
      throw new IllegalArgumentException("低内存阈值必须为 5%、8% 或 10%。");
    start(context, new WatchConfig(true, 100 - thresholdPercent, false, 30, mode));
  }

  public static void start(Context context, WatchConfig config) {
    if (config == null || !config.hasTriggers()) {
      throw new IllegalArgumentException("请至少启用一个触发条件。");
    }
    if (!notificationsEnabled(context, config.mode)) {
      throw new IllegalStateException("请允许应用发送通知，并开启相应的通知类别。");
    }
    Intent request = new Intent(context, WatchService.class).setAction(ACTION_START)
        .putExtra(EXTRA_USED, config.usedPercent)
        .putExtra(EXTRA_THRESHOLD_ENABLED, config.thresholdEnabled)
        .putExtra(EXTRA_INTERVAL, config.intervalMinutes)
        .putExtra(EXTRA_INTERVAL_ENABLED, config.intervalEnabled)
        .putExtra(EXTRA_MODE, config.mode.name());
    context.startForegroundService(request);
    config.save(context);
  }

  public static void stop(Context context) {
    context.stopService(new Intent(context, WatchService.class));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? null : intent.getAction();
    if (ACTION_STOP.equals(action)) {
      cancelSampling();
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf(startId);
      return START_NOT_STICKY;
    }

    if (!ACTION_START.equals(action) || intent == null) {
      stopSelf(startId);
      return START_NOT_STICKY;
    }
    try {
      Mode mode = Mode.valueOf(intent.getStringExtra(EXTRA_MODE));
      WatchConfig config;
      if (intent.hasExtra(EXTRA_USED)) {
        config = new WatchConfig(intent.getBooleanExtra(EXTRA_THRESHOLD_ENABLED, false),
            intent.getIntExtra(EXTRA_USED, -1), intent.getBooleanExtra(EXTRA_INTERVAL_ENABLED, false),
            intent.getIntExtra(EXTRA_INTERVAL, -1), mode);
      } else {
        int threshold = intent.getIntExtra(EXTRA_THRESHOLD, -1);
        if (!validThreshold(threshold)) throw new IllegalArgumentException("阈值无效");
        config = new WatchConfig(true, 100 - threshold, false, 30, mode);
      }
      if (!config.hasTriggers()) throw new IllegalArgumentException("没有启用的触发条件");
      startForeground(NOTIFICATION_ID, createNotification(config));
      if (!notificationsEnabled(this, mode))
        throw new IllegalStateException("系统通知或监控通知类别已关闭。");
      Sampler current = sampler;
      if (current == null || !current.config.sameSettings(config)) {
        cancelSampling();
        Sampler next = new Sampler(config);
        sampler = next;
        next.scheduleNext(next.timing.delay(SystemClock.elapsedRealtime()));
      }
      activeMode = mode;
    } catch (RuntimeException failure) {
      cancelSampling();
      History.add(this, "监控启动失败", errorDetail(failure));
      stopSelf(startId);
    }
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    cancelSampling();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  private void cancelSampling() {
    activeMode = Mode.OFF;
    Sampler current = sampler;
    sampler = null;
    getSystemService(NotificationManager.class).cancel(ALERT_ID);
    if (current != null) current.worker.shutdownNow();
  }

  private Notification createNotification(WatchConfig config) {
    Mode mode = config.mode;
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "内存监控", NotificationManager.IMPORTANCE_LOW));
    if (mode == Mode.ALERT_ONLY) {
      manager.createNotificationChannel(
          new NotificationChannel(ALERT_CHANNEL, "低运存提醒", NotificationManager.IMPORTANCE_DEFAULT));
    }
    Intent stop = new Intent(this, WatchService.class).setAction(ACTION_STOP);
    PendingIntent stopAction =
        PendingIntent.getService(
            this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name) + " 正在监控可用内存")
            .setContentText(summary(config) + (mode == Mode.ALERT_ONLY ? " · 仅提醒" : " · 仅回收文件页缓存"))
            .setContentIntent(openApp(false))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "调整", openApp(true)).build())
            .addAction(new Notification.Action.Builder(null, "停止", stopAction).build());
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
    }
    return builder.build();
  }

  private PendingIntent openApp(boolean configure) {
    Intent open = new Intent(this, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if (configure) open.setAction(ACTION_CONFIGURE);
    return PendingIntent.getActivity(
        this, configure ? 1 : 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static String summary(WatchConfig config) {
    String usage = "已用 ≥ " + config.usedPercent + "%";
    String interval = "每 " + config.intervalMinutes + " 分钟";
    if (config.thresholdEnabled && config.intervalEnabled) return usage + " 或 " + interval;
    return config.thresholdEnabled ? usage : interval;
  }

  private static boolean validThreshold(int threshold) {
    return threshold == 5 || threshold == 8 || threshold == 10;
  }

  private static String errorDetail(Exception error) {
    String message = error.getMessage();
    if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
    return message.length() > 160 ? message.substring(0, 160) : message;
  }

  private final class Sampler implements Runnable {
    private final WatchConfig config;
    private final WatchTiming timing;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();

    Sampler(WatchConfig config) {
      this.config = config;
      timing = new WatchTiming(config.thresholdEnabled, config.usedPercent, config.intervalEnabled,
          config.intervalMinutes, SystemClock.elapsedRealtime());
    }

    private void scheduleNext(long delay) {
      if (inactive() || worker.isShutdown()) return;
      try {
        worker.schedule(this, delay, TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException closed) {
        if (!worker.isShutdown()) throw closed;
      }
    }

    @Override public void run() {
      if (inactive()) return;
      try {
        sample();
      } catch (RuntimeException error) {
        Log.w(TAG, "Memory sampling failed", error);
        scheduleNext(WatchTiming.SAMPLE_MS);
        return;
      }
      scheduleNext(timing.delay(SystemClock.elapsedRealtime()));
    }

    private void sample() {
      if (!notificationsEnabled(WatchService.this, config.mode)) {
        worker.shutdown();
        main.post(this::stopForBlockedNotifications);
        return;
      }
      long now = SystemClock.elapsedRealtime();
      double used = Double.NaN;
      if (config.thresholdEnabled && timing.sampleDue(now)) {
        try {
          MemStats stats = MemStats.read(WatchService.this);
          if (stats.total > 0 && stats.available >= 0 && stats.available <= stats.total) {
            used = 100.0 * (stats.total - stats.available) / stats.total;
          }
        } catch (RuntimeException error) {
          Log.w(TAG, "Memory counters unavailable", error);
        }
      }
      String key = config.mode == Mode.ALERT_ONLY ? LAST_ALERT : LAST_ATTEMPT;
      boolean wasArmed = timing.isArmed();
      int reasons;
      synchronized (ATTEMPT_LOCK) {
        if (inactive()) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        reasons = timing.tick(now, used, prefs.getLong(key, -1L));
        if ((reasons & WatchTiming.THRESHOLD) != 0 && !prefs.edit().putLong(key, now).commit()) {
          reasons &= ~WatchTiming.THRESHOLD;
        }
      }
      if (!wasArmed && timing.isArmed() && !config.intervalEnabled && config.mode == Mode.ALERT_ONLY) {
        main.post(() -> {
          if (sampler == this) getSystemService(NotificationManager.class).cancel(ALERT_ID);
        });
      }
      if (reasons == 0 || inactive()) return;
      String detail;
      if ((reasons & WatchTiming.THRESHOLD) != 0) {
        detail = String.format(Locale.ROOT, "已用运存 %.1f%%，阈值 %d%%", used, config.usedPercent)
            + ((reasons & WatchTiming.INTERVAL) != 0 ? "；定时间隔亦已到达" : "");
      } else {
        detail = "已到 " + config.intervalMinutes + " 分钟间隔";
      }
      if (config.mode == Mode.ALERT_ONLY) {
        boolean low = (reasons & WatchTiming.THRESHOLD) != 0;
        main.post(() -> postAlert(low, detail));
        return;
      }
      if (!LegacyScope.enabled(WatchService.this, LegacyScope.Scope.FILES)) {
        History.add(WatchService.this, "自动回收已跳过", "全机干净文件页开关已关闭");
        return;
      }
      History.add(WatchService.this, "自动回收尝试", detail);
      try {
        if (inactive()) throw new InterruptedException();
        LegacyScope.require(WatchService.this, LegacyScope.Scope.FILES);
        RamController.reclaim(1);
        History.add(WatchService.this, "自动回收完成", "文件页缓存回收命令已完成");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        History.add(WatchService.this, "自动回收结束", "监控停止，等待操作被中断");
      } catch (Exception error) {
        History.add(WatchService.this, "自动回收失败", errorDetail(error));
      }
    }

    // Both notification delivery and service lifecycle changes are serialized on the main thread.
    private void stopForBlockedNotifications() {
      if (sampler != this) return;
      History.add(WatchService.this, "监控已停止", "系统通知或监控通知类别已关闭");
      cancelSampling();
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
    }

    private void postAlert(boolean low, String detail) {
      if (sampler != this) return;
      if (!notificationsEnabled(WatchService.this, config.mode)) {
        stopForBlockedNotifications();
        return;
      }

      Notification alert = new Notification.Builder(WatchService.this, ALERT_CHANNEL)
          .setSmallIcon(android.R.drawable.stat_notify_more)
          .setContentTitle(low ? "可用内存偏低" : "定时内存提醒")
          .setContentText(detail)
          .setContentIntent(openApp(false))
          .setAutoCancel(true)
          .build();
      try {
        getSystemService(NotificationManager.class).notify(ALERT_ID, alert);
      } catch (SecurityException denied) {
        stopForBlockedNotifications();
        return;
      }
      History.add(WatchService.this, low ? "低运存提醒" : "定时内存提醒", detail);
    }

    private boolean inactive() {
      return sampler != this || Thread.currentThread().isInterrupted();
    }
  }
}

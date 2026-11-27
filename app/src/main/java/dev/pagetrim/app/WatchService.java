package dev.pagetrim.app;

import android.Manifest;
import android.app.ActivityManager;
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
  private static final String EXTRA_LOW_MEMORY_ONLY = "low_memory_only";
  private static final String EXTRA_MODE = "mode";
  private static final String ALERT_CHANNEL = "low_memory_alerts";
  private static final int ALERT_ID = 105;
  private static final String CHANNEL = "memory_watch";
  private static final int NOTIFICATION_ID = 104;

  private static final String PREFS = "memory_watch";
  private static final String SESSION = "watch_running_session";
  private static final String DEADLINE = "next_interval_elapsed";
  private static final String ARMED = "threshold_armed";
  private static final String UI_STOP = "stop_from_ui";
  private static volatile long userRequest;
  private static boolean startPending;

  private static final String LAST_ATTEMPT = "last_attempt_elapsed_ms";
  private static final String LAST_ALERT = "last_alert_elapsed_ms";
  private static final Object ATTEMPT_LOCK = new Object();

  private static volatile Mode activeMode = Mode.OFF;
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile Sampler sampler;


  // All user commands originate on the main thread. Workers only compare the request id.
  static long beginUserRequest() { return ++userRequest; }
  static boolean isLatestRequest(long request) { return request == userRequest; }

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
        .putExtra(EXTRA_LOW_MEMORY_ONLY, config.lowMemoryOnly)
        .putExtra(EXTRA_MODE, config.mode.name());
    context.startForegroundService(request);
    startPending = true;
  }

  public static void stop(Context context) {
    beginUserRequest();
    Intent command = new Intent(context, WatchService.class);
    if (startPending || isRunning()) {
      // Deliver in start order, even when the initial foreground promotion is still pending.
      context.startService(command.setAction(ACTION_STOP).putExtra(UI_STOP, true));
    } else {
      clearSession(context);
      context.stopService(command);
    }
  }

  private static void clearSession(Context context) {
    synchronized (ATTEMPT_LOCK) {
      context.getSharedPreferences(SESSION, MODE_PRIVATE).edit().clear().commit();
    }
  }

  private WatchConfig restoredConfig(SharedPreferences session) {
    if (!session.getBoolean("running", false)) return null;
    // Never derive permission to restart from editable preferences or last-used root settings.
    return new WatchConfig(
        session.getBoolean("threshold_enabled", false), session.getInt("used_percent", -1),
        session.getBoolean("interval_enabled", false), session.getInt("interval_minutes", -1),
        Mode.valueOf(session.getString("action_mode", "")), session.getBoolean("low_memory_only", false));
  }

  private void finishSession(int startId) {
    cancelSampling();
    clearSession(this);
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf(startId);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? null : intent.getAction();
    startPending = false;
    if (ACTION_STOP.equals(action)) {
      if (!intent.getBooleanExtra(UI_STOP, false)) beginUserRequest();
      finishSession(startId);
      return START_NOT_STICKY;
    }
    try {
      SharedPreferences session = getSharedPreferences(SESSION, MODE_PRIVATE);
      WatchConfig config;
      if (intent == null) {
        config = restoredConfig(session);
        if (config == null) {
          finishSession(startId);
          return START_NOT_STICKY;
        }
      } else if (ACTION_START.equals(action)) {
        Mode mode = Mode.valueOf(intent.getStringExtra(EXTRA_MODE));
        if (intent.hasExtra(EXTRA_USED)) {
          config = new WatchConfig(intent.getBooleanExtra(EXTRA_THRESHOLD_ENABLED, false),
              intent.getIntExtra(EXTRA_USED, -1), intent.getBooleanExtra(EXTRA_INTERVAL_ENABLED, false),
              intent.getIntExtra(EXTRA_INTERVAL, -1), mode, intent.getBooleanExtra(EXTRA_LOW_MEMORY_ONLY, false));
        } else {
          int threshold = intent.getIntExtra(EXTRA_THRESHOLD, -1);
          if (!validThreshold(threshold)) throw new IllegalArgumentException("阈值无效");
          config = new WatchConfig(true, 100 - threshold, false, 30, mode);
        }
      } else {
        throw new IllegalArgumentException("监控请求无效。");
      }
      if (!config.hasTriggers()) throw new IllegalArgumentException("没有启用的触发条件");
      startForeground(NOTIFICATION_ID, createNotification(config));
      if (!notificationsEnabled(this, config.mode))
        throw new IllegalStateException("系统通知或监控通知类别已关闭。");
      synchronized (ATTEMPT_LOCK) {
        if (sampler == null || !sampler.config.sameSettings(config)) {
          cancelSampling();
          Sampler next = new Sampler(config);
          sampler = next;
          if (intent == null) {
            next.timing.resume(session.getLong(DEADLINE, -1L), session.getBoolean(ARMED, true));
          } else if (!config.write(session.edit()).putBoolean("running", true)
              .putLong(DEADLINE, next.timing.intervalDeadline()).putBoolean(ARMED, true).commit()) {
            throw new IllegalStateException("无法保存本次运行的监控配置。");
          }
          next.scheduleNext(next.timing.delay(SystemClock.elapsedRealtime()));
        }
        activeMode = config.mode;
      }
      if (intent != null) config.save(this);
      else History.add(this, "后台监控已恢复", "Android 重新创建了监控服务，继续本次配置。");
      return START_STICKY;
    } catch (RuntimeException failure) {
      History.add(this, "监控启动失败", errorDetail(failure));
      finishSession(startId);
      return START_NOT_STICKY;
    }
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
            .setContentText(summary(config) + (mode == Mode.ALERT_ONLY ? " · 仅提醒" : config.lowMemoryOnly ? " · 系统低内存时回收文件页" : " · 仅回收文件页缓存"))
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
      boolean guarded = config.lowMemoryOnly && config.mode == Mode.ROOT_RECLAIM;
      boolean checkDue = timing.intervalDue(now) || (config.thresholdEnabled && timing.sampleDue(now));
      boolean pressureAllows = !guarded || !checkDue || systemLowMemory();
      String key = config.mode == Mode.ALERT_ONLY ? LAST_ALERT : LAST_ATTEMPT;
      boolean wasArmed = timing.isArmed();
      int reasons;
      synchronized (ATTEMPT_LOCK) {
        if (inactive()) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long previousDeadline = timing.intervalDeadline();
        reasons = timing.tick(now, used, prefs.getLong(key, -1L), pressureAllows);
        if (previousDeadline != timing.intervalDeadline() || wasArmed != timing.isArmed()) {
          boolean saved = getSharedPreferences(SESSION, MODE_PRIVATE).edit()
              .putLong(DEADLINE, timing.intervalDeadline()).putBoolean(ARMED, timing.isArmed()).commit();
          if (!saved) {
            main.post(() -> {
              if (sampler == this) stopForFailure("无法保存监控计时。");
            });
            return;
          }
        }
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
      if (!pressureAllows) {
        History.add(WatchService.this, "自动回收已跳过", detail + "。未确认系统处于低内存状态。");
        return;
      }
      try {
        synchronized (RamController.class) {
          if (inactive()) throw new InterruptedException();
          LegacyScope.require(WatchService.this, LegacyScope.Scope.FILES);
          if (guarded && !systemLowMemory()) {
            History.add(WatchService.this, "自动回收已跳过", "执行前未确认系统处于低内存状态。");
            return;
          }
          History.add(WatchService.this, "自动回收尝试", detail);
          RamController.reclaim(1);
        }
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
      if (sampler != this || notificationsEnabled(WatchService.this, config.mode)) return;
      stopForFailure("系统通知或监控通知类别已关闭");
    }

    private void stopForFailure(String reason) {
      History.add(WatchService.this, "监控已停止", reason);
      beginUserRequest();
      cancelSampling();
      clearSession(WatchService.this);
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

    private boolean systemLowMemory() {
      ActivityManager manager = getSystemService(ActivityManager.class);
      if (manager == null) return false;
      ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
      try {
        manager.getMemoryInfo(info);
        return info.lowMemory;
      } catch (RuntimeException unavailable) {
        return false;
      }
    }

    private boolean inactive() {
      return sampler != this || Thread.currentThread().isInterrupted();
    }
  }
}

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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WatchService extends Service {
  public enum Mode {
    OFF,
    ALERT_ONLY,
    ROOT_RECLAIM
  }

  private static final String TAG = "PageTrimWatch";
  private static final String ACTION_START = "dev.pagetrim.app.watch.START";
  private static final String ACTION_STOP = "dev.pagetrim.app.watch.STOP";
  private static final String EXTRA_THRESHOLD = "threshold";
  private static final String EXTRA_MODE = "mode";
  private static final String ALERT_CHANNEL = "low_memory_alerts";
  private static final int ALERT_ID = 105;
  private static final String CHANNEL = "memory_watch";
  private static final int NOTIFICATION_ID = 104;
  private static final long SAMPLE_SECONDS = 30;
  private static final long COOLDOWN_MS = TimeUnit.MINUTES.toMillis(30);
  private static final String PREFS = "memory_watch";
  private static final String LAST_ATTEMPT = "last_attempt_elapsed_ms";
  private static final String LAST_ALERT = "last_alert_elapsed_ms";
  private static final Object ATTEMPT_LOCK = new Object();

  private static volatile Mode activeMode = Mode.OFF;
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile Sampler sampler;
  private ScheduledExecutorService executor;

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
    if (mode == null || mode == Mode.OFF) {
      throw new IllegalArgumentException("请选择监控模式。");
    }
    if (!validThreshold(thresholdPercent)) {
      throw new IllegalArgumentException("低内存阈值必须为 5%、8% 或 10%。");
    }
    if (!notificationsEnabled(context, mode)) {
      throw new IllegalStateException("请允许应用发送通知，并开启相应的通知类别。");
    }
    Intent request =
        new Intent(context, WatchService.class)
            .setAction(ACTION_START)
            .putExtra(EXTRA_THRESHOLD, thresholdPercent)
            .putExtra(EXTRA_MODE, mode.name());
    context.startForegroundService(request);
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

    int threshold = intent == null ? -1 : intent.getIntExtra(EXTRA_THRESHOLD, -1);
    String requested = intent == null ? null : intent.getStringExtra(EXTRA_MODE);
    Mode mode = Mode.OFF;
    if (Mode.ALERT_ONLY.name().equals(requested)) mode = Mode.ALERT_ONLY;
    if (Mode.ROOT_RECLAIM.name().equals(requested)) mode = Mode.ROOT_RECLAIM;
    if (!ACTION_START.equals(action) || !validThreshold(threshold) || mode == Mode.OFF) {
      stopSelf(startId);
      return START_NOT_STICKY;
    }

    try {
      startForeground(NOTIFICATION_ID, createNotification(threshold, mode));
      if (!notificationsEnabled(this, mode)) {
        throw new IllegalStateException("系统通知或监控通知类别已关闭。");
      }
      Sampler current = sampler;
      if (current == null || current.threshold != threshold || current.mode != mode) {
        cancelSampling();
        Sampler next = new Sampler(threshold, mode);
        sampler = next;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(next, SAMPLE_SECONDS, SAMPLE_SECONDS, TimeUnit.SECONDS);
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
    sampler = null;
    getSystemService(NotificationManager.class).cancel(ALERT_ID);
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  private Notification createNotification(int threshold, Mode mode) {
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
            .setContentText("阈值 " + threshold + (mode == Mode.ALERT_ONLY ? "% · 仅提醒" : "% · 仅回收文件页缓存"))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "停止", stopAction).build());
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
    }
    return builder.build();
  }

  private PendingIntent openApp() {
    Intent open = new Intent(this, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return PendingIntent.getActivity(
        this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
    private final int threshold;
    private final Mode mode;
    private int lowSamples;
    private boolean armed = true;

    Sampler(int threshold, Mode mode) {
      this.threshold = threshold;
      this.mode = mode;
    }

    @Override
    public void run() {
      if (inactive()) return;
      try {
        sample();
      } catch (RuntimeException error) {
        lowSamples = 0;
        Log.w(TAG, "Memory sampling failed", error);
      }
    }

    private void sample() {
      if (!notificationsEnabled(WatchService.this, mode)) {
        main.post(this::stopForBlockedNotifications);
        return;
      }
      MemStats stats = MemStats.read(WatchService.this);
      if (stats.total <= 0 || stats.available < 0 || stats.available > stats.total) {
        lowSamples = 0;
        return;
      }
      double availablePercent = 100.0 * stats.available / stats.total;
      if (availablePercent >= threshold) {
        lowSamples = 0;
        if (!armed && availablePercent >= threshold + 3) {
          armed = true;
          if (mode == Mode.ALERT_ONLY) main.post(() -> {
            if (sampler == this) getSystemService(NotificationManager.class).cancel(ALERT_ID);
          });
        }
        return;
      }
      if (!armed) return;
      lowSamples = Math.min(3, lowSamples + 1);
      if (lowSamples < 3 || !claimAttempt() || inactive()) return;
      lowSamples = 0;
      armed = false;
      if (mode == Mode.ALERT_ONLY) {
        main.post(() -> postLowMemoryAlert(availablePercent));
        return;
      }
      History.add(
          WatchService.this,
          "自动回收尝试",
          String.format(Locale.ROOT, "可用内存 %.1f%%，阈值 %d%%", availablePercent, threshold));
      try {
        if (inactive()) throw new InterruptedException();
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

    private void postLowMemoryAlert(double percent) {
      if (sampler != this) return;
      if (!notificationsEnabled(WatchService.this, mode)) {
        stopForBlockedNotifications();
        return;
      }
      String detail = String.format(Locale.ROOT, "可用内存 %.1f%%，低于 %d%% 阈值", percent, threshold);
      Notification alert = new Notification.Builder(WatchService.this, ALERT_CHANNEL)
          .setSmallIcon(android.R.drawable.stat_notify_more)
          .setContentTitle("可用内存偏低")
          .setContentText(detail)
          .setContentIntent(openApp())
          .setAutoCancel(true)
          .build();
      try {
        getSystemService(NotificationManager.class).notify(ALERT_ID, alert);
      } catch (SecurityException denied) {
        stopForBlockedNotifications();
        return;
      }
      History.add(WatchService.this, "低运存提醒", detail);
    }

    private boolean claimAttempt() {
      synchronized (ATTEMPT_LOCK) {
        if (inactive()) return false;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long now = SystemClock.elapsedRealtime();
        String key = mode == Mode.ALERT_ONLY ? LAST_ALERT : LAST_ATTEMPT;
        long prior = prefs.getLong(key, -1L);
        // If the elapsed clock reset after reboot, wait at least 30 minutes from boot.
        if (prior >= 0 && now - (now < prior ? 0 : prior) < COOLDOWN_MS) return false;
        return prefs.edit().putLong(key, now).commit();
      }
    }

    private boolean inactive() {
      return sampler != this || Thread.currentThread().isInterrupted();
    }
  }
}

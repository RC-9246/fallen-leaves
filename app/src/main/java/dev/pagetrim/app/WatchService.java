package dev.pagetrim.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WatchService extends Service {
  private static final String TAG = "PageTrimWatch";
  private static final String ACTION_START = "dev.pagetrim.app.watch.START";
  private static final String ACTION_STOP = "dev.pagetrim.app.watch.STOP";
  private static final String EXTRA_THRESHOLD = "threshold";
  private static final String CHANNEL = "memory_watch";
  private static final int NOTIFICATION_ID = 104;
  private static final long SAMPLE_SECONDS = 30;
  private static final long COOLDOWN_MS = TimeUnit.MINUTES.toMillis(30);
  private static final String PREFS = "memory_watch";
  private static final String LAST_ATTEMPT = "last_attempt_elapsed_ms";
  private static final Object ATTEMPT_LOCK = new Object();

  private static volatile boolean running;
  private volatile Sampler sampler;
  private ScheduledExecutorService executor;

  public static boolean isRunning() {
    return running;
  }

  public static void start(Context context, int thresholdPercent) {
    if (!validThreshold(thresholdPercent)) {
      throw new IllegalArgumentException("低内存阈值必须为 5%、8% 或 10%。");
    }
    Intent request =
        new Intent(context, WatchService.class)
            .setAction(ACTION_START)
            .putExtra(EXTRA_THRESHOLD, thresholdPercent);
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
    if (!ACTION_START.equals(action) || !validThreshold(threshold)) {
      stopSelf(startId);
      return START_NOT_STICKY;
    }

    try {
      startForeground(NOTIFICATION_ID, createNotification(threshold));
      Sampler current = sampler;
      if (current == null || current.threshold != threshold) {
        cancelSampling();
        Sampler next = new Sampler(threshold);
        sampler = next;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(next, SAMPLE_SECONDS, SAMPLE_SECONDS, TimeUnit.SECONDS);
      }
      running = true;
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
    running = false;
    sampler = null;
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  private Notification createNotification(int threshold) {
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "内存监控", NotificationManager.IMPORTANCE_LOW));
    Intent stop = new Intent(this, WatchService.class).setAction(ACTION_STOP);
    PendingIntent stopAction =
        PendingIntent.getService(
            this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name) + " 正在监控可用内存")
            .setContentText("阈值 " + threshold + "% · 仅回收文件页缓存")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "停止", stopAction).build());
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
    }
    return builder.build();
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
    private int lowSamples;
    private boolean armed = true;

    Sampler(int threshold) {
      this.threshold = threshold;
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
      MemStats stats = MemStats.read(WatchService.this);
      if (stats.total <= 0 || stats.available < 0 || stats.available > stats.total) {
        lowSamples = 0;
        return;
      }
      double availablePercent = 100.0 * stats.available / stats.total;
      if (availablePercent >= threshold) {
        lowSamples = 0;
        if (availablePercent >= threshold + 3) armed = true;
        return;
      }
      if (!armed) return;
      lowSamples = Math.min(3, lowSamples + 1);
      if (lowSamples < 3 || !claimAttempt() || inactive()) return;
      lowSamples = 0;
      armed = false;
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

    private boolean claimAttempt() {
      synchronized (ATTEMPT_LOCK) {
        if (inactive()) return false;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long now = SystemClock.elapsedRealtime();
        long prior = prefs.getLong(LAST_ATTEMPT, -1L);
        // If the elapsed clock reset after reboot, wait at least 30 minutes from boot.
        if (prior >= 0 && now - (now < prior ? 0 : prior) < COOLDOWN_MS) return false;
        return prefs.edit().putLong(LAST_ATTEMPT, now).commit();
      }
    }

    private boolean inactive() {
      return sampler != this || Thread.currentThread().isInterrupted();
    }
  }
}

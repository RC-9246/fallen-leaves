package dev.pagetrim.app;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReclaimTile extends TileService {
  private static final String LABEL = "回收文件页";
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final AtomicBoolean BUSY = new AtomicBoolean();
  private static WeakReference<ReclaimTile> listeningTile = new WeakReference<>(null);

  @Override
  public void onStartListening() {
    super.onStartListening();
    listeningTile = new WeakReference<>(this);
    refreshListeningTile();
  }

  @Override
  public void onStopListening() {
    stopUpdating();
    super.onStopListening();
  }

  @Override
  public void onDestroy() {
    stopUpdating();
    super.onDestroy();
  }

  private void stopUpdating() {
    if (listeningTile.get() == this) listeningTile.clear();
  }

  @Override
  public void onClick() {
    super.onClick();
    if (BUSY.get()) return;
    Context app = getApplicationContext();
    if (isLocked()) {
      unlockAndRun(() -> startReclaim(app));
    } else {
      startReclaim(app);
    }
  }

  private static void startReclaim(Context app) {
    if (!BUSY.compareAndSet(false, true)) return;
    MAIN.post(ReclaimTile::refreshListeningTile);
    try {
      new Thread(() -> reclaim(app), "pagetrim-tile-reclaim").start();
    } catch (RuntimeException error) {
      BUSY.set(false);
      MAIN.post(ReclaimTile::refreshListeningTile);
      History.add(app, "快捷回收失败", errorDetail(error));
    }
  }

  private static void reclaim(Context app) {
    String before = available(app);
    try {
      RamController.reclaim(1);
      History.add(app, "快捷回收页缓存", "可用内存 " + before + " → " + available(app));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      History.add(app, "快捷回收失败", "等待回收结果时被中断。");
    } catch (Exception error) {
      History.add(app, "快捷回收失败", errorDetail(error));
    } finally {
      BUSY.set(false);
      MAIN.post(ReclaimTile::refreshListeningTile);
    }
  }

  private static void refreshListeningTile() {
    ReclaimTile service = listeningTile.get();
    if (service == null) return;
    Tile tile = service.getQsTile();
    if (tile == null) return;
    boolean busy = BUSY.get();
    String status = busy ? "正在回收…" : "可用内存 " + available(service);
    tile.setLabel(LABEL);
    tile.setContentDescription(LABEL + "，" + status);
    tile.setState(busy ? Tile.STATE_UNAVAILABLE : Tile.STATE_INACTIVE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.setSubtitle(status);
    tile.updateTile();
  }

  private static String available(Context context) {
    try {
      long bytes = MemStats.read(context).available;
      return bytes >= 0 ? MainActivity.fmt(bytes) : "未知";
    } catch (RuntimeException error) {
      return "未知";
    }
  }

  private static String errorDetail(Exception error) {
    String message = error.getMessage();
    if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
    return message.length() > 160 ? message.substring(0, 160) : message;
  }
}

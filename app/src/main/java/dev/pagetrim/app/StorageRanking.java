package dev.pagetrim.app;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class StorageRanking {
  public static final class Entry {
    public final String pkg, label;
    public final boolean available;
    public final long appBytes, dataBytes, cacheBytes, totalBytes;

    private Entry(StorageController.Entry app, StorageStats stats) {
      pkg = app.pkg;
      label = app.label;
      available = stats != null;
      if (available) {
        appBytes = stats.getAppBytes();
        cacheBytes = stats.getCacheBytes();
        // Android includes cache in the data counter.
        dataBytes = Math.max(0L, stats.getDataBytes() - cacheBytes);
        totalBytes = appBytes + stats.getDataBytes();
      } else {
        appBytes = dataBytes = cacheBytes = totalBytes = -1;
      }
    }
  }

  public static boolean hasUsageAccess(Context ctx) {
    AppOpsManager ops = ctx.getSystemService(AppOpsManager.class);
    if (ops == null) return false;
    int mode = ops.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
    return mode == AppOpsManager.MODE_ALLOWED
        || (mode == AppOpsManager.MODE_DEFAULT
            && ctx.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                == PackageManager.PERMISSION_GRANTED);
  }

  public static void openUsageAccessSettings(Activity activity) {
    if (Build.VERSION.SDK_INT >= 29) {
      Intent detail = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
          Uri.fromParts("package", activity.getPackageName(), null));
      try {
        activity.startActivity(detail);
        return;
      } catch (ActivityNotFoundException | SecurityException ignored) {
        // Some manufacturers only expose the list of apps.
      }
    }
    try {
      activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
    } catch (ActivityNotFoundException | SecurityException e) {
      throw new IllegalStateException("请在系统设置中打开「使用情况访问权限」。", e);
    }
  }

  /** Call on a worker thread. Figures describe each app on its installation volume. */
  public static List<Entry> list(Context ctx) {
    StorageStatsManager service = ctx.getSystemService(StorageStatsManager.class);
    PackageManager pm = ctx.getPackageManager();
    UserHandle user = Process.myUserHandle();
    boolean allowed = service != null && hasUsageAccess(ctx);
    List<Entry> result = new ArrayList<>();
    for (StorageController.Entry app : StorageController.list(ctx)) {
      StorageStats stats = null;
      if (allowed) {
        try {
          ApplicationInfo info = pm.getApplicationInfo(app.pkg, 0);
          UUID volume = info.storageUuid != null ? info.storageUuid : StorageManager.UUID_DEFAULT;
          stats = service.queryStatsForPackage(volume, app.pkg, user);
        } catch (IOException | PackageManager.NameNotFoundException | SecurityException ignored) {
          // Access can change or an app's volume can disappear during a scan.
        }
      }
      result.add(new Entry(app, stats));
    }
    sort(result, true);
    return result;
  }

  public static void sort(List<Entry> entries, boolean byCache) {
    Collator labels = Collator.getInstance();
    entries.sort((a, b) -> {
      long left = byCache ? a.cacheBytes : a.totalBytes;
      long right = byCache ? b.cacheBytes : b.totalBytes;
      int order = Long.compare(right, left);
      if (order != 0) return order;
      order = labels.compare(a.label, b.label);
      return order != 0 ? order : a.pkg.compareTo(b.pkg);
    });
  }

  private StorageRanking() {}
}

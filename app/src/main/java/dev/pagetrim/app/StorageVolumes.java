package dev.pagetrim.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import java.io.File;

/** Filesystem capacity as seen by this user, with shared views reported separately. */
final class StorageVolumes {
  private StorageVolumes() {}

  static void show(MainActivity activity) {
    Context app = activity.getApplicationContext();
    activity.work("正在读取存储卷", () -> describe(app), report ->
        new AlertDialog.Builder(activity).setTitle("存储卷与空间管理").setMessage(report)
            .setNegativeButton("返回", null)
            .setPositiveButton("系统空间管理", (dialog, which) -> openManager(activity)).show());
  }

  private static String describe(Context context) {
    StringBuilder report = new StringBuilder("内部数据分区");
    appendSpace(report, context.getFilesDir());
    StorageManager storage = context.getSystemService(StorageManager.class);
    if (storage == null) return report.toString();
    File[] appDirs = Build.VERSION.SDK_INT < 30 ? context.getExternalFilesDirs(null) : new File[0];
    for (StorageVolume volume : storage.getStorageVolumes()) {
      report.append("\n\n").append(volume.getDescription(context));
      if (volume.isPrimary()) report.append("（主共享存储）");
      String state = volume.getState();
      if (!Environment.MEDIA_MOUNTED.equals(state) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
        report.append("\n当前未挂载");
        continue;
      }
      File location = null;
      if (Build.VERSION.SDK_INT >= 30) location = volume.getDirectory();
      else for (File dir : appDirs) {
        if (dir == null) continue;
        StorageVolume candidate = storage.getStorageVolume(dir);
        if (candidate != null && candidate.equals(volume)) {
          location = dir;
          break;
        }
      }
      appendSpace(report, location);
      if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) report.append(" · 只读");
    }
    report.append("\n\n内部数据与主共享存储可能共用同一块磁盘，容量应分别查看。可用值按当前应用身份读取。");
    return report.toString();
  }

  private static void appendSpace(StringBuilder report, File path) {
    if (path != null) {
      try {
        StatFs stats = new StatFs(path.getAbsolutePath());
        long total = stats.getTotalBytes(), available = stats.getAvailableBytes();
        if (total > 0 && available >= 0 && available <= total) {
          report.append("\n文件系统总量 ").append(MainActivity.fmt(total))
              .append(" · 可用 ").append(MainActivity.fmt(available));
          return;
        }
      } catch (IllegalArgumentException | SecurityException unavailable) {
        // Removable storage can disappear while the list is open.
      }
    }
    report.append("\n当前无法读取容量");
  }

  private static void openManager(MainActivity activity) {
    for (String action : new String[] {StorageManager.ACTION_MANAGE_STORAGE, Settings.ACTION_INTERNAL_STORAGE_SETTINGS}) {
      try {
        activity.startActivity(new Intent(action));
        return;
      } catch (ActivityNotFoundException | SecurityException unavailable) {
        // Some devices provide only the system storage settings.
      }
    }
    activity.message("存储管理", "本机未提供可打开的系统空间管理页面。");
  }
}

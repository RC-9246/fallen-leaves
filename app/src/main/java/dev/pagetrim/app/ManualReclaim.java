package dev.pagetrim.app;

import android.content.Context;
import android.content.SharedPreferences;

final class ManualReclaim {
  static final int FILES = 1;
  static final int OBJECTS = 2;
  static final int BOTH = FILES | OBJECTS;
  static final String TILE_CONFIRM = "dev.pagetrim.app.CONFIRM_FILE_PAGES";

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("manual_reclaim", Context.MODE_PRIVATE);
  }

  static String label(int mask) {
    switch (mask) {
      case FILES: return "文件页缓存";
      case OBJECTS: return "可回收内核对象";
      case BOTH: return "文件页和内核对象";
      default: throw new IllegalArgumentException("无效的回收范围");
    }
  }

  static String caution(int mask) {
    if (mask == FILES) return "干净文件页可从存储重新读取，下次打开应用可能变慢。";
    if (mask == OBJECTS) return "回收目录项和 inode 可能使文件访问短时停顿，系统随后会重建这些对象。";
    return "同时回收两类缓存可能造成短时停顿。下次打开应用或读取文件时，系统需要重新读取和重建缓存。";
  }

  static int selection(Context context) {
    int mask = prefs(context).getInt("regions", FILES);
    return mask >= FILES && mask <= BOTH ? mask : FILES;
  }

  static void setSelection(Context context, int mask) {
    if (mask < FILES || mask > BOTH) throw new IllegalArgumentException("请至少选择一项");
    prefs(context).edit().putInt("regions", mask).apply();
  }

  static boolean confirm(Context context) {
    return prefs(context).getBoolean("confirm", true);
  }

  static void setConfirm(Context context, boolean confirm) {
    prefs(context).edit().putBoolean("confirm", confirm).apply();
  }

  private ManualReclaim() {}
}

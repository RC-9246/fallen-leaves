package dev.pagetrim.app;

import android.content.Context;

/** Availability of the original cleanup operations across the app, tile and monitor. */
final class LegacyScope {
  enum Scope {
    FILES("全机干净文件页"), OBJECTS("可回收内核对象"), TRIM("通知缓存进程自行回收"), DIAGNOSTICS("删除旧诊断文件");

    final String label;
    Scope(String label) { this.label = label; }
  }

  private LegacyScope() {}

  static boolean enabled(Context context, Scope scope) {
    return context.getSharedPreferences("legacy_scopes", Context.MODE_PRIVATE)
        .getBoolean(scope.name(), true);
  }

  static void setEnabled(Context context, Scope scope, boolean enabled) {
    context.getSharedPreferences("legacy_scopes", Context.MODE_PRIVATE)
        .edit().putBoolean(scope.name(), enabled).apply();
  }

  static void require(Context context, Scope scope) {
    if (!enabled(context, scope)) {
      throw new IllegalStateException("「" + scope.label + "」已关闭，可在运存页的扩展回收范围中开启。");
    }
  }

  static void requireMemory(Context context, int mask) {
    if ((mask & ManualReclaim.FILES) != 0) require(context, Scope.FILES);
    if ((mask & ManualReclaim.OBJECTS) != 0) require(context, Scope.OBJECTS);
  }
}

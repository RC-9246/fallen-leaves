package dev.pagetrim.app;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class AppReview {
  static final String PREFS = "app_review", REVIEW = "usage", END = "end_background";
  static final long DAY = TimeUnit.DAYS.toMillis(1);

  static boolean enabled(Context c, String feature) {
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(feature, false);
  }

  static void requireReview(Context c) {
    if (!enabled(c, REVIEW)) throw new IllegalStateException("使用与存储对照已关闭。");
    if (!StorageRanking.hasUsageAccess(c)) throw new IllegalStateException("请在系统设置中允许使用情况访问。");
  }

  static final class Record {
    final StorageRanking.Entry storage;
    final long lastUsed;
    Record(StorageRanking.Entry storage, long lastUsed) {
      this.storage = storage;
      this.lastUsed = lastUsed;
    }
  }

  static List<Record> review(Context c) {
    requireReview(c);
    long now = System.currentTimeMillis();
    long start = now - 90 * DAY;
    UsageStatsManager manager = c.getSystemService(UsageStatsManager.class);
    if (manager == null) throw new IllegalStateException("系统未提供使用情况服务。");
    Map<String, UsageStats> stats = manager.queryAndAggregateUsageStats(start, now);
    if (stats == null) throw new IllegalStateException("当前用户的使用记录暂不可读。");
    List<Record> rows = new ArrayList<>();
    for (StorageRanking.Entry app : StorageRanking.list(c)) {
      UsageStats usage = stats.get(app.pkg);
      long timestamp = usage == null ? 0 : usage.getLastTimeUsed();
      rows.add(new Record(app, timestamp >= start && timestamp <= now ? timestamp : 0));
    }
    requireReview(c);
    return rows;
  }

  static boolean matches(long lastUsed, long now, int filter) {
    if (filter == 0) return true;
    if (filter == -1) return lastUsed == 0;
    return lastUsed > 0 && lastUsed <= now - filter * DAY;
  }

  static final class Target {
    final String pkg, label;
    final int uid;
    final long installed, updated;
    Target(PackageInfo p, String label) {
      pkg = p.packageName;
      this.label = label;
      uid = p.applicationInfo.uid;
      installed = p.firstInstallTime;
      updated = p.lastUpdateTime;
    }
    boolean same(PackageInfo p) {
      return pkg.equals(p.packageName) && uid == p.applicationInfo.uid
          && installed == p.firstInstallTime && updated == p.lastUpdateTime;
    }
  }

  // Critical user-selected components can belong to otherwise ordinary third-party apps.
  private static Set<String> protectedApps(Context c) {
    Set<String> packages = new HashSet<>();
    ResolveInfo home = c.getPackageManager().resolveActivity(
        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
    if (home != null && home.activityInfo != null) packages.add(home.activityInfo.packageName);
    String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
    ComponentName input = ime == null ? null : ComponentName.unflattenFromString(ime);
    if (input != null) packages.add(input.getPackageName());
    DevicePolicyManager policy = c.getSystemService(DevicePolicyManager.class);
    List<ComponentName> admins = policy == null ? null : policy.getActiveAdmins();
    if (admins != null) for (ComponentName admin : admins) packages.add(admin.getPackageName());
    AccessibilityManager access = c.getSystemService(AccessibilityManager.class);
    if (access != null) {
      for (AccessibilityServiceInfo service : access.getEnabledAccessibilityServiceList(
          AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
        ResolveInfo r = service.getResolveInfo();
        if (r != null && r.serviceInfo != null) packages.add(r.serviceInfo.packageName);
      }
    }
    return packages;
  }

  private static boolean singleOwner(Context c, PackageInfo p) {
    String[] owners = c.getPackageManager().getPackagesForUid(p.applicationInfo.uid);
    return owners != null && owners.length == 1 && p.packageName.equals(owners[0]);
  }

  private static void requireEnd(Context c) {
    if (Build.VERSION.SDK_INT >= 34) throw new IllegalStateException("Android 14 起仅允许应用结束自己的后台进程。");
    if (!enabled(c, END)) throw new IllegalStateException("结束所选应用后台进程已关闭。");
  }

  static List<Target> targets(Context c) {
    requireEnd(c);
    Set<String> protectedPackages = protectedApps(c);
    List<Target> rows = new ArrayList<>();
    for (StorageController.Entry e : StorageController.list(c)) {
      if (protectedPackages.contains(e.pkg)) continue;
      try {
        PackageInfo p = c.getPackageManager().getPackageInfo(e.pkg, 0);
        if (singleOwner(c, p)) rows.add(new Target(p, e.label));
      } catch (PackageManager.NameNotFoundException ignored) {
        // Uninstalled since enumeration.
      }
    }
    return rows;
  }

  static String requestEnd(Context c, Target target) {
    requireEnd(c);
    PackageInfo current = StorageController.checkedPackage(c, target.pkg);
    if (!target.same(current) || !singleOwner(c, current) || protectedApps(c).contains(target.pkg)) {
      throw new IllegalStateException("目标身份或保护状态已改变，请重新选择。");
    }
    ActivityManager manager = c.getSystemService(ActivityManager.class);
    if (manager == null) throw new IllegalStateException("系统未提供进程管理服务。");
    manager.killBackgroundProcesses(current.packageName);
    String result = target.label + " · " + target.pkg + "\n已向 Android 提交请求。系统未返回进程数或释放字节数。";
    History.add(c, "请求结束所选应用后台进程", result);
    return result;
  }

  private AppReview() {}
}

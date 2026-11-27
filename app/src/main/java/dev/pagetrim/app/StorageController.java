package dev.pagetrim.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.VpnService;
import android.provider.Settings;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StorageController {
  public static final class Entry {
    public final String pkg, label;

    private Entry(String pkg, String label) {
      this.pkg = pkg;
      this.label = label;
    }
  }

  private static boolean eligible(Context c, PackageInfo p, Set<String> vpn) {
    ApplicationInfo a = p.applicationInfo;
    if (a == null
        || !a.enabled
        || c.getPackageName().equals(p.packageName)
        || vpn.contains(p.packageName)
        || (a.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
      return false;
    if (p.services != null)
      for (ServiceInfo s : p.services)
        if ("android.permission.BIND_VPN_SERVICE".equals(s.permission)) return false;
    return true;
  }

  public static List<Entry> list(Context c) {
    PackageManager pm = c.getPackageManager();
    Set<String> vpn = new HashSet<>();
    for (ResolveInfo r :
        pm.queryIntentServices(
            new Intent(VpnService.SERVICE_INTERFACE), PackageManager.MATCH_DISABLED_COMPONENTS))
      if (r.serviceInfo != null) vpn.add(r.serviceInfo.packageName);
    List<Entry> a = new ArrayList<>();
    for (PackageInfo p :
        pm.getInstalledPackages(
            PackageManager.GET_SERVICES | PackageManager.MATCH_DISABLED_COMPONENTS))
      if (eligible(c, p, vpn))
        a.add(new Entry(p.packageName, p.applicationInfo.loadLabel(pm).toString()));
    Collator order = Collator.getInstance();
    a.sort(
        (x, y) -> {
          int diff = order.compare(x.label, y.label);
          return diff != 0 ? diff : x.pkg.compareTo(y.pkg);
        });
    return a;
  }

  static PackageInfo checkedPackage(Context a, String pkg) {
    try {
      PackageManager pm = a.getPackageManager();
      PackageInfo info =
          pm.getPackageInfo(
              pkg, PackageManager.GET_SERVICES | PackageManager.MATCH_DISABLED_COMPONENTS);
      boolean publishesVpn =
          !pm.queryIntentServices(
                  new Intent(VpnService.SERVICE_INTERFACE).setPackage(pkg),
                  PackageManager.MATCH_DISABLED_COMPONENTS)
              .isEmpty();
      if (!publishesVpn && eligible(a, info, java.util.Collections.emptySet())) {
        return info;
      }
    } catch (PackageManager.NameNotFoundException ignored) {
    }
    throw new IllegalStateException("应用状态已改变，请刷新列表。");
  }

  static Intent settingsIntent(Context context, String pkg) {
    checkedPackage(context, pkg);
    Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null));
    if (systemPage(context, details)) return details;
    throw new IllegalStateException("此设备未提供可打开应用详情的系统设置页面。");
  }

  static boolean systemPage(Context context, Intent intent) {
    PackageManager pm = context.getPackageManager();
    int flags = PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY;
    ResolveInfo defaultHandler = pm.resolveActivity(intent, flags);
    ComponentName chosen = null;
    for (ResolveInfo handler : pm.queryIntentActivities(intent, flags)) {
      if (handler.activityInfo == null || !handler.activityInfo.exported) continue;
      ComponentName candidate = new ComponentName(handler.activityInfo.packageName, handler.activityInfo.name);
      if (chosen == null) chosen = candidate;
      if (defaultHandler != null && defaultHandler.activityInfo != null
          && candidate.getPackageName().equals(defaultHandler.activityInfo.packageName)
          && candidate.getClassName().equals(defaultHandler.activityInfo.name)) {
        chosen = candidate;
        break;
      }
    }
    if (chosen == null) return false;
    intent.setComponent(chosen);
    return true;
  }

  public static void openSettings(Activity a, String pkg) {
    a.startActivity(settingsIntent(a, pkg));
  }

  private StorageController() {}
}

package dev.pagetrim.app;

import android.app.Activity;
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

  public static void openSettings(Activity a, String pkg) {
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
        a.startActivity(
            new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)));
        return;
      }
    } catch (PackageManager.NameNotFoundException ignored) {
    }
    throw new IllegalStateException("应用状态已改变，请刷新列表。");
  }

  private StorageController() {}
}

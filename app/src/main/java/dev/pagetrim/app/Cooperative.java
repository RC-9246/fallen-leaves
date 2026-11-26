package dev.pagetrim.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Explicit, user-selected operations performed by the app that owns the resources. */
final class Cooperative {
  static final String ACTION = "dev.pagetrim.app.action.MANAGE_MEMORY_V1";
  static final String META = "dev.pagetrim.app.MEMORY_SCOPES_V1";
  static final String SCOPE = "scope", NONCE = "request_id";

  enum Scope {
    MANAGED_GC,
    NATIVE_ALLOCATOR,
    OBJECT_POOL,
    IMAGE_CACHE,
    GPU_RESOURCES,
    SQLITE_CACHE,
    DISCARDABLE_PRIVATE,
    ACTIVITY_INSTANCE,
    BROWSER_TAB,
    WEBVIEW_RENDERER,
    PRIVATE_WORKER_EXIT,
    SAVE_SESSION,
    RESTORE_SESSION
  }

  static final class Owner {
    final ComponentName component;
    final String title, signer;

    Owner(ComponentName component, String title, String signer) {
      this.component = component;
      this.title = title;
      this.signer = signer;
    }
  }

  private Cooperative() {}

  @SuppressWarnings("deprecation")
  static String signer(Context context, String pkg) throws Exception {
    PackageManager pm = context.getPackageManager();
    Signature[] signatures;
    if (Build.VERSION.SDK_INT >= 28) {
      PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
      if (pi.signingInfo == null) throw new IOException("无法确认应用签名。");
      signatures = pi.signingInfo.getApkContentsSigners();
    } else {
      signatures = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
    }
    if (signatures == null || signatures.length == 0) throw new IOException("缺少应用签名。");
    List<String> values = new ArrayList<>();
    for (Signature sig : signatures) {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray());
      values.add(android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP));
    }
    Collections.sort(values);
    return android.text.TextUtils.join(".", values);
  }

  static List<Owner> owners(Context context, Scope scope) throws Exception {
    List<Owner> owners = new ArrayList<>();
    if (scope == Scope.MANAGED_GC || scope == Scope.NATIVE_ALLOCATOR || scope == Scope.SQLITE_CACHE)
      owners.add(new Owner(null, "落叶（本进程）", ""));
    Set<String> eligible = new HashSet<>();
    for (StorageController.Entry app : StorageController.list(context)) eligible.add(app.pkg);
    PackageManager pm = context.getPackageManager();
    for (ResolveInfo r :
        pm.queryIntentActivities(
            new Intent(ACTION).addCategory(Intent.CATEGORY_DEFAULT),
            PackageManager.GET_META_DATA)) {
      ActivityInfo a = r.activityInfo;
      if (a == null
          || !a.exported
          || !a.enabled
          || a.metaData == null
          || !eligible.contains(a.packageName)
          || !ProcessTrim.sameApp(pm, a.packageName, a.applicationInfo.uid)
          || (a.applicationInfo.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) continue;
      Object scopes = a.metaData.get(META);
      if (!(scopes instanceof String)
          || !Arrays.asList(((String) scopes).split("[,\\s]+")).contains(scope.name())) continue;
      owners.add(
          new Owner(
              new ComponentName(a.packageName, a.name),
              a.applicationInfo.loadLabel(pm) + " · " + a.packageName,
              signer(context, a.packageName)));
    }
    return owners;
  }

  static Intent request(Context context, Owner owner, Scope scope, String nonce) throws Exception {
    for (Owner live : owners(context, scope)) {
      if (live.component != null
          && live.component.equals(owner.component)
          && live.signer.equals(owner.signer))
        return new Intent(ACTION)
            .setComponent(live.component)
            .putExtra(SCOPE, scope.name())
            .putExtra(NONCE, nonce);
    }
    throw new IOException("协作应用或签名已变化，请重新选择。");
  }

  static String runLocal(Scope scope) throws IOException {
    switch (scope) {
      case MANAGED_GC:
        Runtime.getRuntime().gc();
        return "落叶已请求本进程 ART 垃圾回收。空闲堆空间可以留给后续对象复用。";
      case SQLITE_CACHE:
        return "落叶进程内 SQLite 返回已释放 " + SQLiteDatabase.releaseMemory() + " 字节。系统物理内存的变化还受其它分配影响。";
      case NATIVE_ALLOCATOR:
        if (Build.VERSION.SDK_INT < 28) throw new IOException("本机 Android 版本未提供 M_PURGE 接口。");
        try {
          System.loadLibrary("fallenallocator");
          int accepted = purgeAllocator();
          if (accepted <= 0) throw new IOException("分配器未接受归还空闲页请求。");
        } catch (UnsatisfiedLinkError error) {
          throw new IOException("原生分配器接口无法加载。", error);
        }
        return "落叶已请求本进程原生分配器归还可释放页。分配器没有返回独立的释放字节数。";
      default:
        throw new IOException("此操作需要拥有相应资源的应用执行。");
    }
  }

  private static native int purgeAllocator();
}

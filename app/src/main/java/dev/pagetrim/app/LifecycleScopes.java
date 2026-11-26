package dev.pagetrim.app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

/** Explicit standby changes keep a recovery record even when the feature is switched off. */
final class LifecycleScopes {
  private LifecycleScopes() {}

  private static SharedPreferences journal(Context c) {
    return c.getSharedPreferences("standby_rollback_v1", Context.MODE_PRIVATE);
  }

  static List<String> pending(Context c) {
    return new ArrayList<>(journal(c).getAll().keySet());
  }

  private static String argument(String pkg) throws IOException {
    if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) throw new IOException("包名无效。");
    return " --user " + (Process.myUid() / 100000) + " " + pkg;
  }

  private static int bucket(String args) throws Exception {
    String read = Root.run("am get-standby-bucket" + args, 10);
    if (!read.matches("[0-9]{1,2}")) throw new IOException("无法核对待机分组：" + read);
    return Integer.parseInt(read);
  }

  private static String identity(Context c, String pkg) throws Exception {
    PackageInfo p = c.getPackageManager().getPackageInfo(pkg, 0);
    return p.applicationInfo.uid + "/" + p.firstInstallTime + "/" + Cooperative.signer(c, pkg);
  }

  private static boolean forget(Context c, String pkg) {
    return journal(c).edit().remove(pkg).commit();
  }

  static synchronized String standby(Context c, TargetReclaim.App target) throws Exception {
    TargetReclaim.validate(c, target);
    String pkg = target.pkg, args = argument(pkg);
    if (journal(c).contains(pkg)) throw new IOException("此应用已有待恢复的更改。请先使用「恢复待机分组」。");
    int before = bucket(args);
    if (before >= 40) return "原分组为 " + before + "，已是罕用或更严格的分组，无需调整。";
    if (before != 10 && before != 20 && before != 30)
      throw new IOException("此系统分组不适用于手动更改：" + before);
    JSONObject saved = new JSONObject().put("identity", identity(c, pkg)).put("before", before);
    if (!journal(c).edit().putString(pkg, saved.toString()).commit())
      throw new IOException("无法保存原待机分组。");
    Root.run(TargetReclaim.validatedPrelude(target) + "\nam set-standby-bucket" + args + " 40", 12);
    int after = bucket(args);
    if (after != 40) throw new IOException("原分组 " + before + "，当前 " + after + "。原始记录已保留，可在恢复入口核对。");
    return pkg + " 的待机分组由 " + before + " 改为罕用（40）。这会限制部分后台工作，系统可再次调整。";
  }

  static synchronized String restore(Context c, String pkg) throws Exception {
    String raw = journal(c).getString(pkg, null);
    if (raw == null) throw new IOException("没有待恢复的记录。");
    String args = argument(pkg);
    JSONObject saved = new JSONObject(raw);
    if (!saved.getString("identity").equals(identity(c, pkg)))
      throw new IOException("应用安装身份已变化，未写入分组。");
    int before = saved.getInt("before"), current = bucket(args);
    if (current == 40) {
      Root.run("am set-standby-bucket" + args + " " + before, 12);
      if (bucket(args) != before) throw new IOException("系统没有恢复到原分组，已保留记录。");
    }
    if (!forget(c, pkg)) throw new IOException("状态已核对，恢复记录暂未清除。");
    return current == 40 || current == before
        ? pkg + " 已恢复到分组 " + before + "。"
        : "系统已把该应用调整到分组 " + current + "，已沿用系统的新值并结束本次恢复记录。";
  }

  private static final String SUSPEND_MARKER = "dev.pagetrim.app.suspension_id";

  private static SharedPreferences suspensionJournal(Context c) {
    return c.getSharedPreferences("suspension_rollback_v1", Context.MODE_PRIVATE);
  }

  static synchronized List<String> suspended(Context c) {
    List<String> packages = new ArrayList<>(suspensionJournal(c).getAll().keySet());
    Collections.sort(packages);
    return packages;
  }

  private static void requireSuspensionSupport(Context c) throws IOException {
    if (Build.VERSION.SDK_INT < 30 || c.getSystemService(LauncherApps.class) == null)
      throw new IOException("独立恢复暂停需要 Android 11 或更新系统提供多来源暂停和启动器标记。");
  }

  private static String suspensionMarker(Context c, String pkg) {
    Bundle extras =
        c.getSystemService(LauncherApps.class)
            .getSuspendedPackageLauncherExtras(pkg, Process.myUserHandle());
    return extras == null ? null : extras.getString(SUSPEND_MARKER);
  }

  private static void checkEssentialRole(Context c, String pkg) throws IOException {
    ResolveInfo home =
        c.getPackageManager()
            .resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
    if (home != null && home.activityInfo != null && pkg.equals(home.activityInfo.packageName))
      throw new IOException("当前桌面需要保持可用。");
    DevicePolicyManager policy = c.getSystemService(DevicePolicyManager.class);
    List<ComponentName> admins = policy == null ? null : policy.getActiveAdmins();
    if (admins != null)
      for (ComponentName admin : admins)
        if (pkg.equals(admin.getPackageName())) throw new IOException("此包持有已启用的设备管理服务。");
    for (String setting :
        new String[] {
          Settings.Secure.DEFAULT_INPUT_METHOD,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
          "enabled_notification_listeners"
        }) {
      String components = Settings.Secure.getString(c.getContentResolver(), setting);
      if (components == null) continue;
      for (String item : components.split(":")) {
        ComponentName component = ComponentName.unflattenFromString(item);
        if (component != null && pkg.equals(component.getPackageName()))
          throw new IOException("此包提供正在使用的输入法、辅助功能或通知服务。");
      }
    }
  }

  private static void closeSuspensionRecord(Context c, String pkg) throws IOException {
    if (!suspensionJournal(c).edit().remove(pkg).commit())
      throw new IOException("系统状态已核对，恢复记录暂未清除。");
  }

  static synchronized String suspend(Context c, TargetReclaim.App target) throws Exception {
    requireSuspensionSupport(c);
    TargetReclaim.validate(c, target);
    String pkg = target.pkg;
    String args = argument(pkg);
    if (suspensionJournal(c).contains(pkg)) throw new IOException("此应用已有暂停记录，请先从恢复入口核对。");
    checkEssentialRole(c, pkg);
    PackageManager pm = c.getPackageManager();
    if (pm.isPackageSuspended(pkg)) throw new IOException("此应用已有系统或其他工具设置的暂停。");
    // Verify the read API is accessible before changing policy. The system's launch extras
    // identify this particular suspension, including across process death and reboot.
    suspensionMarker(c, pkg);
    String token = UUID.randomUUID().toString();
    String identity = identity(c, pkg);
    String saved = new JSONObject().put("identity", identity).put("marker", token).toString();
    if (!suspensionJournal(c).edit().putString(pkg, saved).commit())
      throw new IOException("无法保存暂停恢复记录。");
    if (pm.isPackageSuspended(pkg) || !identity.equals(identity(c, pkg))) {
      closeSuspensionRecord(c, pkg);
      throw new IOException("应用身份或已有暂停状态发生变化，请重新选取。");
    }
    int user = Process.myUid() / 100000;
    // The system package tool uses a shared root identity. Also check the current user's
    // package record within the same shell immediately before submitting the request.
    String stateCheck =
        "dumpsys package "
            + pkg
            + " | awk '$1==\"User\" && $2==\""
            + user
            + ":\" {for(i=3;i<=NF;i++) if($i==\"suspended=false\" || $i==\"suspended=true\") "
            + "{n++; if($i==\"suspended=false\") active++}} "
            + "END {exit !(n==1 && active==1)}' || { echo '无法确认应用仍未暂停。'; exit 1; }\n";
    String reply =
        Root.run(
            TargetReclaim.validatedPrelude(target)
                + stateCheck
                + "cached_uid && same_members || stale\ncmd package suspend --les "
                + SUSPEND_MARKER
                + " "
                + token
                + args,
            20);
    if (!reply.endsWith("Package " + pkg + " new suspended state: true")
        || !pm.isPackageSuspended(pkg)
        || !token.equals(suspensionMarker(c, pkg)))
      throw new IOException("系统未确认本次暂停及来源标记。已保留恢复记录，请核对。");
    return pkg + " 已由系统暂停。打开应用和通知会受限，暂停会跨重启保留。可在「解除应用挂起」解除。";
  }

  static synchronized String unsuspend(Context c, String pkg) throws Exception {
    requireSuspensionSupport(c);
    String args = argument(pkg);
    String raw = suspensionJournal(c).getString(pkg, null);
    if (raw == null) throw new IOException("没有此应用的暂停记录。");
    JSONObject saved = new JSONObject(raw);
    String current;
    try {
      current = identity(c, pkg);
    } catch (PackageManager.NameNotFoundException removed) {
      closeSuspensionRecord(c, pkg);
      return "原应用已卸载，已结束其暂停记录。";
    }
    if (!saved.getString("identity").equals(current)) {
      closeSuspensionRecord(c, pkg);
      return "原安装身份已变化，已结束旧记录并保留当前应用状态。";
    }
    PackageManager pm = c.getPackageManager();
    if (!pm.isPackageSuspended(pkg)) {
      closeSuspensionRecord(c, pkg);
      return pkg + " 当前可用，恢复记录已核销。";
    }
    String token = saved.getString("marker");
    if (!token.equals(suspensionMarker(c, pkg)))
      throw new IOException("当前暂停没有落叶保存的来源标记。已保留现有策略，请在原管理工具核对。");
    // Android 11+ removes only the calling suspender's entry, preserving other owners.
    String reply = Root.run("cmd package unsuspend" + args, 12);
    boolean stillSuspended = pm.isPackageSuspended(pkg);
    if (!reply.endsWith("Package " + pkg + " new suspended state: " + stillSuspended)
        || token.equals(suspensionMarker(c, pkg)))
      throw new IOException("系统没有确认移除落叶的暂停标记。已保留恢复记录。");
    closeSuspensionRecord(c, pkg);
    return stillSuspended ? "已移除落叶的暂停。其他管理工具仍在暂停 " + pkg + "。" : pkg + " 已解除暂停，可以重新打开。";
  }

  static String freeze(Context c, TargetReclaim.App target) throws Exception {
    TargetReclaim.validate(c, target);
    String help = Root.run("am help", 12);
    if (!help.matches("(?s).*\\n\\s*freeze \\[[^\\n]+.*")
        || !help.matches("(?s).*\\n\\s*unfreeze \\[[^\\n]+.*"))
      throw new IOException(
          "当前 Android 没有公开活动管理器的 freeze / unfreeze 命令。内核 freezer 节点本身不足以协调 Binder 和前后台切换。");
    if (target.processCount() != 1) throw new IOException("仅支持进程归属明确的单进程缓存应用。");
    // No sticky flag. AMS retains lifecycle responsibility on app use. Only submit an
    // unfreeze request for the same process identity if it survives the observation.
    return Root.run(
        TargetReclaim.validatedPrelude(target)
            + "\n"
            + "p=$pids\n"
            + "same_members && cached_uid && process \"$p\" check || stale\n"
            + "m=$(am freeze \"$p\" 2>&1) || { echo \"$m\"; exit 1; }\n"
            + "case \"$m\" in *[Ee]rror*|*Exception*) echo \"$m\"; exit 1;; esac\n"
            + "sleep 2\n"
            + "if process \"$p\" alive; then m=$(am unfreeze \"$p\" 2>&1) || { echo \"$m\"; exit 1;"
            + " }; case \"$m\" in *[Ee]rror*|*Exception*) echo \"$m\"; exit 1;; esac; echo '已向"
            + " Android 提交短暂冻结与解除请求。系统决定实际冻结时长，未计为释放运存。'; else echo '原进程已经退出，短暂冻结不再适用。'; fi",
        20);
  }
}

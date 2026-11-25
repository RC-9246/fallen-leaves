package dev.pagetrim.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Requests an optional memory-trim callback from one explicitly selected cached process. */
public final class ProcessTrim {
  private ProcessTrim() {}

  private static final Pattern PACKAGE =
      Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");
  private static final Pattern PROCESS = Pattern.compile("[A-Za-z0-9_.]+(?::[A-Za-z0-9_.]+)?");
  // Android allocates this range of Linux UIDs to each user/profile.
  private static final int PER_USER_RANGE = 100000;
  // /proc/<pid>/stat fields after the final ") " start at field 3; starttime is field 22.
  private static final String READ_START =
      "IFS= read -r st < \"/proc/$pid/stat\" || return 1; "
          + "set -- ${st##*) }; [ \"$#\" -ge 20 ] || return 1; start=${20};";
  private static final String IDENTITY =
      "read_start() { "
          + READ_START
          + " }; "
          + "is_cached() { IFS= read -r adj < \"/proc/$pid/oom_score_adj\" "
          + "&& [ \"$adj\" -ge 900 ] && [ \"$adj\" -le 1000 ]; }; ";

  public static final class Entry {
    public final String label, pkg, process;

    /** RSS in bytes. RSS can include shared pages across processes. */
    public final long rss;

    private final int pid, uid;
    private final long start;

    private Entry(
        String label, String pkg, String process, long rss, int pid, int uid, long start) {
      this.label = label;
      this.pkg = pkg;
      this.process = process;
      this.rss = rss;
      this.pid = pid;
      this.uid = uid;
      this.start = start;
    }
  }

  public static List<Entry> list(Context ctx) throws Exception {
    Map<String, StorageController.Entry> allowed = new HashMap<>();
    for (StorageController.Entry app : StorageController.list(ctx)) {
      if (PACKAGE.matcher(app.pkg).matches() && !ctx.getPackageName().equals(app.pkg)) {
        allowed.put(app.pkg, app);
      }
    }
    List<Entry> result = new ArrayList<>();
    if (allowed.isEmpty()) return result;
    int user = Process.myUid() / PER_USER_RANGE;
    String command =
        "set -f; "
            + IDENTITY
            + "lines=$(ps -A -o PID,UID,RSS,NAME) || exit 1; "
            + "printf '%s\\n' \"$lines\" | { n=0; while read -r pid uid rss name; do "
            + "case \"$uid\" in ''|*[!0-9]*) continue;; esac; "
            + "[ \"$((uid / "
            + PER_USER_RANGE
            + "))\" -eq "
            + user
            + " ] || continue; "
            + "is_cached 2>/dev/null && read_start 2>/dev/null || continue; "
            + "printf '%s %s %s %s %s\\n' \"$pid\" \"$uid\" \"$rss\" \"$start\" \"$name\"; "
            + "n=$((n + 1)); [ \"$n\" -lt 512 ] || break; done; :; }";
    String output = Root.run(command, 15);
    PackageManager pm = ctx.getPackageManager();
    for (String line : output.split("\n")) {
      String[] fields = line.trim().split("\\s+");
      if (fields.length != 5 || !PROCESS.matcher(fields[4]).matches()) continue;
      String process = fields[4];
      String pkg = process.split(":", 2)[0];
      StorageController.Entry app = allowed.get(pkg);
      if (app == null) continue;
      try {
        int pid = Integer.parseInt(fields[0]);
        int uid = Integer.parseInt(fields[1]);
        long kib = Long.parseLong(fields[2]);
        long start = Long.parseLong(fields[3]);
        if (pid <= 0 || start <= 0 || kib < 0 || kib > Long.MAX_VALUE / 1024) continue;
        if (!sameApp(pm, pkg, uid)) continue;
        result.add(new Entry(app.label, pkg, process, kib * 1024, pid, uid, start));
      } catch (NumberFormatException | PackageManager.NameNotFoundException ignored) {
        // Skip a transient row or an application uninstalled during the sample.
      }
    }
    result.sort((a, b) -> Long.compare(b.rss, a.rss));
    return result;
  }

  private static boolean sameApp(PackageManager pm, String pkg, int uid)
      throws PackageManager.NameNotFoundException {
    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
    if (info.uid != uid
        || !UserHandle.getUserHandleForUid(uid).equals(Process.myUserHandle())
        || (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
            != 0) {
      return false;
    }
    String[] uidPackages = pm.getPackagesForUid(uid);
    return uidPackages != null && uidPackages.length == 1 && pkg.equals(uidPackages[0]);
  }

  public static String trim(Context ctx, Entry entry) throws Exception {
    if (entry == null
        || !PACKAGE.matcher(entry.pkg).matches()
        || !PROCESS.matcher(entry.process).matches()
        || !(entry.process.equals(entry.pkg) || entry.process.startsWith(entry.pkg + ":"))) {
      throw new IllegalArgumentException("无效的进程名称");
    }
    boolean allowed = false;
    for (StorageController.Entry app : StorageController.list(ctx)) {
      if (entry.pkg.equals(app.pkg)) {
        allowed = true;
        break;
      }
    }
    if (!allowed
        || ctx.getPackageName().equals(entry.pkg)
        || !sameApp(ctx.getPackageManager(), entry.pkg, entry.uid)) {
      throw new IOException("应用状态已变化，请刷新列表");
    }
    int user = entry.uid / PER_USER_RANGE;
    String cmd =
        "set -f; "
            + IDENTITY
            + "pid="
            + entry.pid
            + "; "
            + "stale() { echo '进程已退出或已离开缓存状态，请刷新列表' >&2; exit 1; }; "
            + "name=$(tr '\\000' '\\n' < \"/proc/$pid/cmdline\" | head -n 1); "
            + "[ \"$name\" = '"
            + entry.process
            + "' ] || stale; "
            + "ids=$(grep '^Uid:' \"/proc/$pid/status\") || stale; set -- $ids; "
            + "[ \"${2:-x}\" = '"
            + entry.uid
            + "' ] && [ \"${3:-x}\" = '"
            + entry.uid
            + "' ] || stale; "
            + "read_start && [ \"$start\" = '"
            + entry.start
            + "' ] && is_cached || stale; "
            + "am send-trim-memory --user "
            + user
            + " '"
            + entry.process
            + "' COMPLETE";
    String response = Root.run(cmd, 15).trim();
    String lower = response.toLowerCase(Locale.ROOT);
    if (lower.contains("error")
        || lower.contains("exception")
        || lower.contains("unknown command")) {
      throw new IOException(response);
    }
    if (Build.VERSION.SDK_INT >= 34) {
      return "已提交请求。Android 14 及以上可能忽略 COMPLETE 回调。";
    }
    return "已提交内存回收请求，由应用决定是否释放内存。";
  }
}

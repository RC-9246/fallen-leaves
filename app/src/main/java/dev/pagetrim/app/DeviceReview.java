package dev.pagetrim.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Sample without running a reclaim action. Counters are compared only within the same boot. */
final class DeviceReview {
  private DeviceReview() {}

  static String capabilities(Context c) throws Exception {
    StringBuilder text = new StringBuilder();
    int ready = 0;
    boolean gpuStats = false;
    for (KernelScopes.Key key : KernelScopes.Key.values()) {
      KernelScopes.Probe p = KernelScopes.probe(c, key);
      if (p.actionable && key == KernelScopes.Key.GPU_PROCESS) gpuStats = true;
      else if (p.actionable) ready++;
      text.append(p.actionable && key != KernelScopes.Key.GPU_PROCESS ? "可执行 · " : "只读/缺接口 · ")
          .append(key.label)
          .append("\n")
          .append(p.detail)
          .append("\n\n");
    }
    return "本次在 "
        + KernelScopes.Key.values().length
        + " 项内核能力中核对到 "
        + ready
        + " 项可执行。"
        + (gpuStats ? "另支持按目标读取 GPU 明细。" : "")
        + "部分操作还需选择合格目标。\n\n"
        + text.toString().trim();
  }

  private static final String SAMPLE =
      "cat /proc/sys/kernel/random/boot_id\n"
          + "awk '$1==\"MemAvailable:\" || $1==\"SwapFree:\" {print $1,$2}' /proc/meminfo\n"
          + "awk '$1==\"pgmajfault\" || $1==\"pswpin\" || $1==\"pswpout\" ||"
          + " $1==\"workingset_refault\" || $1==\"workingset_refault_anon\" ||"
          + " $1==\"workingset_refault_file\" {print $1,$2}' /proc/vmstat\n"
          + "for f in /sys/block/zram[0-9]*/mm_stat; do [ -r \"$f\" ] && awk -v f=\"$f\" 'NF>=3"
          + " {print f,$3}' \"$f\"; done\n"
          + "if [ -r /proc/pressure/memory ]; then awk '$1==\"some\" || $1==\"full\""
          + " {for(i=2;i<=NF;i++) if($i~/^total=/){sub(/^total=/,\"\",$i);print \"psi_\"$1,$i}}'"
          + " /proc/pressure/memory; fi\n"
          + ":";

  static synchronized String snapshot(Context c, int phase) throws Exception {
    if (phase < 0 || phase > 2) throw new IOException("无效的观察阶段。");
    String[] lines = Root.run(SAMPLE, 14).split("\n");
    if (lines.length < 2 || !lines[0].matches("[0-9a-fA-F-]{36}"))
      throw new IOException("无法核对启动标识。");
    JSONObject now =
        new JSONObject().put("boot", lines[0]).put("elapsed", SystemClock.elapsedRealtime());
    for (int i = 1; i < lines.length; i++) {
      String[] words = lines[i].trim().split("\\s+");
      if (words.length == 2 && words[1].matches("[0-9]{1,18}"))
        now.put(words[0], Long.parseLong(words[1]));
    }
    SharedPreferences prefs = c.getSharedPreferences("observed_scenario", Context.MODE_PRIVATE);
    JSONObject previous = null;
    if (phase > 0) {
      String prior = prefs.getString("step" + (phase - 1), null);
      if (prior == null) throw new IOException("请先采集上一阶段。");
      previous = new JSONObject(prior);
      if (!lines[0].equals(previous.optString("boot"))) throw new IOException("手机已重启，请重新采集基线。");
    }
    SharedPreferences.Editor edit = prefs.edit().putString("step" + phase, now.toString());
    for (int i = phase + 1; i <= 2; i++) edit.remove("step" + i);
    if (!edit.commit()) throw new IOException("快照未能保存。");
    StringBuilder result = new StringBuilder(new String[] {"动作前", "动作后", "切回后"}[phase]);
    if (previous != null)
      result
          .append(" · 距上一阶段 ")
          .append((now.getLong("elapsed") - previous.getLong("elapsed")) / 1000)
          .append(" 秒");
    List<String> keys = new ArrayList<>();
    now.keys().forEachRemaining(keys::add);
    Collections.sort(keys);
    for (String key : keys) {
      if (key.equals("boot") || key.equals("elapsed")) continue;
      long value = now.getLong(key);
      boolean kib = key.equals("MemAvailable:") || key.equals("SwapFree:");
      boolean bytes = key.endsWith("/mm_stat");
      String label =
          kib
              ? (key.startsWith("Mem") ? "可用运存" : "剩余逻辑交换空间")
              : bytes ? key.split("/")[3] + " 物理占用" : key;
      result.append("\n").append(label).append("　");
      result.append(
          kib
              ? MainActivity.fmt(value * 1024)
              : bytes ? MainActivity.fmt(value) : Long.toString(value));
      if (key.startsWith("psi_")) result.append(" µs");
      if (previous != null && previous.has(key)) {
        long delta = value - previous.getLong(key);
        if (!kib && !bytes && delta < 0) result.append("（计数重置）");
        else
          result
              .append("（")
              .append(delta > 0 ? "+" : delta < 0 ? "−" : "")
              .append(
                  kib || bytes
                      ? MainActivity.fmt(Math.abs(delta) * (kib ? 1024 : 1))
                      : Long.toString(Math.abs(delta)))
              .append("）");
      }
    }
    if (!now.has("psi_some")) result.append("\n本机未提供内存 PSI。");
    return result.append("\n整机变化包含同期其它应用活动。").toString();
  }

  private static final String FRAME_PREFS = "hwui_frame_starts";

  private static PackageInfo frameApp(Context c, String pkg) throws Exception {
    if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
      throw new IOException("应用包名无效。");
    for (StorageController.Entry app : StorageController.list(c))
      if (app.pkg.equals(pkg)) return c.getPackageManager().getPackageInfo(pkg, 0);
    throw new IOException("应用已变化。");
  }

  private static String frameIdentity(Context c, PackageInfo app) throws Exception {
    String[] owners = c.getPackageManager().getPackagesForUid(app.applicationInfo.uid);
    if (owners == null || owners.length != 1 || !app.packageName.equals(owners[0]))
      throw new IOException("这个 UID 的主进程帧不能单独归属到所选应用。");
    return app.applicationInfo.uid + "/" + app.firstInstallTime + "/" + app.lastUpdateTime
        + "/" + Cooperative.signer(c, app.packageName);
  }

  private static String bootId() throws Exception {
    String boot = Root.run("cat /proc/sys/kernel/random/boot_id", 6);
    if (!boot.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
      throw new IOException("无法核对手机启动标识。");
    return boot;
  }

  static synchronized String markFrames(Context c, String pkg) throws Exception {
    PackageInfo app = frameApp(c, pkg);
    String identity = frameIdentity(c, app);
    String boot = bootId();
    // HWUI uses CLOCK_MONOTONIC. elapsedRealtimeNanos includes deep sleep and is a different clock.
    long start = System.nanoTime();
    boolean saved = c.getSharedPreferences(FRAME_PREFS, Context.MODE_PRIVATE).edit()
        .putString(pkg, boot + "\n" + identity + "\n" + start).commit();
    if (!saved) throw new IOException("帧统计起点未能保存。");
    return pkg + " 已设起点。现在可以切换到目标应用操作，再回来读取起点后的帧。";
  }

  static long readFrameStart(String saved, String boot, String identity, long end) throws IOException {
    if (saved == null) throw new IOException("请先为这个应用设置帧统计起点。");
    String[] fields = saved.split("\\n", -1);
    if (fields.length != 3 || !boot.equals(fields[0]) || !identity.equals(fields[1]))
      throw new IOException("手机已重启或应用安装身份已变化，请重新设起点。");
    try {
      long start = Long.parseLong(fields[2]);
      if (start > 0 && start <= end) return start;
    } catch (NumberFormatException ignored) {
      // A corrupt or stale marker cannot delimit a measurement.
    }
    throw new IOException("帧统计起点无效，请重新设置。");
  }

  static synchronized String frameStatsSince(Context c, String pkg) throws Exception {
    // Exclude frames that complete while the measurement is being retrieved.
    long end = System.nanoTime();
    PackageInfo app = frameApp(c, pkg);
    String identity = frameIdentity(c, app);
    String saved = c.getSharedPreferences(FRAME_PREFS, Context.MODE_PRIVATE).getString(pkg, null);
    long start = readFrameStart(saved, bootId(), identity, end);
    String raw = frameDump(app);
    if (!identity.equals(frameIdentity(c, c.getPackageManager().getPackageInfo(pkg, 0))))
      throw new IOException("读取期间应用安装身份已变化，请重新设起点。");
    return frameReport(pkg, raw, start, end);
  }

  static String frameStats(Context c, String pkg) throws Exception {
    return frameReport(pkg, frameDump(frameApp(c, pkg)), 0, Long.MAX_VALUE);
  }

  private static String frameDump(PackageInfo app) throws Exception {
    return Root.run(frameDumpCommand(app.applicationInfo.uid, app.applicationInfo.processName), 10);
  }

  static String frameDumpCommand(int uid, String name) throws IOException {
    if (uid < 0 || name == null || !name.matches("[A-Za-z0-9_.:]+"))
      throw new IOException("无法核对主进程名称。");
    // Select the main process for this Android user. Keep its proc directory pinned across the dump.
    return "uid=" + uid + "\nname='" + name + "'\n"
        + "pids=$(ps -A -o PID,UID,NAME | awk -v u=\"$uid\" -v n=\"$name\" "
        + "'NR==1 && ($1!=\"PID\" || $2!=\"UID\" || $3!=\"NAME\"){exit 1} "
        + "$1~/^[0-9]+$/ && $2==u && $3==n {print $1} END{if(!NR)exit 1}') || exit 1\n"
        + "set -- $pids; [ \"$#\" -gt 0 ] || exit 0; [ \"$#\" -eq 1 ] || exit 1; p=$1\n"
        + "cd -P /proc/\"$p\" || exit 1\n"
        + "matches() { [ \"$(awk '/^Uid:/{print $2,$3,$4,$5}' status)\" = \"$uid $uid $uid $uid\" ] && "
        + "[ \"$(tr '\\000' '\\n' < cmdline | head -n 1)\" = \"$name\" ]; }\n"
        + "matches || exit 1\n"
        // A full multi-window dump can exceed both Android's single-argument limit and Root's
        // output limit. Stream only numeric HWUI columns, without retaining any window titles.
        + "{ dumpsys -t 6 gfxinfo \"$p\" framestats && echo END_OF_HWUI_DUMP; } | "
        + "awk -F, -v h=\"** Graphics info for pid $p [$name] **\" '"
        + "$0==h {found=1;next} $0==\"END_OF_HWUI_DUMP\" {ok=1;next} !found {next} "
        + "$0==\"---PROFILEDATA---\" {inside=!inside;s=e=0;print;next} "
        + "/DUMP TIMEOUT|Failure while dumping|RemoteException while dumping/ {print \"DUMP TIMEOUT\";next} "
        + "inside && $1==\"Flags\" {s=e=0;for(i=2;i<=NF;i++){if($i==\"IntendedVsync\")s=i; "
        + "if($i==\"FrameCompleted\")e=i} if(s&&e)print \"Flags,IntendedVsync,FrameCompleted\";next} "
        + "inside && s && e && $1==\"0\" && $s~/^[0-9]+$/ && $e~/^[0-9]+$/ "
        + "{print \"0,\" $s \",\" $e} END{if(!found || !ok)exit 1}' || exit 1\n"
        + "matches || exit 1";
  }

  // Only consume complete PROFILEDATA sections. Every window has its own bounded history.
  static String frameReport(String pkg, String raw, long from, long until) throws IOException {
    List<Long> durations = new ArrayList<>();
    List<Long> window = null;
    int start = -1, end = -1, flags = -1, possiblyTruncated = 0;
    boolean olderRow = false;
    for (String line : raw.split("\n")) {
      line = line.trim();
      if (line.equals("---PROFILEDATA---")) {
        if (window == null) window = new ArrayList<>();
        else {
          if (from > 0 && !window.isEmpty() && !olderRow) possiblyTruncated++;
          durations.addAll(window);
          window = null;
        }
        start = end = flags = -1;
        olderRow = false;
        continue;
      }
      if (window == null) continue;
      String[] fields = line.split(",");
      if (line.startsWith("Flags,")) {
        flags = start = end = -1;
        for (int i = 0; i < fields.length; i++) {
          if (fields[i].equals("Flags")) flags = i;
          if (fields[i].equals("IntendedVsync")) start = i;
          if (fields[i].equals("FrameCompleted")) end = i;
        }
      } else if (start >= 0 && end >= 0 && flags >= 0
          && fields.length > Math.max(flags, Math.max(start, end))) {
        try {
          long a = Long.parseLong(fields[start]), b = Long.parseLong(fields[end]);
          if (!"0".equals(fields[flags]) || a <= 0 || b <= a || b == Long.MAX_VALUE || b > until) continue;
          if (a <= from) olderRow = true;
          if (a >= from) window.add(b - a);
        } catch (NumberFormatException ignored) {
          // Vendor headers and unfinished records can contain non-numeric fields.
        }
      }
    }
    if (durations.isEmpty() && from == 0)
      throw new IOException("未取得可核验的 HWUI 帧记录。部分游戏使用独立的图形渲染管线。");
    String title = from == 0 ? pkg + " · 系统留存 " : pkg + " · 起点到本次读取开始，主进程各窗口留存 ";
    StringBuilder result = new StringBuilder(title).append(durations.size()).append(" 条有效 HWUI 窗口帧记录。");
    if (!durations.isEmpty()) {
      Collections.sort(durations);
      result.append(String.format(Locale.ROOT, "\n帧时长中位数 %.1f ms，P95 %.1f ms。",
          percentile(durations, 0.5), percentile(durations, 0.95)));
    }
    if (from == 0) return result.append("\n同一窗口的历史帧可重复出现在两次采样中；不同窗口会合并统计。").toString();
    if (durations.isEmpty()) result.append("\n没有可计算时长的样本。应用可能未绘制 HWUI 帧，或旧窗口记录已失效。");
    if (possiblyTruncated > 0) result.append("\n有 ").append(possiblyTruncated)
        .append(" 个窗口的最早可核验帧已晚于起点，可能为新窗口，也可能已有记录被覆盖。");
    if (window != null || raw.contains("DUMP TIMEOUT") || raw.contains("Failure while dumping")
        || raw.contains("RemoteException while dumping"))
      result.append("\n部分窗口未完整导出，仅计入完整的记录段。");
    return result.append("\n结果合并各窗口。已销毁窗口或被环形缓冲覆盖的帧无法追回。").toString();
  }

  private static double percentile(List<Long> sorted, double p) {
    return sorted.get((int) Math.ceil(sorted.size() * p) - 1) / 1000000.0;
  }
}

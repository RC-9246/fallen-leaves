package dev.pagetrim.app;

import android.content.Context;
import android.content.SharedPreferences;
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

  static String frameStats(Context c, String pkg) throws Exception {
    boolean allowed = false;
    for (StorageController.Entry app : StorageController.list(c))
      if (app.pkg.equals(pkg)) {
        allowed = true;
        break;
      }
    if (!allowed || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
      throw new IOException("应用已变化。");
    String raw = Root.run("dumpsys -t 6 gfxinfo " + pkg + " framestats", 10);
    List<Long> durations = new ArrayList<>();
    int start = -1, end = -1, flags = -1;
    for (String line : raw.split("\n")) {
      line = line.trim();
      if (line.equals("---PROFILEDATA---")) {
        start = end = flags = -1;
        continue;
      }
      String[] fields = line.split(",");
      if (line.startsWith("Flags,")) {
        for (int i = 0; i < fields.length; i++) {
          if (fields[i].equals("Flags")) flags = i;
          if (fields[i].equals("IntendedVsync")) start = i;
          if (fields[i].equals("FrameCompleted")) end = i;
        }
      } else if (start >= 0
          && end >= 0
          && flags >= 0
          && fields.length > Math.max(flags, Math.max(start, end))) {
        try {
          long a = Long.parseLong(fields[start]), b = Long.parseLong(fields[end]);
          if ("0".equals(fields[flags]) && a > 0 && b > a && b != Long.MAX_VALUE)
            durations.add(b - a);
        } catch (NumberFormatException ignored) {
        }
      }
    }
    if (durations.isEmpty()) throw new IOException("未取得可核验的 HWUI 帧记录。部分游戏使用独立的图形渲染管线。");
    Collections.sort(durations);
    return String.format(
        Locale.ROOT,
        "%s · 系统留存 %d 条有效 HWUI 窗口帧记录。\n帧时长中位数 %.1f ms，P95 %.1f ms。\n同一窗口的历史帧可重复出现在两次采样中；不同窗口会合并统计。",
        pkg,
        durations.size(),
        percentile(durations, 0.5),
        percentile(durations, 0.95));
  }

  private static double percentile(List<Long> sorted, double p) {
    return sorted.get((int) Math.ceil(sorted.size() * p) - 1) / 1000000.0;
  }
}

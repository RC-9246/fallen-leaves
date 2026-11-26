package dev.pagetrim.app;

import android.content.Context;
import android.os.Process;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Manual, package-scoped operations on an explicitly selected cohort of cached processes. */
final class TargetReclaim {
  enum Mode { ANON, FILE, COLD }

  static final class Result {
    final String text;
    final boolean partial;

    private Result(String text, boolean partial) {
      this.text = text;
      this.partial = partial;
    }
  }

  static final class App {
    final String label, pkg;
    final long rss;
    private final int uid;
    private final List<ProcessTrim.Entry> processes;

    private App(List<ProcessTrim.Entry> entries) {
      ProcessTrim.Entry first = entries.get(0);
      label = first.label;
      pkg = first.pkg;
      uid = first.uid;
      processes = Collections.unmodifiableList(new ArrayList<>(entries));
      long total = 0;
      for (ProcessTrim.Entry e : processes) total = Math.addExact(total, e.rss);
      rss = total;
    }

    int processCount() { return processes.size(); }
  }

  private TargetReclaim() {}

  static List<App> list(Context context) throws Exception {
    Map<Integer, List<ProcessTrim.Entry>> groups = new HashMap<>();
    for (ProcessTrim.Entry entry : ProcessTrim.list(context)) {
      groups.computeIfAbsent(entry.uid, ignored -> new ArrayList<>()).add(entry);
    }
    List<App> result = new ArrayList<>();
    if (groups.isEmpty()) return result;
    // Omit partial app groups, including an app with a cached worker and foreground main process.
    Map<Integer, Set<Integer>> live = new HashMap<>();
    for (String line : Root.run("ps -A -o PID,UID", 10).split("\n")) {
      String[] fields = line.trim().split("\\s+");
      if (fields.length != 2) continue;
      try {
        int pid = Integer.parseInt(fields[0]);
        int uid = Integer.parseInt(fields[1]);
        if (groups.containsKey(uid)) live.computeIfAbsent(uid, k -> new HashSet<>()).add(pid);
      } catch (NumberFormatException ignored) { /* ps header */ }
    }
    for (List<ProcessTrim.Entry> entries : groups.values()) {
      Set<Integer> selected = new HashSet<>();
      for (ProcessTrim.Entry e : entries) selected.add(e.pid);
      if (selected.size() == entries.size() && selected.equals(live.get(entries.get(0).uid))) {
        result.add(new App(entries));
      }
    }
    result.sort((a, b) -> Long.compare(b.rss, a.rss));
    return result;
  }

  static synchronized Result run(Context context, App app, Mode mode) throws Exception {
    if (app == null || mode == null || app.uid == Process.myUid()
        || !ProcessTrim.sameApp(context.getPackageManager(), app.pkg, app.uid)) {
      throw new IOException("已选应用或所属用户发生变化，请重新选取。");
    }
    // The package might have been replaced, disabled or become a VPN app since selection.
    boolean eligible = false;
    for (StorageController.Entry entry : StorageController.list(context)) {
      if (app.pkg.equals(entry.pkg)) { eligible = true; break; }
    }
    if (!eligible) throw new IOException("应用已不符合操作条件。");
    try {
      return report(app.processCount(), mode, Root.run(command(app, mode), 90));
    } catch (IOException e) {
      String message = e.getMessage();
      if (message != null && (message.contains("超时") || message.contains("未能完整读取"))) {
        throw new IOException("未收到完整回执，已有进程可能完成了操作；请刷新后核对。", e);
      }
      throw e;
    }
  }

  // A process directory is pinned as the subshell's working directory. Relative opens cannot
  // follow a recycled numeric PID after identity validation. AMS also rechecks eligibility for kill.
  private static final String FUNCTIONS =
      "stale() { echo '应用进程或缓存状态已变化，请重新选取。' >&2; exit 1; }\n"
      + "cached_uid() { state=$(cmd activity get-uid-state \"$uid\") || return 1;\n"
      + "case \"$state\" in *'(CACHED_ACTIVITY)'|*'(CACHED_ACTIVITY_CLIENT)'|*'(CACHED_RECENT)'|*'(CACHED_EMPTY)') return 0;; *) return 1;; esac; }\n"
      + "members() { listing=$(ps -A -o PID,UID) || return 1;\n"
      + "printf '%s\\n' \"$listing\" | awk -v u=\"$uid\" '$2==u && $1~/^[0-9]+$/ && !seen[$1]++ {print $1}'; }\n"
      + "same_members() { live=$(members) || return 1; n=0; for member in $live; do\n"
      + "case \" $pids \" in *\" $member \"*) ;; *) return 1;; esac; n=$((n+1)); done; [ \"$n\" -eq \"$count\" ]; }\n"
      + "matches_app() { [ \"$(awk '/^Uid:/ {print $2,$3,$4,$5}' status)\" = \"$uid $uid $uid $uid\" ] &&\n"
      + "  [ \"$(tr '\\000' '\\n' < cmdline | head -n 1)\" = \"$name\" ]; }\n"
      + "cached_adj() { IFS= read -r adj < oom_score_adj && [ \"$adj\" -ge 900 ] && [ \"$adj\" -le 999 ]; }\n"
      + "process() ( pid=$1; action=$2; identify \"$pid\" || exit 4;\n"
      + "if ! cd -P /proc/\"$pid\" 2>/dev/null || ! IFS= read -r st < stat; then [ ! -d /proc/\"$pid\" ] && exit 1; exit 4; fi\n"
      + "set -- ${st##*) }; [ \"$#\" -ge 20 ] || exit 4; [ \"${20}\" = \"$born\" ] && [ \"$1\" != Z ] || exit 1;\n"
      + "[ \"$action\" = alive ] && exit 0; matches_app || exit 1;\n"
      + "case $action in rss) awk '$1==\"VmRSS:\" && $2~/^[0-9]+$/ && $3==\"kB\" {print $2;ok=1} END{if(!ok)exit 1}' status;;\n"
      + "check) cached_adj || exit 1; if [ \"$area\" != cold ]; then [ -f reclaim ] && [ ! -L reclaim ] && [ -w reclaim ] || exit 3; fi;;\n"
      + "reclaim) [ -f reclaim ] && [ ! -L reclaim ] && exec 3>reclaim || exit 1; cached_adj && printf '%s\\n' \"$area\" >&3;; esac )\n"
      + "sum_rss() { total=0; for p in $pids; do kib=$(process \"$p\" rss) || return 1; total=$((total + kib)); done; echo \"$total\"; }\n"
      + "zram_only() { awk 'NR>1 {if($1 !~ /\\/zram[0-9]+$/) bad=1; n++} END{exit (!n || bad)}' /proc/swaps; }\n"
      + "zstat() ( set +f; total=0; ids=''; for d in /sys/block/zram[0-9]*; do [ -d \"$d\" ] || continue;\n"
      + "IFS= read -r statline < \"$d/mm_stat\" || exit 1; set -- $statline; case ${3:-x} in ''|*[!0-9]*) exit 1;; esac;\n"
      + "total=$((total+$3)); ids=\"$ids/${d##*/}\"; done; [ -n \"$ids\" ] && echo \"$ids $total\"; )\n";

  private static String command(App app, Mode mode) {
    StringBuilder ids = new StringBuilder();
    StringBuilder identify = new StringBuilder("identify() { case $1 in\n");
    for (ProcessTrim.Entry e : app.processes) {
      // Entries can only be constructed by ProcessTrim, which checks a closed package/name grammar.
      if (e.uid != app.uid || !e.pkg.equals(app.pkg) || e.pid <= 0 || e.start <= 0)
        throw new IllegalArgumentException("目标进程身份无效。");
      if (ids.length() > 0) ids.append(' ');
      ids.append(e.pid);
      identify.append(e.pid).append(") name='").append(e.process).append("'; born=")
          .append(e.start).append(";;\n");
    }
    identify.append("*) return 1;; esac; }\n");
    String area = mode == Mode.ANON ? "anon" : mode == Mode.FILE ? "file" : "cold";
    String pre = "set -f\nuid=" + app.uid + "\npids='" + ids + "'\ncount=" + app.processCount()
        + "\narea=" + area + "\n" + identify + FUNCTIONS
        + "awk '$2==\"/proc\" && $3==\"proc\" {ok=1} END{exit !ok}' /proc/mounts || exit 1\n"
        + "same_members && cached_uid || stale\n"
        + "for p in $pids; do process \"$p\" check; case $? in 0) ;; 3) echo '目标进程未开放可写的内核回收接口。' >&2; exit 1;; *) stale;; esac; done\n";
    if (mode == Mode.COLD) {
      return pre + "same_members && cached_uid || stale\n"
          + "for p in $pids; do process \"$p\" check || stale; done\n"
          + "msg=$(am kill --user " + app.uid / 100000 + " '" + app.pkg + "' 2>&1) || exit 1\n"
          + "case \"$msg\" in *[Ee]rror*|*[Ee]xception*|*'Unknown command'*) echo '系统拒绝结束缓存应用。' >&2; exit 1;; esac\n"
          + "i=0; while :; do alive=0; unknown=0; for p in $pids; do process \"$p\" alive; case $? in 0) alive=$((alive+1));; 1) :;; *) unknown=$((unknown+1));; esac; done;\n"
          + "i=$((i+1)); [ $((alive+unknown)) -gt 0 ] && [ \"$i\" -lt 6 ] || break; sleep 0.2; done\n"
          + "if live=$(members); then set -- $live; now=$#; else now=-1; fi; echo COLD \"$alive\" \"$unknown\" \"$now\"\n";
    }
    return pre
        + (mode == Mode.ANON ? "zram_only || { echo '匿名页回收需要已启用且仅使用 zRAM 的交换区。' >&2; exit 1; }\n" : "")
        + "base=$(sum_rss) || stale; echo BASE \"$base\"; z=$(zstat 2>/dev/null) && echo Z0 $z\n"
        + "done_count=0; for p in $pids; do same_members && cached_uid && process \"$p\" reclaim || break; done_count=$((done_count+1)); done\n"
        + "echo DONE \"$done_count\"; if same_members && after=$(sum_rss); then echo AFTER \"$after\"; fi\n"
        + "z=$(zstat 2>/dev/null) && echo Z1 $z; :\n";
  }

  private static String change(long value) {
    return (value > 0 ? "+" : value < 0 ? "−" : "") + MainActivity.fmt(Math.abs(value));
  }

  static Result report(int count, Mode mode, String output) throws IOException {
    Map<String, String[]> fields = new HashMap<>();
    for (String row : output.split("\n")) {
      String[] parts = row.trim().split("\\s+");
      fields.put(parts[0], parts);
    }
    try {
      if (mode == Mode.COLD) {
        String[] cold = fields.get("COLD");
        int alive = Integer.parseInt(cold[1]);
        int unknown = Integer.parseInt(cold[2]);
        int current = Integer.parseInt(cold[3]);
        if (alive < 0 || unknown < 0 || alive + unknown > count || current < -1)
          throw new IllegalArgumentException();
        String summary = "原缓存进程已确认退出 " + (count - alive - unknown) + "/" + count + " 个。";
        if (unknown > 0) summary += "另有 " + unknown + " 个进程状态无法核验。";
        summary += current < 0 ? "当前同 UID 进程数不可用。" : "该 UID 当前有 " + current + " 个进程。";
        return new Result(summary + "系统可能重新拉起应用，应用数据保留。",
            alive > 0 || unknown > 0 || current != 0);
      }
      int done = Integer.parseInt(fields.get("DONE")[1]);
      if (done < 0 || done > count) throw new IllegalArgumentException();
      if (done == 0) throw new IOException("未执行回收。应用状态已变化或内核拒绝操作，请重新选取。");
      StringBuilder text = new StringBuilder("内核已受理 ").append(done).append('/').append(count).append(" 个进程的请求。");
      if (done < count) text.append("其余请求因状态变化或内核拒绝而中止。");
      Long saved = null;
      if (fields.containsKey("AFTER")) {
        long before = Math.multiplyExact(Long.parseLong(fields.get("BASE")[1]), 1024L);
        long after = Math.multiplyExact(Long.parseLong(fields.get("AFTER")[1]), 1024L);
        if (before < 0 || after < 0) throw new IllegalArgumentException();
        saved = before - after;
        text.append("\n目标进程 RSS 合计 ").append(MainActivity.fmt(before)).append(" → ").append(MainActivity.fmt(after))
            .append("，驻留减少量 ").append(change(saved)).append("。");
      } else {
        text.append("\n进程集合或读数发生变化，驻留减少量不可比较。");
      }
      String[] z0 = fields.get("Z0"), z1 = fields.get("Z1");
      boolean measuredZram = z0 != null && z1 != null && z0[1].equals(z1[1]);
      if (!measuredZram) {
        text.append("\n整机 zRAM 物理用量变化不可用。");
      } else {
        long before = Long.parseLong(z0[2]), after = Long.parseLong(z1[2]);
        if (before < 0 || after < 0) throw new IllegalArgumentException();
        long added = after - before;
        text.append("\n整机 zRAM 物理用量 ").append(MainActivity.fmt(before)).append(" → ").append(MainActivity.fmt(after))
            .append("，增加量 ").append(change(added)).append("。");
        if (saved != null && done == count && mode == Mode.ANON)
          text.append("\n粗略净收益 ").append(change(Math.subtractExact(saved, added))).append("（驻留减少量减去 zRAM 增加量）。");
      }
      return new Result(text.append("\n快照会受同期系统活动影响。多进程 RSS 可能重复计入共享页。").toString(),
          done != count || saved == null || !measuredZram);
    } catch (RuntimeException malformed) {
      throw new IOException("未能核对操作后的完整系统结果。", malformed);
    }
  }
}

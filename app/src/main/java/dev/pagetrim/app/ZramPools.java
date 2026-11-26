package dev.pagetrim.app;

import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;

/** zRAM allocation-pool compaction, isolated from unrelated cleanup operations. */
final class ZramPools {
  private ZramPools() {}

  static final class Probe {
    final boolean supported;
    final String detail;

    Probe(boolean supported, String detail) {
      this.supported = supported;
      this.detail = detail;
    }
  }

  static Probe availability() throws Exception {
    String raw = Root.run(script(false), 15);
    boolean supported = java.util.regex.Pattern.compile("(?m)^zram[0-9]+ READY ").matcher(raw).find();
    return new Probe(supported, render(raw, false, Os.sysconf(OsConstants._SC_PAGESIZE)));
  }

  static final class Result {
    final String text;
    final boolean partial;
    Result(String text, boolean partial) { this.text = text; this.partial = partial; }
  }

  static Result compact() throws Exception {
    return renderResult(Root.run(script(true), 90), true, Os.sysconf(OsConstants._SC_PAGESIZE));
  }

  static String script(boolean execute) {
    return "run=" + (execute ? 1 : 0) + "\n"
        + "snapshot() { if IFS= read -r stats < mm_stat; then set -- $stats; echo \"${3:-?} ${7:-?}\"; else echo '? ?'; fi; }\n"
        + "inspect() ( cd -P \"$1\" || { echo \"$2 UNREADABLE\"; exit; }; id=$2; set -f\n"
        + "IFS= read -r size < disksize || { echo \"$id UNREADABLE\"; exit; };\n"
        + "case \"$size\" in 0) echo \"$id IDLE\"; exit;; ''|*[!0-9]*) echo \"$id UNREADABLE\"; exit;; esac\n"
        + "[ -f compact ] && [ ! -L compact ] && [ -w compact ] || { echo \"$id UNSUPPORTED\"; exit; }\n"
        + "before=$(snapshot 2>/dev/null); [ \"$run\" = 1 ] || { echo \"$id READY $before\"; exit; };\n"
        + "if ! (printf '1\\n' > compact) 2>/dev/null; then echo \"$id REJECTED\"; exit; fi\n"
        + "if ! IFS= read -r current < disksize || [ \"$current\" != \"$size\" ]; then echo \"$id CHANGED\"; exit; fi\n"
        + "after=$(snapshot 2>/dev/null); echo \"$id DONE $before $after\"; )\n"
        + "for d in /sys/block/zram[0-9]*; do [ -d \"$d\" ] || continue; id=${d##*/};\n"
        + "case ${id#zram} in ''|*[!0-9]*) continue;; esac; inspect \"$d\" \"$id\" 2>/dev/null; done; :\n";
  }

  private static long value(String[] fields, int index) {
    if (index >= fields.length) return -1;
    try { return Math.max(-1, Long.parseLong(fields[index])); }
    catch (NumberFormatException ignored) { return -1; }
  }

  static String render(String output, boolean execute, long pageSize) throws IOException {
    return renderResult(output, execute, pageSize).text;
  }

  static Result renderResult(String output, boolean execute, long pageSize) throws IOException {
    StringBuilder text = new StringBuilder();
    int completed = 0, failed = 0;
    for (String line : output.split("\n")) {
      if (line.isEmpty()) continue;
      String[] fields = line.trim().split("\\s+");
      if (fields.length < 2 || !fields[0].matches("zram[0-9]+")) continue;
      if (text.length() > 0) text.append('\n');
      text.append(fields[0]).append(" · ");
      switch (fields[1]) {
        case "IDLE": text.append("未初始化。"); break;
        case "UNREADABLE": text.append("初始化状态不可读取。"); failed++; break;
        case "UNSUPPORTED": text.append("缺少可写的 compact 接口。"); failed++; break;
        case "REJECTED": text.append("驱动拒绝执行整理。"); failed++; break;
        case "CHANGED": text.append("已提交整理；设备容量或初始化状态变化，读数不可比较。"); failed++; break;
        case "READY":
          text.append("compact 接口可写。物理用量 ").append(MainActivity.fmt(value(fields, 2)));
          long prior = value(fields, 3);
          text.append("，累计整理页数 ").append(prior < 0 ? "不可用" : Long.toString(prior)).append("。");
          break;
        case "DONE":
          completed++;
          long before = value(fields, 2), pagesBefore = value(fields, 3);
          long after = value(fields, 4), pagesAfter = value(fields, 5);
          text.append("已提交整理。物理用量 ").append(MainActivity.fmt(before)).append(" → ").append(MainActivity.fmt(after)).append("。");
          if (pagesBefore >= 0 && pagesAfter >= 0 && pagesAfter < pagesBefore) {
            text.append("累计页计数已重置，前后数据不可比较。"); failed++; break;
          }
          if (before >= 0 && after >= 0) {
            text.append(after > before ? "增加 " : "减少 ").append(MainActivity.fmt(Math.abs(after - before))).append("。");
          } else { text.append("用量变化不可用。"); failed++; }
          if (pagesBefore >= 0 && pagesAfter >= 0) {
            long reclaimed = pagesAfter - pagesBefore;
            text.append("累计整理页 ").append(pagesBefore).append(" → ").append(pagesAfter)
                .append("，本次增加 ").append(reclaimed).append(" 页");
            if (pageSize > 0 && reclaimed <= Long.MAX_VALUE / pageSize)
              text.append("（").append(MainActivity.fmt(reclaimed * pageSize)).append("）");
            text.append("。");
          } else { text.append("整理页计数不可用。"); failed++; }
          break;
        default: text.append("状态无法识别。"); failed++;
      }
    }
    if (text.length() == 0) text.append("未发现 zRAM 块设备。");
    if (pageSize > 0) text.append("\n系统页大小 ").append(pageSize).append(" B。");
    else text.append("\n系统页大小不可用。");
    if (execute) {
      if (completed > 0) text.append("\n物理用量差包含同时发生的交换活动。");
      if (completed > 0 && failed > 0) return new Result("部分完成。\n" + text, true);
      if (completed == 0)
        throw new IOException("已取得 " + completed + " 台设备的整理后记录。\n" + text);
    }
    return new Result(text.toString(), false);
  }
}

package dev.pagetrim.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

final class Diagnostics {
  static final long WEEK = 7L * 24 * 3600;
  private static final Pattern ALLOWED =
      Pattern.compile(
          "/data/tombstones/tombstone_[0-9]{1,4}(?:\\.pb)?|"
              + "/data/anr/anr_[0-9-]{10,40}|"
              + "/data/vendor/ramdump/ramdump_wlan_[0-9_-]{8,40}\\.elf|"
              + "/data/opdiagnose/[0-9]{1,6}/(?:android_log|event_log|kernel_log|radio_log)");
  private static final String META = "%d:%i:%s:%Y:%h:%b:%B";

  static final class Item {
    final String path, token;
    final long bytes, modified;

    private Item(String path, String token, long bytes, long modified) {
      this.path = path;
      this.token = token;
      this.bytes = bytes;
      this.modified = modified;
    }

    String label() {
      if (path.startsWith("/data/opdiagnose/")) return path.substring("/data/".length());
      return path.substring(path.lastIndexOf('/') + 1);
    }
  }

  static final class Result {
    int deleted, skipped;
    long bytes;
  }

  static List<Item> scan() throws Exception {
    // Enumerate only the immediate, system-owned locations, and never traverse shared storage.
    String files =
        "for f in /data/tombstones/tombstone_* /data/anr/anr_*"
            + " /data/vendor/ramdump/ramdump_wlan_*.elf"
            + " /data/opdiagnose/[0-9]*/{android_log,event_log,kernel_log,radio_log}; do [ -f"
            + " \"$f\" ] && [ ! -L \"$f\" ] && [ \"$(readlink -f \"$f\")\" = \"$f\" ] || continue;"
            + " stat -c '"
            + META
            + "|%n' \"$f\" || exit 1; done; exit 0";
    return parse(Root.run(files, 30), System.currentTimeMillis() / 1000);
  }

  static List<Item> parse(String records, long now) {
    List<Item> eligible = new ArrayList<>();
    for (String row : records.split("\n")) {
      int sep = row.indexOf('|');
      if (sep < 0) continue;
      String path = row.substring(sep + 1);
      String token = row.substring(0, sep);
      if (!ALLOWED.matcher(path).matches() || !token.matches("[0-9]+(:[0-9]+){6}")) continue;
      try {
        String[] t = token.split(":");
        long modified = Long.parseLong(t[3]);
        long allocated = Math.multiplyExact(Long.parseLong(t[5]), Long.parseLong(t[6]));
        if ("1".equals(t[4]) && modified > 0 && now - modified >= WEEK)
          eligible.add(new Item(path, token, allocated, modified));
      } catch (ArithmeticException | NumberFormatException ignored) {
      }
    }
    Collections.sort(eligible, Comparator.comparingLong((Item i) -> i.bytes).reversed());
    return eligible;
  }

  static Result remove(Collection<Item> selected) throws Exception {
    Result result = new Result();
    List<Item> batch = new ArrayList<>();
    for (Item item : selected) {
      batch.add(item);
      if (batch.size() == 40) {
        attemptBatch(batch, result);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) attemptBatch(batch, result);
    return result;
  }

  private static void attemptBatch(List<Item> items, Result result) throws Exception {
    try {
      removeBatch(items, result);
    } catch (Exception error) {
      throw new java.io.IOException(
          "已确认删除 " + result.deleted + " 个文件。操作中断，请重新扫描以核对现场。 " + error.getMessage(), error);
    }
  }

  private static void removeBatch(List<Item> items, Result out) throws Exception {
    StringBuilder cmd = new StringBuilder("now=$(date +%s) || exit 1\n");
    for (int i = 0; i < items.size(); i++) {
      Item f = items.get(i);
      if (!ALLOWED.matcher(f.path).matches() || !f.token.matches("[0-9]+(:[0-9]+){6}"))
        throw new IllegalArgumentException("文件不在可清理的诊断目录内。");
      cmd.append("f='")
          .append(f.path)
          .append("'\n")
          .append("if [ -f \"$f\" ] && [ ! -L \"$f\" ] && [ \"$(readlink -f \"$f\")\" = \"$f\" ]")
          .append(" && [ \"$(stat -c '")
          .append(META)
          .append("' \"$f\")\" = '")
          .append(f.token)
          .append("' ]")
          .append(" && [ $((now-")
          .append(f.modified)
          .append(")) -ge ")
          .append(WEEK)
          .append(" ]")
          .append(" && rm -- \"$f\"; then echo removed_")
          .append(i)
          .append("; else echo skipped_")
          .append(i)
          .append("; fi\n");
    }
    // If shell access is interrupted, a fresh scan reconciles files already removed.
    List<String> lines = java.util.Arrays.asList(Root.run(cmd.toString(), 45).split("\n"));
    for (int i = 0; i < items.size(); i++) {
      if (lines.contains("removed_" + i)) {
        out.deleted++;
        out.bytes += items.get(i).bytes;
      } else out.skipped++;
    }
  }

  private Diagnostics() {}
}

package dev.pagetrim.app;

import java.util.Collections;
import java.util.List;

/** Regression cases for the boundary between system diagnostics and user files. */
public final class DiagnosticsPolicyTest {
  private static final long NOW = 1800000000L;
  private static final String OLD = Long.toString(NOW - Diagnostics.WEEK);

  private static String record(String path, String mtime, int links, int blocks) {
    return "1:80:100:" + mtime + ":" + links + ":" + blocks + ":512|" + path;
  }

  public static void main(String[] args) throws Exception {
    String eligible = "/data/vendor/ramdump/ramdump_wlan_2026-01-02_18-45-15.elf";
    String logs =
        String.join(
            "\n",
            record(eligible, OLD, 1, 8),
            record("/data/tombstones/tombstone_01", OLD, 1, 2),
            record("/data/opdiagnose/7/radio_log", OLD, 1, 1),
            record("/data/anr/anr_2026-01-02-18-45-123", Long.toString(NOW - 1), 1, 8),
            record("/data/tombstones/tombstone_02", OLD, 2, 6),
            record("/data/media/0/Download/keep.apk", OLD, 1, 8),
            record("/data/opdiagnose/7/../../media/0/Download/keep.apk", OLD, 1, 8),
            record("/data/anr/state", OLD, 1, 8),
            "malformed");
    List<Diagnostics.Item> hits = Diagnostics.parse(logs, NOW);
    if (hits.size() != 3 || !hits.get(0).path.equals(eligible) || hits.get(0).bytes != 4096)
      throw new AssertionError("age, allowlist, link count or allocated byte accounting changed");
    if (Diagnostics.remove(Collections.emptyList()).deleted != 0)
      throw new AssertionError("empty selection");
    try {
      RamController.reclaim(4);
      throw new AssertionError("unsupported kernel reclaim mode");
    } catch (IllegalArgumentException expected) {
    }
    System.out.println("Fallen Leaves policy checks passed.");
  }
}

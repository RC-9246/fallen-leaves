package dev.pagetrim.app;

import java.io.IOException;

public final class ZramPoolTest {
  public static void main(String[] args) throws Exception {
    ZramPools.Result zero = ZramPools.renderResult("zram0 DONE 4000 2 4000 2\nzram1 IDLE", true, 4096);
    check(!zero.partial && zero.text.contains("增加 0 页") && zero.text.contains("减少 0 B"));

    String done = "zram0 DONE 12000 1 8000 2\n";
    for (String bad : new String[]{"zram1 REJECTED", "zram2 DONE 3000 2 2000 1",
        "zram3 DONE ? ? ? ?", "zram4 CHANGED"}) {
      ZramPools.Result result = ZramPools.renderResult(done + bad, true, 4096);
      check(result.partial && result.text.contains("zram0") && result.text.contains("增加 1 页"));
    }
    String increased = ZramPools.render("zram2 DONE 8192 1 16384 2", true, 16384);
    check(increased.contains("增加 1 页") && increased.contains("16384 B"));
    ZramPools.Result reset = ZramPools.renderResult("zram0 DONE 8000 4 1000 0", true, 4096);
    check(reset.partial && reset.text.contains("计数已重置") && !reset.text.contains("减少 7000"));
    try {
      ZramPools.renderResult("zram1 REJECTED", true, 4096);
      throw new AssertionError("Rejected writes cannot report success");
    } catch (IOException expected) { check(expected.getMessage().contains("拒绝")); }
    System.out.println("zRAM measurement and partial-result cases passed");
  }

  private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
}

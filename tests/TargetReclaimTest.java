package dev.pagetrim.app;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** Exercises result attribution and emits the actual command for the host procfs fixtures. */
public final class TargetReclaimTest {
  private static final String PKG = "dev.leaves.fixture";

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      String samples = "BASE 716800\nAFTER 409600\nZ0 /zram0 146800640\nZ1 /zram0 262144000\n";
      TargetReclaim.Result measured = TargetReclaim.report(2, TargetReclaim.Mode.ANON, samples + "DONE 2");
      if (measured.partial || !measured.text.contains("粗略净收益 +199229440"))
        throw new AssertionError(measured.text);
      TargetReclaim.Result skipped = TargetReclaim.report(2, TargetReclaim.Mode.ANON, samples + "DONE 1");
      if (!skipped.partial || skipped.text.contains("粗略净收益")) throw new AssertionError(skipped.text);
      if (!TargetReclaim.report(2, TargetReclaim.Mode.FILE, "BASE 12\nAFTER 12\nDONE 2").partial)
        throw new AssertionError("missing zRAM statistics");
      if (!TargetReclaim.report(2, TargetReclaim.Mode.COLD, "COLD 0 1 0").partial)
        throw new AssertionError("unknown process state");
      if (!TargetReclaim.report(1, TargetReclaim.Mode.FORCE_STOP, "COLD 0 0 0\nSTOPPED stopped=false").partial
          || !TargetReclaim.report(1, TargetReclaim.Mode.FORCE_STOP, "COLD 0 0 0").partial
          || TargetReclaim.report(1, TargetReclaim.Mode.FORCE_STOP, "COLD 0 0 0\nSTOPPED stopped=true").partial)
        throw new AssertionError("force-stop needs independent current-user package state");
      TargetReclaim.Result gc = TargetReclaim.report(2, TargetReclaim.Mode.ART_GC, samples + "GC 1");
      if (!gc.partial || !gc.text.contains("1/2")) throw new AssertionError(gc.text);
      TargetReclaim.Result laz = TargetReclaim.report(2, TargetReclaim.Mode.LAZY_FREE, "LAZY 123 0\nLAZY 124 32");
      if (laz.partial || !laz.text.contains("32768")) throw new AssertionError(laz.text);
      TargetReclaim.Result range = TargetReclaim.report(2, TargetReclaim.Mode.RANGE_FILE, samples + "RANGE 1048576 2097152\nSTOP changed");
      if (!range.partial || !range.text.contains("changed") || range.text.contains("粗略净收益"))
        throw new AssertionError(range.text);
      TargetReclaim.Result marked = TargetReclaim.report(2, TargetReclaim.Mode.MARK_COLD, samples + "RANGE 1048576 1048576\nVMA0 2097152 1024 0\nVMA1 2097152 768 256");
      if (marked.partial || !marked.text.contains("标冷") || marked.text.contains("净收益")) throw new AssertionError(marked.text);
      String maps = "VMA0 2097152 1024 0\nVMA1 2097152 768 256\n";
      String appMoves = "BASE 400000\nAFTER 100000\nZ0 /zram0 50\nZ1 /zram0 100\n";
      TargetReclaim.Result subset = TargetReclaim.report(2, TargetReclaim.Mode.RANGE_ANON,
          maps + appMoves + "RANGE 1048576 1048576");
      if (subset.partial || subset.text.contains("粗略净收益") || !subset.text.contains("驻留减少量 +262144")
          || !subset.text.contains("区间以外")) throw new AssertionError(subset.text);
      TargetReclaim.Result full = TargetReclaim.report(2, TargetReclaim.Mode.RANGE_ANON,
          maps + appMoves + "RANGE 2097152 2097152");
      if (full.partial || !full.text.contains("粗略净收益 +262094")) throw new AssertionError(full.text);
      TargetReclaim.Result unmapped = TargetReclaim.report(1, TargetReclaim.Mode.RANGE_ANON,
          appMoves + "VMA0 2097152 1024 0\nRANGE 2097152 2097152");
      if (!unmapped.partial || unmapped.text.contains("粗略净收益")) throw new AssertionError(unmapped.text);
      try {
        TargetReclaim.report(1, TargetReclaim.Mode.PAGEOUT, "RANGE 0 1048576\nSTOP ENOSYS");
        throw new AssertionError("unsupported kernel reported success");
      } catch (java.io.IOException expected) {
        if (!expected.getMessage().contains("ENOSYS")) throw expected;
      }
      return;
    }
    // Constructors are private so production callers can only select targets issued by list().
    Constructor<ProcessTrim.Entry> entry = ProcessTrim.Entry.class.getDeclaredConstructor(
        String.class, String.class, String.class, long.class, int.class, int.class, long.class);
    entry.setAccessible(true);
    List<ProcessTrim.Entry> processes = Arrays.asList(
        entry.newInstance("Fixture", PKG, PKG, 0L, 123, 10200, 4913L),
        entry.newInstance("Fixture", PKG, PKG + ":worker", 0L, 124, 10200, 4925L));
    Constructor<TargetReclaim.App> app = TargetReclaim.App.class.getDeclaredConstructor(List.class);
    app.setAccessible(true);
    Method command = TargetReclaim.class.getDeclaredMethod(
        "command", TargetReclaim.App.class, TargetReclaim.Mode.class);
    command.setAccessible(true);
    System.out.print(command.invoke(null, app.newInstance(processes), TargetReclaim.Mode.valueOf(args[0])));
  }
}

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

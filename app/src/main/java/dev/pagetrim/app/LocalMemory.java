package dev.pagetrim.app;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Executes only within the process which owns these allocations. */
final class LocalMemory {
  static final int GC = 1, SQLITE = 2, NATIVE = 4, ALL = GC | SQLITE | NATIVE;

  private LocalMemory() {}

  static String run(int selected, BooleanSupplier cancelled) {
    StringBuilder report = new StringBuilder();
    if ((selected & GC) != 0) attempt(report, Cooperative.Scope.MANAGED_GC, cancelled);
    if ((selected & SQLITE) != 0) attempt(report, Cooperative.Scope.SQLITE_CACHE, cancelled);
    if ((selected & NATIVE) != 0) attempt(report, Cooperative.Scope.NATIVE_ALLOCATOR, cancelled);
    return report.toString().trim();
  }

  private static void attempt(StringBuilder report, Cooperative.Scope scope, BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean()) {
      if (report.length() == 0) report.append("本次操作已中止。\n");
      return;
    }
    try {
      report.append(Cooperative.runLocal(scope)).append('\n');
    } catch (IOException | RuntimeException error) {
      report.append("未完成：").append(error.getMessage()).append('\n');
    }
  }
}

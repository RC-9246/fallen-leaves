package dev.pagetrim.app;

/** Manual kernel cache reclamation. No application processes or files are removed. */
public final class RamController {
  private RamController() {}

  public static synchronized String reclaim(int mode) throws Exception {
    String command;
    switch (mode) {
      case 1:
        command = "echo 1 > /proc/sys/vm/drop_caches";
        break;
      case 2:
        command = "echo 2 > /proc/sys/vm/drop_caches";
        break;
      case 3:
        command = "echo 3 > /proc/sys/vm/drop_caches";
        break;
      default:
        throw new IllegalArgumentException("Unknown memory reclaim mode: " + mode);
    }
    return Root.run(command, 45);
  }
}

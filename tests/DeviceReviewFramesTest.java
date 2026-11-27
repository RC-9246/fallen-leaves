package dev.pagetrim.app;

import java.io.IOException;

/** Clock boundaries, current-boot install identity and bounded multi-window HWUI reports. */
public final class DeviceReviewFramesTest {
  private static final long MS = 1_000_000L;
  private static final String DELIMITER = "---PROFILEDATA---\n";
  private static final String HEADER = "Flags,Vsync,FrameCompleted,IntendedVsync,\n";
  private static String row(long start, long finish) {
    return "0,7," + finish * MS + "," + start * MS + ",\n";
  }
  private static void expect(String value, String part) {
    if (!value.contains(part)) throw new AssertionError(value);
  }
  private static void rejects(String saved, String boot, String app, long end) throws Exception {
    try {
      DeviceReview.readFrameStart(saved, boot, app, end);
      throw new AssertionError("Invalid marker accepted: " + saved);
    } catch (IOException expected) {}
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1) {
      System.out.print(DeviceReview.frameDumpCommand(10302, "dev.fallen.frame"));
      return;
    }
    String marker = "bootA\nappA\n" + (100 * MS);
    if (DeviceReview.readFrameStart(marker, "bootA", "appA", 150 * MS) != 100 * MS)
      throw new AssertionError("Marker changed");
    rejects(marker, "bootB", "appA", 150 * MS);
    rejects(marker, "bootA", "appB", 150 * MS);
    rejects(marker, "bootA", "appA", 99 * MS);
    rejects(null, "bootA", "appA", 150 * MS);
    rejects("bootA\nappA\nnot-a-number", "bootA", "appA", 150 * MS);

    String old = row(1, 50) + row(99, 110);
    String live = row(100, 110) + row(115, 135);
    String late = row(140, 151) + row(151, 152);
    String invalid = "1,7,112000000,111000000\n0,7,9223372036854775807,113000000\n"
        + "0,7,x,114000000\n" + row(121, 121);
    String first = DELIMITER + HEADER + old + live + late + invalid + DELIMITER;
    String second = DELIMITER + HEADER + row(121, 150) + DELIMITER;
    String partial = DELIMITER + HEADER + row(120, 149);
    String raw = first + second + partial;
    String summary = DeviceReview.frameReport("pkg", raw, 100 * MS, 150 * MS);
    expect(summary, "3 条");
    expect(summary, "中位数 20.0 ms，P95 29.0 ms");
    expect(summary, "有 1 个窗口");
    expect(summary, "部分窗口未完整导出");
    String empty = DeviceReview.frameReport("pkg", first, 300 * MS, 310 * MS);
    expect(empty, "0 条");
    if (empty.contains("P95")) throw new AssertionError(empty);
    expect(DeviceReview.frameReport("pkg", "", 100 * MS, 150 * MS), "没有可计算时长的样本");
    expect(DeviceReview.frameReport("pkg", first + second, 0, Long.MAX_VALUE), "7 条");
    String equality = DeviceReview.frameReport("pkg", DELIMITER + HEADER + row(100, 150) + DELIMITER,
        100 * MS, 150 * MS);
    expect(equality, "1 条");
    if (equality.contains("最早可核验帧")) throw new AssertionError(equality);
    System.out.println("HWUI time-window, identity, invalid records and buffer-boundary fixtures passed.");
  }
}

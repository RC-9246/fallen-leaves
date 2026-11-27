package dev.pagetrim.app;

import android.content.Context;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Separate root picker for verified backing inodes under the current user's Download directory. */
final class RootFileCache {
  private RootFileCache() {}

  static final class Entry {
    final String name, child;
    final boolean directory;
    final long size;
    private final String parent, hex, identity;

    private Entry(
        String name,
        boolean directory,
        long size,
        String child,
        String parent,
        String hex,
        String identity) {
      this.name = name;
      this.directory = directory;
      this.child = child;
      this.size = size;
      this.parent = parent;
      this.hex = hex;
      this.identity = identity;
    }

    String displayPath() {
      StringBuilder path = new StringBuilder("Download/");
      if (!parent.isEmpty()) {
        for (String component : parent.split("/")) path.append(displayName(component)).append('/');
      }
      return path.append(name).toString();
    }
  }

  // Cursors identify the last raw filename and the verified directory snapshot.
  private static final String COMPONENT = "(?:[0-9a-f]{2}){1,255}";
  private static final String STAMP =
      "[0-9]{1,20}:[0-9]{1,20}:[0-9]{1,19}:-?[0-9]{1,19}:[0-9]{1,9}:-?[0-9]{1,19}:[0-9]{1,9}";
  private static final String CURSOR = COMPONENT + "_" + STAMP;

  static List<Entry> list(Context context, String directory) throws Exception {
    if (directory == null || directory.length() > 9000) throw new IOException("目录无效。");
    String[] at = directory.split("@", -1);
    String folder = at[0];
    if (at.length > 2 || (at.length == 2 && !at[1].matches(CURSOR)))
      throw new IOException("目录页无效。");
    if (!folder.isEmpty())
      for (String component : folder.split("/", -1))
        if (!component.matches(COMPONENT)) throw new IOException("目录编码无效。");
    String output =
        Root.run(command(context, "list", folder) + " " + (at.length == 2 ? at[1] : "-"), 30);
    List<Entry> result = new ArrayList<>();
    Entry next = null;
    for (String line : output.split("\n")) {
      if (line.isEmpty()) continue;
      String[] row = line.split(" ", -1);
      if (row.length == 2 && row[0].equals("N") && row[1].matches(CURSOR)) {
        next = new Entry("下一页…", true, 0, folder + "@" + row[1], "", "", "");
        continue;
      }
      if (row.length != 4
          || !(row[0].equals("D") || row[0].equals("F"))
          || !row[1].matches(COMPONENT)
          || !row[2].matches("[0-9]{1,19}")
          || !row[3].matches(STAMP)) throw new IOException("下载目录清单无法核对。");
      String hex = row[1], name = displayName(hex);
      boolean dir = row[0].equals("D");
      String child = dir ? (folder.isEmpty() ? hex : folder + "/" + hex) : "";
      result.add(new Entry(name, dir, Long.parseLong(row[2]), child, folder, hex, row[3]));
    }
    result.sort(
        (a, b) ->
            a.directory != b.directory
                ? (a.directory ? -1 : 1)
                : a.name.compareToIgnoreCase(b.name));
    if (next != null) result.add(next);
    return result;
  }

  private static String displayName(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++)
      bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    String name;
    try {
      name = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      return "文件名字节 " + hex;
    }
    // Preserve distinctions even for filenames containing reversals, line breaks or backslashes.
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < name.length(); ) {
      int code = name.codePointAt(i);
      i += Character.charCount(code);
      if (code == '\\') text.append("\\\\");
      else if (Character.isISOControl(code)
          || Character.getType(code) == Character.FORMAT
          || code == 0x2028
          || code == 0x2029) text.append("\\u{").append(Integer.toHexString(code)).append('}');
      else text.appendCodePoint(code);
    }
    return text.toString();
  }

  private static String command(Context context, String mode, String parent) throws IOException {
    File tool = new File(context.getApplicationInfo().nativeLibraryDir, "libfallenrootfile.so");
    if (!tool.isFile()) throw new IOException("当前架构缺少下载目录后端文件工具。");
    String executable = "'" + tool.getAbsolutePath().replace("'", "'\\''") + "'";
    return executable
        + " "
        + mode
        + " "
        + (Process.myUid() / 100000)
        + " "
        + (parent.isEmpty() ? "-" : parent);
  }

  static String evict(Context context, Entry entry) throws Exception {
    if (entry == null || entry.directory) throw new IOException("请重新选择一个普通文件。");
    String raw =
        Root.run(
            command(context, "evict", entry.parent) + " " + entry.hex + " " + entry.identity, 60);
    String[] row = raw.split(" ");
    if (row.length != 6 || !row[0].equals("DONE")) throw new IOException("没有收到完整的文件回收回执。");
    try {
      long size = Long.parseLong(row[1]),
          page = Long.parseLong(row[2]),
          span = Long.parseLong(row[3]);
      long before = Long.parseLong(row[4]), after = Long.parseLong(row[5]);
      if (size != entry.size
          || page <= 0
          || span < 0
          || span > size
          || before < 0
          || after < -1
          || before > (span + page - 1) / page
          || after > (span + page - 1) / page) throw new NumberFormatException();
      String headline = entry.displayPath() + " · " + MainActivity.fmt(size);
      if (size == 0) return headline + "\n空文件，无需逐出，驻留 0 页。";
      String coverage = span == size ? "整个文件" : "仅开头 " + MainActivity.fmt(span);
      if (after < 0) return headline + "\n已向磁盘后端文件提交逐出建议；文件或可读计数已变化，前后不可比较。";
      long delta = before - after;
      return headline
          + "\n已向整个后端文件提交逐出建议。"
          + coverage
          + "的驻留页 "
          + before
          + " → "
          + after
          + "，观测到的驻留"
          + (delta < 0 ? "增加 " : "减少 ")
          + MainActivity.fmt(Math.abs(delta) * page)
          + "。再次读取会重建缓存。内核可能异步写回已有的脏页。";
    } catch (NumberFormatException e) {
      throw new IOException("文件驻留读数无法核对。", e);
    }
  }
}

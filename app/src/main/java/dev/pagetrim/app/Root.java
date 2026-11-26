package dev.pagetrim.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class Root {
  private Root() {}

  // Only package-private callers may supply commands. The UI never accepts shell input.
  static String run(String command, int timeoutSeconds) throws Exception {
    final Process process;
    try {
      process =
          new ProcessBuilder("su", "-c", "[ \"$(id -u)\" = 0 ] || exit 77\n" + command)
              .redirectErrorStream(true)
              .start();
    } catch (IOException e) {
      throw new IOException("未找到可用的 Root 管理器。", e);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    boolean[] overflow = {false};
    IOException[] readFailure = {null};
    Thread reader =
        new Thread(
            () -> {
              try (InputStream in = process.getInputStream()) {
                byte[] block = new byte[4096];
                int n;
                while ((n = in.read(block)) != -1) {
                  if (out.size() + n <= 1024 * 1024) out.write(block, 0, n);
                  else overflow[0] = true;
                }
              } catch (IOException e) {
                readFailure[0] = e;
              }
            },
            "pagetrim-root-output");
    reader.setDaemon(true);
    reader.start();
    try {
      process.getOutputStream().close();
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        throw new IOException("操作超时，请核对 KernelSU 授权和操作记录。");
      }
      reader.join(2000);
      if (reader.isAlive() || overflow[0] || readFailure[0] != null) {
        throw new IOException("未能完整读取系统结果。");
      }
      String text = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
      if (process.exitValue() != 0) {
        String detail = text.length() > 220 ? text.substring(0, 220) : text;
        if (process.exitValue() == 77) throw new IOException("未获得 Root 授权。");
        throw new IOException(detail.isEmpty() ? "系统未完成操作，请检查 Root 授权。" : "系统未完成操作。 " + detail);
      }
      return text;
    } finally {
      process.destroyForcibly();
    }
  }
}

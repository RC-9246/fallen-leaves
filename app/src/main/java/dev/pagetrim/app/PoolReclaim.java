package dev.pagetrim.app;

import android.content.Context;
import java.io.File;
import java.io.IOException;

final class PoolReclaim {
  private PoolReclaim() {}

  static final class Capabilities {
    final boolean zram, ashmem;
    final String zramDetail, ashmemDetail;
    Capabilities(boolean z, String zd, boolean a, String ad) {
      zram = z; zramDetail = zd; ashmem = a; ashmemDetail = ad;
    }
  }

  static Capabilities capabilities(Context context) throws Exception {
    ZramPools.Probe zram;
    try { zram = ZramPools.availability(); }
    catch (IOException e) { zram = new ZramPools.Probe(false, e.getMessage()); }
    String ashmem;
    try { ashmem = ashmem(context, false); }
    catch (IOException e) { ashmem = e.getMessage() == null ? "检测失败。" : e.getMessage(); }
    return new Capabilities(zram.supported, zram.detail,
        "READY".equals(ashmem), "READY".equals(ashmem) ? "自有测试页支持取消固定，已确认管理权限。执行时仍须驱动允许。" : ashmem);
  }

  static String purgeAshmem(Context context) throws Exception { return ashmem(context, true); }

  private static String ashmem(Context context, boolean purge) throws Exception {
    File tool = new File(context.getApplicationInfo().nativeLibraryDir, "libfallenashmem.so");
    if (!tool.isFile()) throw new IOException("此安装包没有适用于当前架构的 ashmem 工具。");
    String executable = "'" + tool.getAbsolutePath().replace("'", "'\\''") + "'";
    String output = Root.run(executable + (purge ? " purge" : " probe"), 45);
    if (!purge && output.equals("READY")) return output;
    if (purge && output.matches("SCANNED [0-9]+"))
      return "驱动已执行 ashmem 回收，返回扫描前可丢弃范围 " + output.substring(8)
          + " 页。该计数包含范围内可能未驻留的页，实际归还物理内存量不可由此确定。";
    if (output.equals("ALWAYS_PINNED")) throw new IOException("驱动在取消固定后仍报告页面已固定，此内核没有可用的 ashmem 可丢弃页回收。");
    if (output.equals("NO_CAP")) throw new IOException("当前 Root 环境缺少 ashmem 清理需要的 CAP_SYS_ADMIN。");
    if (output.equals("NO_DEVICE") || output.equals("ERROR OPEN 2"))
      throw new IOException("未找到可用的旧版 /dev/ashmem 字符设备。");
    throw new IOException("ashmem 操作不可用。驱动结果 " + output);
  }
}

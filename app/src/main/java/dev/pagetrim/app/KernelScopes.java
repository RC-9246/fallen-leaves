package dev.pagetrim.app;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Independent privileged controls; only packaged commands and typed choices cross the su boundary.
 */
final class KernelScopes {
  private KernelScopes() {}

  enum Key {
    DENTRY("目录项分配器空页", "请求 dentry 缓存归还整块空页。后续路径查询可能有额外分配。"),
    INODE("inode 分配器空页", "仅收缩 inode_cache 分配器。文件系统可使用其他专用 inode 缓存。"),
    ION_POOL("ION 普通系统堆闲置页", "只处理驱动公开的普通系统堆闲置池。设备可能很快补充该池。"),
    BINDER_POOL("Binder 空闲缓冲页", "在内核提供具名接口时，限量扫描 Binder 的空闲缓冲对象。"),
    KGSL_POOL("KGSL 空闲页池", "检测 GPU 空闲页池的专用收缩器。活跃 GPU 分配不能计入可清额度。"),
    GPU_PROCESS("进程 GPU 换出统计", "读取驱动提供的逐进程回收计数。GPU 前后台标记由系统生命周期管理。"),
    MEMCG("缓存应用内存组回收", "对经核验的独占叶组请求指定字节量，实际回收可能高于或低于请求。"),
    MGLRU("最老一代文件页", "从根内存组一个 NUMA 节点的最老代限量请求回收文件页，可能增加重读。"),
    DAMON("DAMON 冷区回收", "沿用系统年龄、水位和非零配额，试运行三秒后恢复；已运行时只采样。"),
    KSM("KSM 相同页合并", "按已有速率扫描已标记的私有匿名页三秒。新合并的页可保持共享。"),
    ZRAM_MARK_IDLE("标记整个 zRAM 设备为空闲", "将一个活跃 zRAM 设备的所有已存页标为空闲。标记本身释放 0 B，后续访问会更新状态。"),
    MMD_POLICY("mmd 管理及预取策略", "查看系统守护进程维护状态与已公开配置。应用切回预取需要系统协调。"),
    ZRAM_RECOMPRESS("zRAM 二次压缩", "对一个交换设备的难压缩且已标冷的页尝试二次压缩，使用已配置的第二算法。"),
    ZRAM_WRITEBACK("zRAM 冷页写回", "使用现成后备设备和已启用的小额写入预算。会增加闪存写入、切回时可能读盘。"),
    ZRAM_DEDUP("zRAM 去重配置", "读取压缩设备已有的去重设置和同值页统计。运行中的交换盘不作重建。"),
    VM_COMPACT("全机连续物理页规整", "迁移物理页以改善连续分配，可能造成停顿。效果按内核规整计数报告。"),
    LOCKED_CMA("锁定页与 CMA 预留", "读取锁定页、不可驱逐页和连续分配区，物理页可能有多个统计归属。");
    final String label, note;

    Key(String label, String note) {
      this.label = label;
      this.note = note;
    }
  }

  static final class Probe {
    final boolean actionable;
    final String detail;

    Probe(boolean actionable, String detail) {
      this.actionable = actionable;
      this.detail = detail;
    }
  }

  private static volatile String script;

  private static String source(Context context) throws IOException {
    String cached = script;
    if (cached != null) return cached;
    try (InputStream in = context.getAssets().open("kernel-scopes.sh");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] chunk = new byte[4096];
      int n;
      while ((n = in.read(chunk)) != -1) bytes.write(chunk, 0, n);
      script = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
      return script;
    }
  }

  private static String command(Context context, Key key, boolean execute) throws IOException {
    long page = Os.sysconf(OsConstants._SC_PAGESIZE);
    if (page <= 0 || page > 65536) throw new IOException("无法核验系统页大小。");
    return "scope="
        + key.name()
        + "\napply="
        + (execute ? 1 : 0)
        + "\npage="
        + page
        + "\n"
        + source(context);
  }

  static Probe probe(Context context, Key key) throws Exception {
    String raw = Root.run(command(context, key, false), 15);
    if (raw.startsWith("READY\n")) return new Probe(true, raw.substring(6));
    if (raw.startsWith("INFO\n")) return new Probe(false, raw.substring(5));
    throw new IOException("未能核验内核探测结果。");
  }

  static String run(Context context, Key key) throws Exception {
    if (key == Key.MEMCG || key == Key.GPU_PROCESS) throw new IOException("请先选择一个缓存应用。");
    return execute(command(context, key, true));
  }

  static String gpuStats(Context context, TargetReclaim.App target) throws Exception {
    TargetReclaim.validate(context, target);
    return execute(
        TargetReclaim.validatedPrelude(target) + "\n" + command(context, Key.GPU_PROCESS, true));
  }

  static String runMemcg(Context context, TargetReclaim.App target, int budgetMiB)
      throws Exception {
    if (budgetMiB < 1 || budgetMiB > 128) throw new IOException("内存组请求范围为 1 至 128 MiB。");
    TargetReclaim.validate(context, target);
    return execute(
        TargetReclaim.validatedPrelude(target)
            + "\nbudget="
            + (budgetMiB * 1048576L)
            + "\n"
            + command(context, Key.MEMCG, true));
  }

  private static String execute(String command) throws Exception {
    String raw = Root.run(command, 60);
    if (raw.startsWith("DONE\n")) return raw.substring(5);
    if (raw.startsWith("PARTIAL\n")) throw new IOException("部分完成。 " + raw.substring(8));
    if (raw.startsWith("INFO\n")) throw new IOException(raw.substring(5));
    throw new IOException("未获得可核验的回执。操作可能已提交。");
  }
}

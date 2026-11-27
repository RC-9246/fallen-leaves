package dev.pagetrim.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** All advanced scopes are manual, opt-in and kept separate from the service and tile. */
public final class ScopeActivity extends Activity {
  private enum Group {
    FILE("文件缓存", "选择一个本地文件，保留文件内容。"),
    TARGET("缓存应用", "需要 Root。选择应用、映射和本次预算。"),
    KERNEL("内核与驱动", "需要 Root。逐项读取本机能力，再决定是否执行。"),
    COOP("应用协作", "无需 Root。进入资源所有者的协作界面，由它管理自己的分配器、缓存和页面。"),
    REVIEW("设备复核与效果对照", "按本机逐项探测，保存动作前、动作后和切回后的读数。"),
    LIFE("应用生命周期", "需要 Root。额外确认退出、冻结、挂起或改变待机策略。");
    final String title, note;

    Group(String t, String n) {
      title = t;
      note = n;
    }
  }

  private enum Feature {
    INSPECT(Group.FILE, "查看文件驻留", "只读观察。同一文件最多统计开头 128 MiB；权限不足时标为未知。"),
    EVICT(Group.FILE, "逐出选中文件的缓存", "仅向内核建议逐出此文件的干净页，之后再读可能需要访问存储。"),
    SYNC(Group.FILE, "单独写回选中文件", "需要文档写授权，仅同步已有数据，不截断文件。可能增加存储 I/O。"),
    ROOT_FILE(Group.FILE, "真实下载目录文件页 · Root", "从当前用户的真实 Download 目录重新选择普通文件，直接对该文件描述符核验和逐出。"),
    RANGE_ANON(TargetReclaim.Mode.RANGE_ANON, "分段回收私有匿名页", "选择一个内核列出的私有匿名映射，分批尝试换出。"),
    RANGE_FILE(TargetReclaim.Mode.RANGE_FILE, "分段回收文件映射", "仅选择当前无脏页、匿名副本及交换页的可重读文件映射，每批重查。"),
    MARK_COLD(TargetReclaim.Mode.MARK_COLD, "标冷所选内存段", "通过支持的远程内存建议接口降低热度。标记本身可能释放零页。"),
    PAGEOUT(TargetReclaim.Mode.PAGEOUT, "逐出所选内存段", "请求现代内核对单个映射的一段主动逐出。"),
    ART_GC(TargetReclaim.Mode.ART_GC, "请求缓存应用 ART 回收", "Android 11 特定框架诊断路径可能触发 GC；堆内空位可能留给该应用复用。"),
    LAZY_FREE(TargetReclaim.Mode.LAZY_FREE, "读取可丢弃匿名页", "只读汇总选中进程的 LazyFree。单个聚合值不能定位可丢弃地址。"),
    DENTRY(KernelScopes.Key.DENTRY),
    INODE(KernelScopes.Key.INODE),
    F2FS_INODE(KernelScopes.Key.F2FS_INODE),
    EXT4_INODE(KernelScopes.Key.EXT4_INODE),
    ION_POOL(KernelScopes.Key.ION_POOL),
    BINDER_POOL(KernelScopes.Key.BINDER_POOL),
    KGSL_POOL(KernelScopes.Key.KGSL_POOL),
    GPU_PROCESS(KernelScopes.Key.GPU_PROCESS),
    MEMCG(KernelScopes.Key.MEMCG),
    MGLRU(KernelScopes.Key.MGLRU),
    DAMON(KernelScopes.Key.DAMON),
    KSM(KernelScopes.Key.KSM),
    ZRAM_RECOMPRESS(KernelScopes.Key.ZRAM_RECOMPRESS),
    ZRAM_WRITEBACK(KernelScopes.Key.ZRAM_WRITEBACK),
    ZRAM_DEDUP(KernelScopes.Key.ZRAM_DEDUP),
    ZRAM_MARK_IDLE(KernelScopes.Key.ZRAM_MARK_IDLE),
    MMD_POLICY(KernelScopes.Key.MMD_POLICY),
    VM_COMPACT(KernelScopes.Key.VM_COMPACT),
    LOCKED_CMA(KernelScopes.Key.LOCKED_CMA),
    MANAGED_GC(Cooperative.Scope.MANAGED_GC, "所有者主动 GC", "由落叶本身或已接入的目标应用主动请求托管堆回收。"),
    NATIVE_ALLOCATOR(Cooperative.Scope.NATIVE_ALLOCATOR, "归还原生分配器空页", "让实际拥有分配器的应用发起请求。"),
    OBJECT_POOL(Cooperative.Scope.OBJECT_POOL, "释放应用对象池", "仅由应用释放确认可重建的对象。"),
    IMAGE_CACHE(Cooperative.Scope.IMAGE_CACHE, "释放图片内存缓存", "由资源所有者选择可丢弃的图片和解码缓存。"),
    IMAGE_READER_BUFFERS(Cooperative.Scope.IMAGE_READER_BUFFERS, "释放图像队列空闲缓冲", "需 Android 9 及接入的资源所有者。保留正在使用和已排队的图像。"),
    GPU_RESOURCES(Cooperative.Scope.GPU_RESOURCES, "释放可重建的图形资源", "由宿主核验未被使用的纹理和图形资源，并负责重建。"),
    SQLITE_CACHE(Cooperative.Scope.SQLITE_CACHE, "释放 SQLite 内存", "由本进程或合作应用向自己的 SQLite 请求释放缓存。"),
    DISCARDABLE_PRIVATE(Cooperative.Scope.DISCARDABLE_PRIVATE, "归还已作废的私有页", "应用确认内容作废后，可主动放弃内存。"),
    ACTIVITY_INSTANCE(Cooperative.Scope.ACTIVITY_INSTANCE, "释放已离屏页面", "由宿主核对页面已保存并可重建。"),
    BROWSER_TAB(Cooperative.Scope.BROWSER_TAB, "卸载浏览器标签页", "需要浏览器提供标签选择和恢复功能，重载可能丢失临时状态。"),
    WEBVIEW_RENDERER(
        Cooperative.Scope.WEBVIEW_RENDERER, "释放 WebView 渲染器", "宿主需处理渲染器退出以及受影响的所有 WebView。"),
    PRIVATE_WORKER_EXIT(Cooperative.Scope.PRIVATE_WORKER_EXIT, "退出独占后台子进程", "由应用确认子进程归属、依赖和可恢复性。"),
    SAVE_SESSION(Cooperative.Scope.SAVE_SESSION, "保存应用会话", "由资源所有者保存可恢复状态；外部 GPU 和服务资源需要宿主支持。"),
    RESTORE_SESSION(Cooperative.Scope.RESTORE_SESSION, "恢复应用会话", "由同一所有者恢复它保存的会话。"),
    AUDIT(Group.REVIEW, "复核内核接口", "读取设备当前状态并列出每一项支持条件。"),
    SNAPSHOT(Group.REVIEW, "对照三阶段运存", "手动采集动作前、动作后和切回后的整机运存、换页、缺页及可用的 PSI。"),
    FRAMES(Group.REVIEW, "查看应用帧时长", "可查看近期 HWUI 帧或只统计标记起点后绘制的帧，显示中位数和 P95。"),
    FORCE_STOP(Group.LIFE, "强行停止缓存应用", "会影响后台服务与通知；再次手动启动前，包可能保持停止状态。"),
    FREEZE(Group.LIFE, "短暂冻结缓存进程", "在活动管理器支持时，向选中的单进程应用提交两秒非黏性冻结和解除请求。"),
    SUSPEND(Group.LIFE, "挂起选定应用", "Android 将限制该应用的启动与通知，直到手动解除挂起。恢复入口始终可用。"),
    STANDBY(Group.LIFE, "设为罕用待机组", "只改变选定缓存应用的后台策略。原分组会先保存，恢复入口始终可用。");
    final Group group;
    final String title, note;
    final TargetReclaim.Mode target;
    final KernelScopes.Key kernel;
    final Cooperative.Scope coop;

    Feature(Group g, String t, String n) {
      this(g, t, n, null, null, null);
    }

    Feature(TargetReclaim.Mode m, String t, String n) {
      this(Group.TARGET, t, n, m, null, null);
    }

    Feature(KernelScopes.Key k) {
      this(Group.KERNEL, k.label, k.note, null, k, null);
    }

    Feature(Cooperative.Scope c, String t, String n) {
      this(Group.COOP, t, n, null, null, c);
    }

    Feature(
        Group g,
        String t,
        String n,
        TargetReclaim.Mode m,
        KernelScopes.Key k,
        Cooperative.Scope c) {
      group = g;
      title = t;
      note = n;
      target = m;
      kernel = k;
      coop = c;
    }
  }

  private static final int DOCUMENT = 443, OWNER = 444;
  private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
  private static final AtomicBoolean BUSY = new AtomicBoolean();
  private SharedPreferences prefs;
  private LinearLayout body;
  private ScrollView scroll;
  private TextView progress;
  private Group group;
  private Feature pending;
  private String nonce, pkg, signer;
  private boolean working;
  private int scrollY;

  @Override
  protected void attachBaseContext(Context c) {
    super.attachBaseContext(c);
    AppAppearance.applyThemeOverride(this, c);
  }

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("advanced_scopes_v1", MODE_PRIVATE);
    if (b != null) {
      String g = b.getString("group"), f = b.getString("pending");
      if (g != null) group = Group.valueOf(g);
      if (f != null) pending = Feature.valueOf(f);
      nonce = b.getString("nonce");
      pkg = b.getString("pkg");
      signer = b.getString("signer");
      scrollY = b.getInt("position");
    }
    draw();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    if (group != null) b.putString("group", group.name());
    if (pending != null) b.putString("pending", pending.name());
    b.putString("nonce", nonce);
    b.putString("pkg", pkg);
    b.putString("signer", signer);
    b.putInt("position", scroll.getScrollY());
  }

  @Override
  public void onBackPressed() {
    if (group == null) super.onBackPressed();
    else {
      group = null;
      scrollY = 0;
      draw();
    }
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private TextView text(String value, int size, boolean bold) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(getColor(bold ? R.color.leaf_text : R.color.leaf_secondary));
    if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return v;
  }

  private Button button(String label, View.OnClickListener listener) {
    Button v = new Button(this);
    v.setText(label);
    v.setAllCaps(false);
    v.setOnClickListener(listener);
    return v;
  }

  private void draw() {
    scroll = new ScrollView(this);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(18), dp(14), dp(18), dp(24));
    scroll.addView(body);
    setContentView(scroll);
    body.addView(button(group == null ? "返回运存" : "返回功能分类", v -> onBackPressed()));
    body.addView(text(group == null ? "高级回收与设备复核" : group.title, 24, true));
    progress = text("", 14, false);
    body.addView(progress);
    if (group != null) body.addView(text(group.note, 15, false));
    if (group == null) {
      body.addView(text("所有新增开关默认关闭。打开后仍需手动选择并执行。", 15, false));
      for (Group g : Group.values()) {
        Button b =
            button(
                g.title,
                v -> {
                  group = g;
                  scrollY = 0;
                  draw();
                });
        body.addView(b);
        body.addView(text(g.note, 14, false));
      }
    } else {
      if (group == Group.LIFE) {
        body.addView(button("恢复待机分组", v -> restore(false)));
        body.addView(button("解除应用挂起", v -> restore(true)));
      }
      for (Feature f : Feature.values()) if (f.group == group) row(f);
    }
    final int y = scrollY;
    scroll.post(() -> scroll.scrollTo(0, y));
  }

  private boolean enabled(Feature f) {
    return prefs.getBoolean(f.name(), false);
  }

  private void row(Feature f) {
    Switch s = new Switch(this);
    s.setMinHeight(dp(48));
    s.setText(f.title);
    s.setChecked(enabled(f));
    body.addView(s);
    body.addView(text(f.note, 14, false));
    Button action =
        button(
            f.kernel != null
                ? "检测接口"
                : f.group == Group.COOP
                    ? "选择资源所有者"
                    : f.group == Group.FILE ? "选择本地文件" : f.group == Group.REVIEW ? "打开" : "选择缓存应用",
            v -> begin(f));
    action.setContentDescription(f.title + " · " + action.getText());
    action.setEnabled(s.isChecked());
    body.addView(action);
    s.setOnCheckedChangeListener(
        (v, on) -> {
          prefs.edit().putBoolean(f.name(), on).apply();
          action.setEnabled(on);
        });
  }

  private void message(String title, String detail) {
    if (!isFinishing() && !isDestroyed())
      new AlertDialog.Builder(this)
          .setTitle(title)
          .setMessage(detail)
          .setPositiveButton("完成", null)
          .show();
  }

  private void require(Feature f) throws IOException {
    if (!enabled(f)) throw new IOException("此范围已关闭。");
  }

  private interface Done<T> {
    void accept(T value);
  }

  private <T> void work(String name, Callable<T> job, Done<T> done) {
    if (!BUSY.compareAndSet(false, true)) {
      Toast.makeText(this, "请等待当前任务完成", Toast.LENGTH_SHORT).show();
      return;
    }
    working = true;
    progress.setText(name + "…");
    WORK.execute(
        () -> {
          T result = null;
          String problem = null;
          try {
            result = job.call();
          } catch (Exception ex) {
            problem = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
          }
          final T value = result;
          final String failure = problem;
          if (failure != null) History.add(getApplicationContext(), name + "未完成", failure);
          BUSY.set(false);
          runOnUiThread(
              () -> {
                working = false;
                if (isFinishing() || isDestroyed()) return;
                progress.setText("");
                if (failure != null) message("操作未完成", failure);
                else done.accept(value);
              });
        });
  }

  private void confirm(Feature f, String target, Runnable action) {
    if (!enabled(f)) return;
    new AlertDialog.Builder(this)
        .setTitle(f.title)
        .setMessage(target + "\n\n" + f.note)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "执行",
            (d, v) -> {
              if (enabled(f)) action.run();
            })
        .show();
  }

  private void record(Feature f, Callable<String> op) {
    work(
        f.title,
        () -> {
          synchronized (RamController.class) {
            require(f);
            String result = op.call();
            History.add(getApplicationContext(), f.title, result);
            return result;
          }
        },
        r -> showResult(f, r));
  }

  private void showResult(Feature f, String detail) {
    if (AppAppearance.showResults(this)
        || f == Feature.INSPECT
        || f == Feature.LAZY_FREE
        || f.group == Group.REVIEW) message(f.title, detail);
    else {
      progress.setText(detail);
      Toast.makeText(this, "结果已保存至记录", Toast.LENGTH_SHORT).show();
    }
  }

  private void begin(Feature f) {
    if (!enabled(f) || working) return;
    if (f == Feature.ROOT_FILE) {
      chooseRootFile(f, "");
      return;
    }
    if (f.group == Group.REVIEW) {
      review(f);
      return;
    }
    if (f.group == Group.FILE) {
      try {
        pending = f;
        startActivityForResult(
            f == Feature.SYNC ? FilesScope.syncIntent() : FilesScope.pickIntent(), DOCUMENT);
      } catch (RuntimeException ex) {
        pending = null;
        message(f.title, "系统文件选择器不可用。");
      }
    } else if (f.kernel != null) {
      work(
          f.title,
          () -> KernelScopes.probe(this, f.kernel),
          p -> {
            if (!enabled(f)) return;
            AlertDialog.Builder d =
                new AlertDialog.Builder(this)
                    .setTitle(f.title)
                    .setMessage(p.detail + "\n\n" + f.note);
            if (p.actionable)
              d.setNegativeButton("取消", null)
                  .setPositiveButton(
                      (f == Feature.MEMCG || f == Feature.GPU_PROCESS) ? "选择缓存应用" : "执行",
                      (a, b) -> {
                        if (!enabled(f)) return;
                        if (f == Feature.MEMCG || f == Feature.GPU_PROCESS) chooseTarget(f);
                        else record(f, () -> KernelScopes.run(this, f.kernel));
                      });
            else d.setPositiveButton("完成", null);
            d.show();
          });
    } else if (f.coop != null) chooseOwner(f);
    else chooseTarget(f);
  }

  private void chooseRootFile(Feature f, String directory) {
    work(
        "读取实际下载目录",
        () -> RootFileCache.list(this, directory),
        entries -> {
          if (!enabled(f)) return;
          if (entries.isEmpty()) {
            message(f.title, "目录中没有符合条件的文件或子目录。");
            return;
          }
          String[] labels = new String[entries.size()];
          for (int i = 0; i < labels.length; i++) {
            RootFileCache.Entry entry = entries.get(i);
            labels[i] =
                entry.directory
                    ? "目录 · " + entry.name
                    : entry.name + " · " + MainActivity.fmt(entry.size);
          }
          new AlertDialog.Builder(this)
              .setTitle("当前用户的实际 Download 目录")
              .setItems(
                  labels,
                  (d, i) -> {
                    RootFileCache.Entry entry = entries.get(i);
                    if (entry.directory) chooseRootFile(f, entry.child);
                    else
                      confirm(
                          f,
                          entry.displayPath() + " · " + MainActivity.fmt(entry.size),
                          () -> record(f, () -> RootFileCache.evict(this, entry)));
                  })
              .setNegativeButton("取消", null)
              .show();
        });
  }

  private void review(Feature f) {
    if (f == Feature.AUDIT) {
      record(f, () -> DeviceReview.capabilities(this));
      return;
    }
    if (f == Feature.SNAPSHOT) {
      new AlertDialog.Builder(this)
          .setTitle(f.title)
          .setItems(
              new String[] {"动作前 · 开始一组", "动作后 · 与基线对照", "切回后 · 与动作后对照"},
              (d, i) -> record(f, () -> DeviceReview.snapshot(this, i)))
          .setNegativeButton("取消", null)
          .show();
      return;
    }
    work(
        "读取应用名单",
        () -> StorageController.list(this),
        apps -> {
          if (!enabled(f)) return;
          if (apps.isEmpty()) {
            message(f.title, "没有可选用户应用。");
            return;
          }
          String[] labels = new String[apps.size()];
          for (int i = 0; i < labels.length; i++)
            labels[i] = apps.get(i).label + " · " + apps.get(i).pkg;
          new AlertDialog.Builder(this)
              .setTitle(f.title)
              .setItems(
                  labels, (d, i) -> frameOptions(f, apps.get(i).pkg))
              .setNegativeButton("取消", null)
              .show();
        });
  }

  private void frameOptions(Feature feature, String pkg) {
    if (!enabled(feature)) return;
    new AlertDialog.Builder(this)
        .setTitle(feature.title)
        .setItems(new String[]{"查看近期帧", "标记本次起点", "查看起点后的新帧"}, (dialog, choice) ->
            record(feature, () -> {
              if (choice == 1) return DeviceReview.markFrames(this, pkg);
              if (choice == 2) return DeviceReview.frameStatsSince(this, pkg);
              return DeviceReview.frameStats(this, pkg);
            }))
        .setNegativeButton("取消", null).show();
  }

  private void chooseTarget(Feature f) {
    work(
        "读取缓存应用",
        () -> TargetReclaim.list(this),
        apps -> {
          if (!enabled(f)) return;
          if (apps.isEmpty()) {
            message(f.title, "当前没有可核验的缓存用户应用。可先使用目标应用并返回落叶。");
            return;
          }
          String[] labels = new String[apps.size()];
          for (int i = 0; i < labels.length; i++)
            labels[i] =
                apps.get(i).label
                    + " · "
                    + apps.get(i).pkg
                    + "\n"
                    + MainActivity.fmt(apps.get(i).rss)
                    + " RSS";
          new AlertDialog.Builder(this)
              .setTitle(f.title + " · 选择应用")
              .setItems(labels, (d, i) -> target(f, apps.get(i)))
              .setNegativeButton("取消", null)
              .show();
        });
  }

  private interface BudgetAction {
    void choose(int mib);
  }

  private void budget(Feature f, BudgetAction action) {
    int[] sizes = {4, 16, 32, 64};
    new AlertDialog.Builder(this)
        .setTitle("本次上限")
        .setItems(
            new String[] {"4 MiB", "16 MiB", "32 MiB", "64 MiB"},
            (d, i) -> {
              if (enabled(f)) action.choose(sizes[i]);
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void target(Feature f, TargetReclaim.App a) {
    if (!enabled(f)) return;
    if (f == Feature.GPU_PROCESS) {
      record(f, () -> KernelScopes.gpuStats(this, a));
      return;
    }
    if (f == Feature.MEMCG) {
      budget(
          f,
          n ->
              confirm(
                  f,
                  a.pkg + " · " + n + " MiB",
                  () -> record(f, () -> KernelScopes.runMemcg(this, a, n))));
      return;
    }
    if (f.target != null && TargetReclaim.isRange(f.target)) {
      work(
          "列出可选映射",
          () -> TargetReclaim.regions(this, a, f.target),
          regions -> {
            if (!enabled(f)) return;
            if (regions.isEmpty()) {
              message(f.title, "没有合格映射。");
              return;
            }
            String[] names = new String[regions.size()];
            for (int i = 0; i < names.length; i++) names[i] = regions.get(i).label;
            new AlertDialog.Builder(this)
                .setTitle("选择一个映射 · " + a.label)
                .setItems(
                    names,
                    (d, i) ->
                        budget(
                            f,
                            n -> {
                              TargetReclaim.Region r = regions.get(i);
                              confirm(
                                  f,
                                  a.pkg + "\n" + r.label + "\n最多 " + n + " MiB",
                                  () ->
                                      targetResult(
                                          f,
                                          a,
                                          () -> TargetReclaim.runRange(this, a, f.target, r, n)));
                            }))
                .setNegativeButton("取消", null)
                .show();
          });
      return;
    }
    Runnable go =
        () -> {
          if (f == Feature.FREEZE) record(f, () -> LifecycleScopes.freeze(this, a));
          else if (f == Feature.SUSPEND) record(f, () -> LifecycleScopes.suspend(this, a));
          else if (f == Feature.STANDBY) record(f, () -> LifecycleScopes.standby(this, a));
          else
            targetResult(
                f,
                a,
                () ->
                    TargetReclaim.run(
                        this,
                        a,
                        f == Feature.FORCE_STOP ? TargetReclaim.Mode.FORCE_STOP : f.target));
        };
    if (f == Feature.LAZY_FREE) go.run();
    else confirm(f, a.label + " · " + a.pkg, go);
  }

  private void targetResult(Feature f, TargetReclaim.App a, Callable<TargetReclaim.Result> op) {
    work(
        f.title,
        () -> {
          synchronized (RamController.class) {
            require(f);
            TargetReclaim.Result r = op.call();
            History.add(getApplicationContext(), f.title, a.pkg + "\n" + r.text);
            return r;
          }
        },
        r -> {
          if (r.partial) message("部分完成 · " + f.title, a.pkg + "\n" + r.text);
          else showResult(f, a.pkg + "\n" + r.text);
        });
  }

  private void restore(boolean suspension) {
    String title = suspension ? "解除应用挂起" : "恢复待机分组";
    List<String> apps =
        suspension ? LifecycleScopes.suspended(this) : LifecycleScopes.pending(this);
    if (apps.isEmpty()) {
      message(title, "没有待恢复的更改。");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(
            apps.toArray(new String[0]),
            (d, i) -> {
              String name = apps.get(i);
              work(
                  title,
                  () -> {
                    synchronized (RamController.class) {
                      String result =
                          suspension
                              ? LifecycleScopes.unsuspend(this, name)
                              : LifecycleScopes.restore(this, name);
                      History.add(this, title, result);
                      return result;
                    }
                  },
                  r -> message(title, r));
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void chooseOwner(Feature f) {
    work(
        "查找自愿接入的应用",
        () -> Cooperative.owners(this, f.coop),
        owners -> {
          if (!enabled(f)) return;
          if (owners.isEmpty()) {
            message(f.title, "本机没有应用声明支持这项协作操作。接入后的应用会自动出现在这里。");
            return;
          }
          String[] labels = new String[owners.size()];
          for (int i = 0; i < labels.length; i++) labels[i] = owners.get(i).title;
          new AlertDialog.Builder(this)
              .setTitle("选择资源所有者")
              .setItems(
                  labels,
                  (d, i) -> {
                    Cooperative.Owner o = owners.get(i);
                    confirm(
                        f,
                        o.title,
                        () -> {
                          if (o.component == null) {
                            record(f, () -> Cooperative.runLocal(f.coop));
                            return;
                          }
                          String id = UUID.randomUUID().toString();
                          work(
                              "核验协作入口",
                              () -> Cooperative.request(this, o, f.coop, id),
                              intent -> {
                                if (!enabled(f)) return;
                                pending = f;
                                nonce = id;
                                pkg = o.component.getPackageName();
                                signer = o.signer;
                                try {
                                  startActivityForResult(intent, OWNER);
                                } catch (RuntimeException ex) {
                                  clearPending();
                                  message(f.title, "协作入口无法打开。");
                                }
                              });
                        });
                  })
              .setNegativeButton("取消", null)
              .show();
        });
  }

  private void clearPending() {
    pending = null;
    nonce = null;
    pkg = null;
    signer = null;
  }

  @Override
  protected void onActivityResult(int code, int result, Intent data) {
    super.onActivityResult(code, result, data);
    if (code != DOCUMENT && code != OWNER) return;
    Feature f = pending;
    String id = nonce, owner = pkg, cert = signer;
    clearPending();
    if (result != RESULT_OK || f == null || !enabled(f) || data == null) return;
    if (code == DOCUMENT && f.group == Group.FILE) {
      Uri u = data.getData();
      if (u == null) return;
      Runnable run =
          () ->
              record(
                  f,
                  () ->
                      f == Feature.SYNC
                          ? FilesScope.runSync(this, u)
                          : FilesScope.run(this, u, f == Feature.EVICT));
      if (f == Feature.INSPECT) run.run();
      else confirm(f, "系统文件选择器中的选定文件", run);
    } else if (code == OWNER && f.coop != null && owner != null && id != null) {
      record(
          f,
          () -> {
            if (!id.equals(data.getStringExtra(Cooperative.NONCE))
                || !f.coop.name().equals(data.getStringExtra(Cooperative.SCOPE))
                || !cert.equals(Cooperative.signer(this, owner)))
              throw new IOException("无法核对协作回执。");
            String reply = data.getStringExtra("summary");
            if (reply == null || reply.trim().isEmpty()) throw new IOException("应用没有提供结果说明。");
            return "应用自报 · "
                + owner
                + "\n"
                + reply
                    .substring(0, Math.min(reply.length(), 450))
                    .replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
          });
    }
  }
}

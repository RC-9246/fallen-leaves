package dev.pagetrim.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private static final int BG = 0xfff5f7f4,
      WHITE = Color.WHITE,
      TEXT = 0xff152d28,
      SUB = 0xff526860,
      GREEN = 0xff17614e,
      LIGHT = 0xffdff0e6,
      LINE = 0xffd9e3db,
      DARK = 0xff153e35,
      MINT = 0xffb9efd1;
  private static final int MATCH = -1, WRAP = -2;
  private static final String[] TABS = {"运存", "存储", "记录"};
  private static final String[] MODES = {"文件页", "内核缓存", "两者"};
  private static final String[] ACTIONS = {"回收文件页缓存", "回收可释放的内核缓存", "一起回收两类缓存"};
  private static final String[] HINTS = {
    "回收内存中可重新读取的文件页。下次打开应用时可能需要重新读取。", "回收可释放的目录项和文件节点等内核对象。", "同时回收文件页和可释放的内核对象。应用内容可能需要重新载入。"
  };
  private static final AtomicBoolean ACTIVE = new AtomicBoolean();
  private static final ExecutorService JOBS = Executors.newSingleThreadExecutor();
  private static volatile boolean rootVerified;
  private static volatile String jobName = "";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private int tab, mode = 1;
  private LinearLayout body, nav;
  private TextView available,
      total,
      rate,
      cache,
      slab,
      swap,
      pressure,
      monitorNote,
      progress,
      rootButton;
  private Ring ring;
  private MemoryTrend.Samples trendSamples;
  private MemoryTrend trend;
  private boolean returnToRanking, rankByCache = true;
  private int pendingAlertThreshold;
  private boolean pendingRootMonitor;
  private static final int ALERT_PERMISSION = 71;
  private TextView modeHint, action;
  private LinearLayout modeRow;
  private boolean resumed;
  private String shownHistory = "";
  private final Runnable poll =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          update();
          handler.postDelayed(this, 2000);
        }
      };

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    Object retained = getLastNonConfigurationInstance();
    trendSamples = retained instanceof MemoryTrend.Samples
        ? (MemoryTrend.Samples) retained : new MemoryTrend.Samples();
    if (state != null) {
      tab = state.getInt("tab");
      mode = state.getInt("mode", 1);
      rankByCache = state.getBoolean("rankByCache", true);
      returnToRanking = state.getBoolean("returnToRanking");
      pendingAlertThreshold = state.getInt("pendingAlertThreshold");
      pendingRootMonitor = state.getBoolean("pendingRootMonitor");
    }
    draw();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    handler.removeCallbacks(poll);
    poll.run();
    if (returnToRanking) {
      returnToRanking = false;
      if (tab == 1 && StorageRanking.hasUsageAccess(this)) showRanking();
    }
  }

  @Override
  protected void onPause() {
    resumed = false;
    handler.removeCallbacks(poll);
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putInt("tab", tab);
    b.putInt("mode", mode);
    b.putBoolean("rankByCache", rankByCache);
    b.putBoolean("returnToRanking", returnToRanking);
    b.putInt("pendingAlertThreshold", pendingAlertThreshold);
    b.putBoolean("pendingRootMonitor", pendingRootMonitor);
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return trendSamples;
  }

  private int dp(float v) {
    return Math.round(getResources().getDisplayMetrics().density * v);
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private void pad(View v, int x, int y) {
    v.setPadding(dp(x), dp(y), dp(x), dp(y));
  }

  private void gap(LinearLayout l, int h) {
    l.addView(new View(this), 1, dp(h));
  }

  private void add(LinearLayout l, View v) {
    l.addView(v, new LinearLayout.LayoutParams(MATCH, WRAP));
  }

  private void flex(LinearLayout l, View v) {
    l.addView(v, new LinearLayout.LayoutParams(0, WRAP, 1));
  }

  private void line(LinearLayout l) {
    View bar = new View(this);
    bar.setBackgroundColor(LINE);
    l.addView(bar, MATCH, dp(1));
  }

  private GradientDrawable shape(int bg, int radius, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(bg);
    d.setCornerRadius(dp(radius));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  private TextView text(String s, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(color);
    t.setTextSize(size);
    t.setIncludeFontPadding(false);
    if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  private TextView button(String s, int bg, int fg, View.OnClickListener cb) {
    TextView t = text(s, 15, fg, true);
    t.setGravity(Gravity.CENTER);
    pad(t, 12, 8);
    t.setMinHeight(dp(48));
    t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33458870), shape(bg, 14, 0), null));
    t.setOnClickListener(cb);
    t.setFocusable(true);
    return t;
  }

  private LinearLayout card(int bg) {
    LinearLayout l = column();
    pad(l, 16, 16);
    l.setBackground(shape(bg, 20, bg == WHITE ? LINE : 0));
    return l;
  }

  private void draw() {
    trend = null;
    available = null;
    ring = null;
    monitorNote = null;
    LinearLayout screen = column();
    screen.setBackgroundColor(BG);
    LinearLayout header = row();
    pad(header, 20, 6);
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.ic_leaf);
    header.addView(icon, dp(27), dp(27));
    TextView brand = text(getString(R.string.app_name), 20, TEXT, true);
    pad(brand, 8, 0);
    flex(header, brand);
    rootButton = button("", LIGHT, GREEN, v -> verifyRoot());
    header.addView(rootButton);
    add(screen, header);
    progress = text("", 13, GREEN, true);
    pad(progress, 20, 5);
    add(screen, progress);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    body = column();
    pad(body, 18, 8);
    scroll.addView(body);
    screen.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
    if (tab == 0) memoryPage();
    else if (tab == 1) storagePage();
    else historyPage();
    gap(body, 14);
    line(screen);
    nav = row();
    nav.setBackgroundColor(WHITE);
    pad(nav, 12, 4);
    for (int i = 0; i < TABS.length; i++) {
      final int next = i;
      TextView n =
          button(
              TABS[i],
              i == tab ? LIGHT : WHITE,
              i == tab ? GREEN : SUB,
              v -> {
                if (tab != next) {
                  tab = next;
                  draw();
                }
              });
      n.setSelected(i == tab);
      flex(nav, n);
    }
    add(screen, nav);
    setContentView(screen);
    update();
  }

  private void heading(String title) {
    gap(body, 8);
    add(body, text(title, 27, TEXT, true));
    gap(body, 13);
  }

  private void memoryPage() {
    heading("运存概览");
    LinearLayout hero = card(DARK), top = row(), numbers = column();
    add(numbers, text("当前可用", 14, MINT, true));
    gap(numbers, 5);
    available = text("—", 33, WHITE, true);
    add(numbers, available);
    gap(numbers, 5);
    total = text("", 13, 0xffc0d9cb, false);
    add(numbers, total);
    flex(top, numbers);
    android.widget.FrameLayout graph = new android.widget.FrameLayout(this);
    ring = new Ring(this);
    graph.addView(ring);
    rate = text("", 14, MINT, true);
    rate.setGravity(Gravity.CENTER);
    graph.addView(rate);
    top.addView(graph, dp(75), dp(75));
    add(hero, top);
    gap(hero, 16);
    LinearLayout metrics = row();
    cache = metric(metrics, "文件页缓存");
    slab = metric(metrics, "可回收内核");
    swap = metric(metrics, "已用交换");
    add(hero, metrics);
    pressure = text("", 12, 0xffc0d9cb, false);
    add(hero, pressure);
    add(body, hero);
    gap(body, 12);
    LinearLayout chart = card(WHITE);
    add(chart, text("可用运存趋势", 18, TEXT, true));
    trend = new MemoryTrend(this, trendSamples);
    add(chart, trend);
    add(body, chart);
    gap(body, 12);
    LinearLayout extras = card(WHITE);
    monitorNote = rowAction(extras, "低运存监控", "", v -> monitor());
    add(body, extras);
    gap(body, 12);
    LinearLayout tools = card(WHITE);
    add(tools, text("手动回收 · Root", 18, TEXT, true));
    gap(tools, 10);
    modeRow = row();
    add(tools, modeRow);
    gap(tools, 8);
    modeHint = text("", 13, SUB, false);
    add(tools, modeHint);
    gap(tools, 10);
    action = button("", GREEN, WHITE, v -> reclaim());
    add(tools, action);
    add(body, tools);
    renderMode();
    line(tools);
    rowAction(tools, "后台应用", "使用 Root 请求指定缓存进程释放运存", v -> chooseProcess());
  }

  private TextView metric(LinearLayout parent, String name) {
    LinearLayout cell = column();
    TextView v = text("—", 15, WHITE, true);
    add(cell, v);
    gap(cell, 4);
    add(cell, text(name, 12, 0xffc0d9cb, false));
    flex(parent, cell);
    return v;
  }

  private void renderMode() {
    modeRow.removeAllViews();
    for (int i = 1; i <= 3; i++) {
      final int x = i;
      TextView t =
          button(
              MODES[i - 1],
              mode == i ? LIGHT : WHITE,
              mode == i ? GREEN : SUB,
              v -> {
                mode = x;
                renderMode();
              });
      t.setSelected(mode == i);
      flex(modeRow, t);
    }
    modeHint.setText(HINTS[mode - 1]);
    action.setText(ACTIONS[mode - 1]);
  }

  private TextView rowAction(
      LinearLayout parent, String title, String sub, View.OnClickListener op) {
    LinearLayout r = row();
    pad(r, 1, 8);
    r.setMinimumHeight(dp(54));
    LinearLayout info = column();
    add(info, text(title, 16, TEXT, true));
    gap(info, 4);
    TextView note = text(sub, 13, SUB, false);
    add(info, note);
    flex(r, info);
    TextView arrow = text("›", 23, GREEN, false);
    pad(arrow, 6, 0);
    r.addView(arrow);
    r.setOnClickListener(op);
    r.setFocusable(true);
    r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x163a6d55), null, null));
    add(parent, r);
    return note;
  }

  private void update() {
    if (rootButton == null) return;
    rootButton.setText(rootVerified ? "Root 已验证" : "检查 Root");
    progress.setVisibility(ACTIVE.get() ? View.VISIBLE : View.GONE);
    progress.setText(jobName);
    if (tab == 2) {
      if (!History.list(this).toString().equals(shownHistory)) {
        body.removeAllViews();
        historyPage();
        gap(body, 14);
      }
      return;
    }
    if (tab != 0 || available == null) return;
    MemStats m = MemStats.read(this);
    if (resumed && trend != null) trend.record(m);
    available.setText(fmt(m.available));
    total.setText("总运存  " + fmt(m.total));
    int used =
        m.total > 0 && m.available >= 0
            ? (int) Math.round(100.0 * (m.total - m.available) / m.total)
            : -1;
    rate.setText(used < 0 ? "—" : "已用\n" + used + "%");
    ring.value = used;
    ring.invalidate();
    cache.setText(fmt(m.cached));
    slab.setText(fmt(m.reclaimable));
    swap.setText(
        fmt(m.swapTotal >= 0 && m.swapFree >= 0 ? Math.max(0, m.swapTotal - m.swapFree) : -1));
    pressure.setVisibility(m.psiSome < 0 ? View.GONE : View.VISIBLE);
    if (m.psiSome >= 0) {
      pressure.setPadding(0, dp(12), 0, 0);
      pressure.setText(String.format(Locale.ROOT, "近 10 秒等待内存的时间占比  %.2f%%", m.psiSome));
    }
    if (monitorNote != null) {
      String status;
      switch (WatchService.mode()) {
        case ALERT_ONLY: status = "仅提醒 · 已开启，点此停止"; break;
        case ROOT_RECLAIM: status = "自动回收文件页 · 已开启，点此停止"; break;
        default: status = "已关闭 · 可选免 Root 提醒或 Root 自动回收";
      }
      monitorNote.setText(status);
    }
  }

  static String fmt(long n) {
    if (n < 0) return "—";
    if (n >= (1L << 30)) return String.format(Locale.ROOT, "%.2f GiB", n / (double) (1L << 30));
    if (n >= (1L << 20)) return String.format(Locale.ROOT, "%.1f MiB", n / (double) (1L << 20));
    if (n >= 1024) return String.format(Locale.ROOT, "%.0f KiB", n / 1024.0);
    return n + " B";
  }

  private void storagePage() {
    heading("存储清理");
    StatFs disk = new StatFs(Environment.getDataDirectory().getAbsolutePath());
    LinearLayout hero = card(DARK);
    add(hero, text("数据分区可用空间", 14, MINT, true));
    gap(hero, 6);
    add(hero, text(fmt(disk.getAvailableBytes()), 32, WHITE, true));
    gap(hero, 6);
    add(hero, text("总容量  " + fmt(disk.getTotalBytes()), 13, 0xffc0d9cb, false));
    add(body, hero);
    gap(body, 12);
    LinearLayout c = card(WHITE);
    rowAction(c, "应用占用排行", "按缓存或总占用排序，点选应用查看系统存储设置。", v -> showRanking());
    line(c);
    rowAction(c, "应用缓存", "直接选择应用，在系统存储页点“清除缓存”。", v -> chooseApp());
    line(c);
    rowAction(c, "历史诊断文件 · Root", "扫描满 7 天的崩溃记录、无响应追踪和指定厂商诊断日志。", v -> scanLogs());
    add(body, c);
    gap(body, 14);
    add(body, text("诊断扫描只访问指定的系统日志目录。下载、照片和聊天目录均不在扫描范围内。", 13, SUB, false));
  }

  private void historyPage() {
    heading("操作记录");
    JSONArray entries = History.list(this);
    shownHistory = entries.toString();
    if (entries.length() == 0) {
      LinearLayout c = card(WHITE);
      add(c, text("暂无操作记录", 17, TEXT, true));
      gap(c, 6);
      add(c, text("手动回收、阈值回收和诊断文件清理结果会保存在本机。", 14, SUB, false));
      add(body, c);
      return;
    }
    LinearLayout c = card(WHITE);
    for (int i = 0; i < entries.length(); i++) {
      JSONObject j = entries.optJSONObject(i);
      if (j == null) continue;
      if (i > 0) {
        gap(c, 10);
        line(c);
        gap(c, 10);
      }
      add(
          c,
          text(
              DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                  .format(new Date(j.optLong("time"))),
              12,
              SUB,
              false));
      gap(c, 4);
      add(c, text(j.optString("action"), 16, TEXT, true));
      gap(c, 3);
      add(c, text(j.optString("detail"), 14, SUB, false));
    }
    add(body, c);
  }

  private void message(String title, String detail) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(detail)
        .setPositiveButton("完成", null)
        .show();
  }

  private void verifyRoot() {
    work(
        "正在检查 Root",
        () -> Root.run("id -u", 20),
        v -> {
          rootVerified = "0".equals(v);
          update();
          message("Root 权限", rootVerified ? "当前授权可用。" : "未获取到 Root 权限。");
        });
  }

  private void reclaim() {
    final int selected = mode;
    final Context app = getApplicationContext();
    work(
        "正在回收运存",
        () -> {
          MemStats a = MemStats.read(app);
          RamController.reclaim(selected);
          rootVerified = true;
          Thread.sleep(400);
          MemStats b = MemStats.read(app);
          String result =
              "可用运存  "
                  + fmt(a.available)
                  + " → "
                  + fmt(b.available)
                  + "\n文件页缓存  "
                  + fmt(a.cached)
                  + " → "
                  + fmt(b.cached)
                  + "\n可回收内核  "
                  + fmt(a.reclaimable)
                  + " → "
                  + fmt(b.reclaimable);
          History.add(app, ACTIONS[selected - 1], result);
          return result;
        },
        v -> message("回收已执行", v));
  }

  private void monitor() {
    if (WatchService.isRunning()) {
      WatchService.stop(this);
      handler.postDelayed(this::update, 500);
      toast("已请求停止低运存监控");
      return;
    }
    LinearLayout options = column();
    pad(options, 22, 12);
    add(options, text("每 30 秒采样，连续 3 次低于阈值时触发。两次至少间隔 30 分钟。", 14, SUB, false));
    String[] actions = {"仅通知提醒 · 免 Root", "自动回收文件页 · Root"};
    Spinner behavior = new Spinner(this);
    ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, actions);
    behavior.setAdapter(choices);
    behavior.setContentDescription("低运存时的操作");
    add(options, behavior);
    RadioGroup group = new RadioGroup(this);
    for (int threshold : new int[] {5, 8, 10}) {
      RadioButton option = new RadioButton(this);
      option.setId(View.generateViewId());
      option.setTag(threshold);
      option.setText("可用运存低于 " + threshold + "%");
      group.addView(option);
      if (threshold == 8) group.check(option.getId());
    }
    add(options, group);
    new AlertDialog.Builder(this)
        .setTitle("低运存监控")
        .setView(options)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "开启",
            (dialog, which) -> {
              int threshold =
                  (Integer) group.findViewById(group.getCheckedRadioButtonId()).getTag();
              requestMonitor(threshold, behavior.getSelectedItemPosition() == 1);
            })
        .show();
  }

  private void requestMonitor(int threshold, boolean withRoot) {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      pendingAlertThreshold = threshold;
      pendingRootMonitor = withRoot;
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, ALERT_PERMISSION);
      return;
    }
    startMonitor(threshold, withRoot);
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code != ALERT_PERMISSION || pendingAlertThreshold == 0) return;
    int threshold = pendingAlertThreshold;
    pendingAlertThreshold = 0;
    if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
      startMonitor(threshold, pendingRootMonitor);
    } else {
      notificationSettings();
    }
  }

  private void notificationSettings() {
    new AlertDialog.Builder(this).setTitle("需要允许通知")
        .setMessage("在系统设置中启用落叶的通知以及对应的监控与提醒频道，然后重新开启监控。")
        .setNegativeButton("取消", null)
        .setPositiveButton("通知设置", (d, w) -> {
          try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
          } catch (RuntimeException e) { message("无法打开设置", reason(e)); }
        }).show();
  }

  private void startMonitor(int threshold, boolean withRoot) {
    WatchService.Mode selected = withRoot ? WatchService.Mode.ROOT_RECLAIM : WatchService.Mode.ALERT_ONLY;
    if (!WatchService.notificationsEnabled(this, selected)) {
      notificationSettings();
      return;
    }
    if (withRoot) {
      work("正在检查监控权限", () -> Root.run("id -u", 20), id -> {
        if (!"0".equals(id)) {
          message("监控未启动", "自动回收需要 Root 授权。也可以选择仅通知提醒。");
          return;
        }
        rootVerified = true;
        launchMonitor(threshold, selected);
      });
    } else {
      launchMonitor(threshold, selected);
    }
  }

  private void launchMonitor(int threshold, WatchService.Mode selected) {
    try {
      WatchService.start(this, threshold, selected);
      handler.postDelayed(this::update, 500);
      toast("已请求启动监控");
    } catch (RuntimeException e) { message("启动失败", reason(e)); }
  }

  private void chooseProcess() {
    work(
        "正在读取缓存进程",
        () -> ProcessTrim.list(getApplicationContext()),
        entries -> {
          if (entries.isEmpty()) {
            message("暂无可选进程", "当前未找到符合条件的用户应用缓存进程。");
            return;
          }
          String[] labels = new String[entries.size()];
          for (int i = 0; i < labels.length; i++) {
            ProcessTrim.Entry e = entries.get(i);
            labels[i] = e.label + " · " + fmt(e.rss) + " RSS\n" + e.process;
          }
          new AlertDialog.Builder(this)
              .setTitle("选择要通知的进程")
              .setItems(
                  labels,
                  (d, i) -> {
                    ProcessTrim.Entry e = entries.get(i);
                    work(
                        "正在请求应用释放运存",
                        () -> {
                          String result = ProcessTrim.trim(getApplicationContext(), e);
                          History.add(
                              getApplicationContext(), "应用内存回收请求", e.process + "\n" + result);
                          return result;
                        },
                        v -> message("系统返回", v));
                  })
              .setNegativeButton("取消", null)
              .show();
        });
  }

  private void showRanking() {
    if (!StorageRanking.hasUsageAccess(this)) {
      new AlertDialog.Builder(this).setTitle("允许读取应用占用")
          .setMessage("在系统的“使用情况访问权限”中允许落叶读取应用占用。排行在本机计算。也可以直接按名称选择应用。")
          .setPositiveButton("打开授权设置", (d, w) -> {
            try {
              returnToRanking = true;
              StorageRanking.openUsageAccessSettings(this);
            } catch (RuntimeException e) {
              returnToRanking = false;
              message("无法打开设置", reason(e));
            }
          }).setNeutralButton("按名称选择", (d, w) -> chooseApp()).setNegativeButton("取消", null).show();
      return;
    }
    work("正在统计应用占用", () -> StorageRanking.list(getApplicationContext()), this::displayRanking);
  }

  private void displayRanking(List<StorageRanking.Entry> entries) {
    if (entries.isEmpty()) {
      message("应用占用排行", "当前用户没有可列出的应用。");
      return;
    }
    StorageRanking.sort(entries, rankByCache);
    String[] rows = new String[entries.size()];
    for (int i = 0; i < entries.size(); i++) {
      StorageRanking.Entry e = entries.get(i);
      rows[i] = e.label + " · " + e.pkg + "\n" + (e.available
          ? "缓存 " + fmt(e.cacheBytes) + "　总占用 " + fmt(e.totalBytes)
          : "系统暂未提供占用统计");
    }
    new AlertDialog.Builder(this).setTitle(rankByCache ? "应用占用 · 按缓存排序" : "应用占用 · 按总占用排序")
        .setItems(rows, (d, i) -> {
          try {
            returnToRanking = true;
            StorageController.openSettings(this, entries.get(i).pkg);
          } catch (RuntimeException e) {
            returnToRanking = false;
            message("无法打开应用信息", reason(e));
          }
        }).setNeutralButton(rankByCache ? "按总占用排序" : "按缓存排序", (d, w) -> {
          rankByCache = !rankByCache;
          displayRanking(entries);
        }).setNegativeButton("关闭", null).show();
  }

  private void chooseApp() {
    work(
        "正在列出应用",
        () -> StorageController.list(getApplicationContext()),
        entries -> {
          if (entries.isEmpty()) {
            message("暂无可选应用", "未找到可打开存储设置的用户应用。");
            return;
          }
          String[] labels = new String[entries.size()];
          for (int i = 0; i < labels.length; i++)
            labels[i] = entries.get(i).label + "\n" + entries.get(i).pkg;
          new AlertDialog.Builder(this)
              .setTitle("选择应用")
              .setItems(
                  labels,
                  (d, i) -> {
                    try {
                      StorageController.openSettings(this, entries.get(i).pkg);
                    } catch (Exception e) {
                      message("无法打开应用信息", reason(e));
                    }
                  })
              .setNegativeButton("取消", null)
              .show();
        });
  }

  private void scanLogs() {
    work(
        "正在扫描历史诊断文件",
        Diagnostics::scan,
        items -> {
          if (items.isEmpty()) {
            message("扫描完成", "没有找到符合 7 天条件的诊断文件。");
            return;
          }
          String[] names = new String[items.size()];
          boolean[] chosen = new boolean[names.length];
          for (int i = 0; i < names.length; i++) {
            Diagnostics.Item f = items.get(i);
            names[i] =
                f.label()
                    + "\n"
                    + fmt(f.bytes)
                    + " · "
                    + DateFormat.getDateInstance().format(new Date(f.modified * 1000));
          }
          AlertDialog dialog =
              new AlertDialog.Builder(this)
                  .setTitle("选择诊断文件（" + names.length + "）")
                  .setMultiChoiceItems(names, chosen, (d, i, c) -> chosen[i] = c)
                  .setNeutralButton("全选", null)
                  .setNegativeButton("取消", null)
                  .setPositiveButton("继续", null)
                  .create();
          dialog.setOnShowListener(
              unused -> {
                dialog
                    .getButton(-3)
                    .setOnClickListener(
                        v -> {
                          for (int i = 0; i < chosen.length; i++) {
                            chosen[i] = true;
                            dialog.getListView().setItemChecked(i, true);
                          }
                        });
                dialog
                    .getButton(-1)
                    .setOnClickListener(
                        v -> {
                          List<Diagnostics.Item> selection = new ArrayList<>();
                          long bytes = 0;
                          for (int i = 0; i < chosen.length; i++)
                            if (chosen[i]) {
                              selection.add(items.get(i));
                              bytes += items.get(i).bytes;
                            }
                          if (selection.isEmpty()) {
                            toast("请先选择文件");
                            return;
                          }
                          dialog.dismiss();
                          new AlertDialog.Builder(this)
                              .setTitle("删除所选诊断文件？")
                              .setMessage(selection.size() + " 个文件，占用 " + fmt(bytes) + "。删除后不可恢复。")
                              .setNegativeButton("取消", null)
                              .setPositiveButton("删除", (d, w) -> deleteLogs(selection))
                              .show();
                        });
              });
          dialog.show();
        });
  }

  private void deleteLogs(List<Diagnostics.Item> selection) {
    work(
        "正在清理选中的诊断文件",
        () -> {
          Diagnostics.Result r = Diagnostics.remove(selection);
          String report =
              "已删除 " + r.deleted + " 个，原占用 " + fmt(r.bytes) + "。跳过 " + r.skipped + " 个。";
          History.add(getApplicationContext(), "诊断文件清理", report);
          return report;
        },
        v -> {
          draw();
          message("清理结果", v);
        });
  }

  private void toast(String msg) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
  }

  private static String reason(Throwable e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private interface Done<T> {
    void accept(T value);
  }

  private <T> void work(String title, Callable<T> task, Done<T> done) {
    if (!ACTIVE.compareAndSet(false, true)) {
      toast("请等待当前操作完成");
      return;
    }
    jobName = title;
    update();
    final Context app = getApplicationContext();
    JOBS.execute(
        () -> {
          try {
            T value = task.call();
            handler.post(
                () -> {
                  if (!isFinishing() && !isDestroyed()) done.accept(value);
                });
          } catch (Exception e) {
            String msg = reason(e);
            History.add(app, title.replaceFirst("^正在", "") + "失败", msg);
            handler.post(
                () -> {
                  if (!isFinishing() && !isDestroyed()) message("操作未完成", msg);
                });
          } finally {
            ACTIVE.set(false);
            handler.post(
                () -> {
                  if (!isFinishing() && !isDestroyed()) update();
                });
          }
        });
  }

  private final class Ring extends View {
    int value = -1;
    final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);

    Ring(Context c) {
      super(c);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth(), h = getHeight(), in = dp(5);
      pen.setStyle(Paint.Style.STROKE);
      pen.setStrokeWidth(dp(5));
      pen.setStrokeCap(Paint.Cap.ROUND);
      pen.setColor(0xff335e51);
      canvas.drawArc(in, in, w - in, h - in, -90, 360, false, pen);
      if (value > 0) {
        pen.setColor(MINT);
        canvas.drawArc(in, in, w - in, h - in, -90, 3.6f * value, false, pen);
      }
    }
  }
}

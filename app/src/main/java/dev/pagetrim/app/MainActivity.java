package dev.pagetrim.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
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
  private static final int MATCH = -1, WRAP = -2;
  private static final String[] TABS = {"运存", "存储", "记录"};
  private static final AtomicBoolean ACTIVE = new AtomicBoolean();
  private static final ExecutorService JOBS = Executors.newSingleThreadExecutor();
  private static volatile boolean rootVerified;
  private static volatile String jobName = "";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private int tab;
  private LinearLayout body, nav;
  private TextView monitorNote, progress, rootButton, runSelection;
  private MemoryDashboard dashboard;
  private AlertDialog watchDialog, manualDialog;
  private AlertDialog appearanceDialog;
  private WatchConfig pendingMonitor;
  private int confirmMask;
  private Switch askBeforeReclaim;
  private CheckBox skipFileConfirmation;
  private MemoryTrend.Samples trendSamples;
  private MemoryTrend trend;
  private boolean returnToRanking, rankByCache = true;
  private static final int ALERT_PERMISSION = 71;
  private boolean resumed;
  private String shownHistory = "";
  private final Runnable poll =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          update();
          handler.postDelayed(this, tab == 0 ? 1000 : 2000);
        }
      };

  @Override
  protected void attachBaseContext(Context base) {
    super.attachBaseContext(base);
    AppAppearance.applyThemeOverride(this, base);
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    Object retained = getLastNonConfigurationInstance();
    trendSamples = retained instanceof MemoryTrend.Samples
        ? (MemoryTrend.Samples) retained : new MemoryTrend.Samples();
    if (state != null) {
      tab = state.getInt("tab");
      rankByCache = state.getBoolean("rankByCache", true);
      returnToRanking = state.getBoolean("returnToRanking");
      Bundle config = state.getBundle("pendingMonitor");
      if (config != null) pendingMonitor = new WatchConfig(
          config.getBoolean("threshold"), config.getInt("used"), config.getBoolean("interval"),
          config.getInt("minutes"), config.getBoolean("root")
              ? WatchService.Mode.ROOT_RECLAIM : WatchService.Mode.ALERT_ONLY);
    }
    draw();
    if (state == null) {
      handleShortcut(getIntent());
    } else if (state.containsKey("appearanceDraft")) {
      appearanceDialog = AppAppearance.show(this, state.getBundle("appearanceDraft"));
    } else if (state.getBundle("watchEditor") != null) {
      openMonitor(state.getBundle("watchEditor"));
    } else if (state.containsKey("confirmMask")) {
      confirmReclaim(state.getInt("confirmMask"), true);
      if (skipFileConfirmation != null) {
        skipFileConfirmation.setChecked(state.getBoolean("skipFileConfirmation"));
      }
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleShortcut(intent);
  }

  private void handleShortcut(Intent intent) {
    if (intent == null) return;
    String shortcut = intent.getAction();
    if (!WatchService.ACTION_CONFIGURE.equals(shortcut)
        && !ManualReclaim.TILE_CONFIRM.equals(shortcut)) return;
    intent.setAction(null);
    if (tab != 0) {
      tab = 0;
      draw();
    }
    if (WatchService.ACTION_CONFIGURE.equals(shortcut)) {
      openMonitor(null);
    } else {
      // Any caller of the exported activity must receive an interactive confirmation.
      confirmReclaim(ManualReclaim.FILES, true);
    }
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
    b.putBoolean("rankByCache", rankByCache);
    b.putBoolean("returnToRanking", returnToRanking);
    if (appearanceDialog != null && appearanceDialog.isShowing()) {
      AppAppearance form = appearanceDialog.findViewById(R.id.appearance_form);
      b.putBundle("appearanceDraft", form.snapshot());
    }
    Bundle editor = WatchEditor.saveState(watchDialog);
    if (editor != null) b.putBundle("watchEditor", editor);
    if (manualDialog != null && manualDialog.isShowing()) {
      b.putInt("confirmMask", confirmMask);
      if (skipFileConfirmation != null) b.putBoolean("skipFileConfirmation", skipFileConfirmation.isChecked());
    }
    if (pendingMonitor != null) {
      Bundle config = new Bundle();
      config.putBoolean("threshold", pendingMonitor.thresholdEnabled);
      config.putInt("used", pendingMonitor.usedPercent);
      config.putBoolean("interval", pendingMonitor.intervalEnabled);
      config.putInt("minutes", pendingMonitor.intervalMinutes);
      config.putBoolean("root", pendingMonitor.mode == WatchService.Mode.ROOT_RECLAIM);
      b.putBundle("pendingMonitor", config);
    }
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
    bar.setBackgroundColor(getColor(R.color.leaf_rule));
    l.addView(bar, MATCH, dp(1));
  }

  private GradientDrawable shape(int backgroundRes, int radius, int strokeRes) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(getColor(backgroundRes));
    d.setCornerRadius(dp(radius));
    if (strokeRes != 0) d.setStroke(dp(1), getColor(strokeRes));
    return d;
  }

  private TextView text(String s, int size, int colorRes, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(getColor(colorRes));
    t.setTextSize(size);
    t.setIncludeFontPadding(false);
    if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  private TextView button(String s, int backgroundRes, int textRes, View.OnClickListener cb) {
    TextView t = text(s, 15, textRes, true);
    t.setGravity(Gravity.CENTER);
    pad(t, 12, 8);
    t.setMinHeight(dp(48));
    t.setBackground(new RippleDrawable(getColorStateList(R.color.leaf_ripple), shape(backgroundRes, 14, 0), null));
    t.setOnClickListener(cb);
    t.setFocusable(true);
    return t;
  }

  private LinearLayout card(int backgroundRes) {
    LinearLayout l = column();
    pad(l, 16, 16);
    l.setBackground(shape(backgroundRes, 20, backgroundRes == R.color.leaf_surface ? R.color.leaf_rule : 0));
    return l;
  }

  private void draw() {
    trend = null;
    dashboard = null;
    runSelection = null;
    askBeforeReclaim = null;
    monitorNote = null;
    LinearLayout screen = column();
    screen.setBackgroundColor(getColor(R.color.leaf_background));
    LinearLayout header = row();
    pad(header, 20, 6);
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.ic_leaf);
    header.addView(icon, dp(27), dp(27));
    TextView brand = text(getString(R.string.app_name), 20, R.color.leaf_text, true);
    pad(brand, 8, 0);
    flex(header, brand);
    rootButton = button("", R.color.leaf_soft, R.color.leaf_accent, v -> verifyRoot());
    header.addView(rootButton);
    TextView settings = button("设置", R.color.leaf_background, R.color.leaf_accent,
        v -> appearanceDialog = AppAppearance.show(this, null));
    header.addView(settings);
    add(screen, header);
    progress = text("", 13, R.color.leaf_accent, true);
    pad(progress, 20, 5);
    add(screen, progress);
    ScrollView scroll = new ScrollView(this);
    scroll.setId(R.id.content_scroll);
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
    nav.setBackgroundColor(getColor(R.color.leaf_surface));
    pad(nav, 12, 4);
    for (int i = 0; i < TABS.length; i++) {
      final int next = i;
      TextView n =
          button(
              TABS[i],
              i == tab ? R.color.leaf_soft : R.color.leaf_surface,
              i == tab ? R.color.leaf_accent : R.color.leaf_secondary,
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
    add(body, text(title, 27, R.color.leaf_text, true));
    gap(body, 13);
  }

  private void memoryPage() {
    heading("运存概览");
    dashboard = new MemoryDashboard(this);
    add(body, dashboard);
    gap(body, 12);
    LinearLayout chart = card(R.color.leaf_surface);
    add(chart, text("可用运存趋势", 18, R.color.leaf_text, true));
    trend = new MemoryTrend(this, trendSamples);
    add(chart, trend);
    add(body, chart);
    gap(body, 12);
    LinearLayout extras = card(R.color.leaf_surface);
    monitorNote = rowAction(extras, "自动监控", "", v -> openMonitor(null));
    add(body, extras);
    gap(body, 12);
    LinearLayout tools = card(R.color.leaf_surface);
    add(tools, text("手动回收 · Root", 18, R.color.leaf_text, true));
    gap(tools, 10);
    LinearLayout commands = row();
    runSelection = button("", R.color.leaf_accent, R.color.leaf_on_accent, v -> confirmReclaim(ManualReclaim.selection(this), false));
    flex(commands, runSelection);
    TextView single = button("单项 ▾", R.color.leaf_soft, R.color.leaf_accent, this::singleMenu);
    commands.addView(single);
    add(tools, commands);
    updateSelection();
    rowAction(tools, "选择默认组合", "主按钮使用此组合，单项操作只执行一次。", v -> chooseRegions());
    askBeforeReclaim = new Switch(this);
    askBeforeReclaim.setText("手动回收前确认");
    askBeforeReclaim.setMinHeight(dp(48));
    askBeforeReclaim.setChecked(ManualReclaim.confirm(this));
    askBeforeReclaim.setOnCheckedChangeListener((widget, enabled) -> {
      if (ManualReclaim.confirm(this) != enabled) ManualReclaim.setConfirm(this, enabled);
    });
    add(tools, askBeforeReclaim);
    add(tools, text("涉及内核对象的操作始终提示可能的停顿。", 13, R.color.leaf_secondary, false));
    line(tools);
    rowAction(tools, "后台应用", "使用 Root 请求指定缓存进程释放运存", v -> chooseProcess());
    add(body, tools);
  }

  private void updateSelection() {
    if (runSelection != null) runSelection.setText("回收" + ManualReclaim.label(ManualReclaim.selection(this)));
  }

  private void chooseRegions() {
    int current = ManualReclaim.selection(this);
    boolean[] checked = {(current & ManualReclaim.FILES) != 0, (current & ManualReclaim.OBJECTS) != 0};
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("默认回收组合")
        .setMultiChoiceItems(new String[] {ManualReclaim.label(1), ManualReclaim.label(2)},
            checked, (d, which, enabled) -> {
              checked[which] = enabled;
              ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE)
                  .setEnabled(checked[0] || checked[1]);
            })
        .setNegativeButton("取消", null)
        .setPositiveButton("保存", (d, w) -> {
          int mask = (checked[0] ? ManualReclaim.FILES : 0) | (checked[1] ? ManualReclaim.OBJECTS : 0);
          ManualReclaim.setSelection(this, mask);
          updateSelection();
        }).create();
    dialog.show();
  }

  private void singleMenu(View anchor) {
    PopupMenu menu = new PopupMenu(this, anchor);
    menu.getMenu().add(0, ManualReclaim.FILES, 0, ManualReclaim.label(ManualReclaim.FILES));
    menu.getMenu().add(0, ManualReclaim.OBJECTS, 1, ManualReclaim.label(ManualReclaim.OBJECTS));
    menu.setOnMenuItemClickListener(item -> {
      confirmReclaim(item.getItemId(), false);
      return true;
    });
    menu.show();
  }

  private void confirmReclaim(int mask, boolean alwaysAsk) {
    if (!alwaysAsk && mask == ManualReclaim.FILES && !ManualReclaim.confirm(this)) {
      reclaim(mask);
      return;
    }
    if (manualDialog != null && manualDialog.isShowing()) manualDialog.dismiss();
    confirmMask = mask;
    AlertDialog.Builder builder = new AlertDialog.Builder(this)
        .setTitle(mask == ManualReclaim.FILES ? "确认回收文件页？" : "此次回收可能造成停顿")
        .setMessage("本次范围：" + ManualReclaim.label(mask) + "。\n" + ManualReclaim.caution(mask))
        .setNegativeButton("取消", null);
    skipFileConfirmation = null;
    if (mask == ManualReclaim.FILES && ManualReclaim.confirm(this)) {
      LinearLayout options = new LinearLayout(builder.getContext());
      pad(options, 20, 0);
      skipFileConfirmation = new CheckBox(builder.getContext());
      skipFileConfirmation.setText("下次不再询问");
      skipFileConfirmation.setMinHeight(dp(48));
      add(options, skipFileConfirmation);
      builder.setView(options);
    }
    final CheckBox skip = skipFileConfirmation;
    manualDialog = builder.setPositiveButton("回收", (d, w) -> {
      if (skip != null && skip.isChecked()) {
        ManualReclaim.setConfirm(this, false);
        if (askBeforeReclaim != null) askBeforeReclaim.setChecked(false);
      }
      reclaim(mask);
    }).show();
  }

  private TextView rowAction(
      LinearLayout parent, String title, String sub, View.OnClickListener op) {
    LinearLayout r = row();
    pad(r, 1, 8);
    r.setMinimumHeight(dp(54));
    LinearLayout info = column();
    add(info, text(title, 16, R.color.leaf_text, true));
    gap(info, 4);
    TextView note = text(sub, 13, R.color.leaf_secondary, false);
    add(info, note);
    flex(r, info);
    TextView arrow = text("›", 23, R.color.leaf_accent, false);
    pad(arrow, 6, 0);
    r.addView(arrow);
    r.setOnClickListener(op);
    r.setFocusable(true);
    r.setBackground(new RippleDrawable(getColorStateList(R.color.leaf_ripple), null, null));
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
    if (tab != 0 || dashboard == null) return;
    MemStats m = MemStats.read(this);
    dashboard.update(m);
    if (resumed && trend != null) trend.record(m);
    String status;
    switch (WatchService.mode()) {
      case ALERT_ONLY: status = "仅提醒 · 运行中"; break;
      case ROOT_RECLAIM: status = "自动回收文件页 · 运行中"; break;
      default: status = "已关闭 · 可设置占用阈值和定时间隔";
    }
    if (!status.contentEquals(monitorNote.getText())) monitorNote.setText(status);
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
    LinearLayout hero = card(R.color.leaf_hero);
    add(hero, text("数据分区可用空间", 14, R.color.leaf_hero_label, true));
    gap(hero, 6);
    add(hero, text(fmt(disk.getAvailableBytes()), 32, R.color.leaf_hero_value, true));
    gap(hero, 6);
    add(hero, text("总容量  " + fmt(disk.getTotalBytes()), 13, R.color.leaf_hero_secondary, false));
    add(body, hero);
    gap(body, 12);
    LinearLayout c = card(R.color.leaf_surface);
    rowAction(c, "应用占用排行", "按缓存或总占用排序，点选应用查看系统存储设置。", v -> showRanking());
    line(c);
    rowAction(c, "应用缓存", "直接选择应用，在系统存储页点“清除缓存”。", v -> chooseApp());
    line(c);
    rowAction(c, "历史诊断文件 · Root", "扫描满 7 天的崩溃记录、无响应追踪和指定厂商诊断日志。", v -> scanLogs());
    add(body, c);
    gap(body, 14);
    add(body, text("诊断扫描只访问指定的系统日志目录。下载、照片和聊天目录均不在扫描范围内。", 13, R.color.leaf_secondary, false));
  }

  private void historyPage() {
    heading("操作记录");
    JSONArray entries = History.list(this);
    shownHistory = entries.toString();
    if (entries.length() == 0) {
      LinearLayout c = card(R.color.leaf_surface);
      add(c, text("暂无操作记录", 17, R.color.leaf_text, true));
      gap(c, 6);
      add(c, text("手动回收、自动提醒或回收、诊断文件清理的记录会保存在本机。", 14, R.color.leaf_secondary, false));
      add(body, c);
      return;
    }
    LinearLayout c = card(R.color.leaf_surface);
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
              R.color.leaf_secondary,
              false));
      gap(c, 4);
      add(c, text(j.optString("action"), 16, R.color.leaf_text, true));
      gap(c, 3);
      add(c, text(j.optString("detail"), 14, R.color.leaf_secondary, false));
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

  private void resultMessage(String title, String detail) {
    if (AppAppearance.showResults(this)) message(title, detail);
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

  private void reclaim(final int selected) {
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
          History.add(app, "回收" + ManualReclaim.label(selected), result);
          return result;
        },
        v -> resultMessage("回收已执行", v));
  }

  private void openMonitor(Bundle editorState) {
    if (watchDialog != null && watchDialog.isShowing()) return;
    watchDialog = WatchEditor.show(this, new WatchEditor.Callback() {
      @Override public void onSave(WatchConfig config) {
        if (!config.hasTriggers()) {
          config.save(MainActivity.this);
          stopMonitor();
        } else {
          requestMonitor(config);
        }
      }

      @Override public void onStop() { stopMonitor(); }
    }, editorState);
  }

  private void stopMonitor() {
    WatchService.stop(this);
    handler.postDelayed(this::update, 300);
    toast("监控已关闭");
  }

  private void requestMonitor(WatchConfig config) {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      pendingMonitor = config;
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, ALERT_PERMISSION);
      return;
    }
    startMonitor(config);
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code != ALERT_PERMISSION || pendingMonitor == null) return;
    WatchConfig config = pendingMonitor;
    pendingMonitor = null;
    if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
      startMonitor(config);
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

  private void startMonitor(WatchConfig config) {
    if (!WatchService.notificationsEnabled(this, config.mode)) {
      notificationSettings();
      return;
    }
    if (config.mode == WatchService.Mode.ROOT_RECLAIM) {
      work("正在检查监控权限", () -> Root.run("id -u", 20), id -> {
        if (!"0".equals(id)) {
          message("监控未启动", "自动回收需要 Root 授权。也可以选择仅通知提醒。");
          return;
        }
        rootVerified = true;
        launchMonitor(config);
      });
    } else {
      launchMonitor(config);
    }
  }

  private void launchMonitor(WatchConfig config) {
    try {
      WatchService.start(this, config);
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
                        v -> resultMessage("系统返回", v));
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
          Diagnostics.Result result = Diagnostics.remove(selection);
          History.add(getApplicationContext(), "诊断文件清理", diagnosticReport(result));
          return result;
        },
        result -> {
          draw();
          String report = diagnosticReport(result);
          if (result.skipped > 0) message("有诊断文件未清理", report);
          else resultMessage("清理结果", report);
        });
  }

  private static String diagnosticReport(Diagnostics.Result result) {
    return "已删除 " + result.deleted + " 个，原占用 " + fmt(result.bytes)
        + "。跳过 " + result.skipped + " 个。";
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

}

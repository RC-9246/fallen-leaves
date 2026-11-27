package dev.pagetrim.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.EnumMap;

final class ExtendedReclaimPanel {
  static final String TITLE = "扩展回收范围 · Root";

  private enum Scope {
    ANON("压缩应用匿名页", "选择应用并压缩", "尝试将缓存进程中可换出的堆页移入交换区。切回应用可能需要解压。", TargetReclaim.Mode.ANON),
    FILE("回收应用文件映射", "选择应用并回收", "尝试回收代码、库和资源的映射页，再次使用时可能需要重读。此厂商接口可能一并换出私有映射中已改写的页。需要只选可重读的干净映射时，可在高级回收中选择「分段回收文件映射」。", TargetReclaim.Mode.FILE),
    COMPACT("整理 zRAM 压缩池", "整理压缩池", "搬拢压缩数据并归还腾空的物理页。可整理的页数可能为零。", null),
    ASHMEM("回收可丢弃共享内存", "回收共享缓冲区", "回收应用已标记为可丢弃的 ashmem 缓冲区。应用再次使用时可能需要重建。", null),
    COLD("允许应用下次冷启动", "选择要结束的应用", "请求系统结束选中的缓存应用。切回时需要重新启动，未保存的内存状态可能丢失。", TargetReclaim.Mode.COLD);

    final String label, button, note;
    final TargetReclaim.Mode targetMode;

    Scope(String label, String button, String note, TargetReclaim.Mode targetMode) {
      this.label = label;
      this.button = button;
      this.note = note;
      this.targetMode = targetMode;
    }
  }

  private static final class Outcome {
    final String text;
    final boolean partial;

    Outcome(String text, boolean partial) {
      this.text = text;
      this.partial = partial;
    }
  }

  private final MainActivity activity;
  private final Context app;
  private final SharedPreferences prefs;
  private final EnumMap<Scope, TextView> results = new EnumMap<>(Scope.class);
  private final EnumMap<Scope, Button> actions = new EnumMap<>(Scope.class);
  private PoolReclaim.Capabilities capabilities;
  private AlertDialog dialog;

  ExtendedReclaimPanel(MainActivity activity) {
    this.activity = activity;
    app = activity.getApplicationContext();
    prefs = app.getSharedPreferences("extended_reclaim", Context.MODE_PRIVATE);
  }

  AlertDialog show() {
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    Context themed = builder.getContext();
    ScrollView scroll = new ScrollView(themed);
    LinearLayout body = new LinearLayout(themed);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20), dp(8), dp(20), dp(12));
    scroll.addView(body);
    Button advanced = new Button(themed);
    advanced.setText("高级回收与设备复核");
    advanced.setOnClickListener(v -> activity.startActivity(new android.content.Intent(activity, ScopeActivity.class)));
    body.addView(advanced);
    note(body, "下列五项需单独打开，并通过对应按钮执行。");
    heading(body, "保留缓存进程");
    addScope(body, Scope.ANON);
    addScope(body, Scope.FILE);
    heading(body, "结束缓存进程");
    addScope(body, Scope.COLD);
    heading(body, "系统共享内存池");
    Button probe = new Button(themed);
    probe.setText("检测内核接口");
    probe.setOnClickListener(v -> probePools());
    body.addView(probe);
    addScope(body, Scope.COMPACT);
    addScope(body, Scope.ASHMEM);
    heading(body, "已有清理范围");
    note(body, "这些开关也控制原有入口。关闭整机文件页回收同时限制自动回收和快捷磁贴。");
    for (LegacyScope.Scope scope : LegacyScope.Scope.values()) addLegacy(body, scope);
    dialog = builder.setTitle(TITLE).setView(scroll)
        .setPositiveButton("完成", null).show();
    return dialog;
  }

  private int dp(int value) {
    return Math.round(activity.getResources().getDisplayMetrics().density * value);
  }

  private TextView note(LinearLayout body, String text) {
    TextView note = new TextView(body.getContext());
    note.setTextColor(activity.getColor(R.color.leaf_secondary));
    note.setTextSize(14);
    note.setText(text);
    body.addView(note);
    return note;
  }

  private void heading(LinearLayout body, String title) {
    TextView heading = new TextView(body.getContext());
    heading.setText(title);
    heading.setPadding(0, dp(16), 0, dp(3));
    heading.setTextSize(16);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setTextColor(activity.getColor(R.color.leaf_accent));
    body.addView(heading);
  }

  private void addScope(LinearLayout body, Scope scope) {
    Context themed = body.getContext();
    Switch toggle = new Switch(themed);
    toggle.setText(scope.label);
    toggle.setMinHeight(dp(48));
    toggle.setChecked(enabled(scope));
    body.addView(toggle);
    note(body, scope.note);
    Button action = new Button(themed);
    action.setText(scope.button);
    action.setContentDescription(scope.button + " · " + scope.label);
    actions.put(scope, action);
    updateAction(scope);
    action.setOnClickListener(v -> {
      if (scope.targetMode != null) chooseTarget(scope);
      else confirm(scope, null);
    });
    body.addView(action);
    toggle.setOnCheckedChangeListener((v, checked) -> {
      prefs.edit().putBoolean(scope.name(), checked).apply();
      updateAction(scope);
    });
    TextView detail = note(body, "");
    detail.setTextIsSelectable(true);
    detail.setVisibility(View.GONE);
    results.put(scope, detail);
  }

  private void updateAction(Scope scope) {
    boolean supported = capabilities == null
        || ((scope != Scope.COMPACT || capabilities.zram) && (scope != Scope.ASHMEM || capabilities.ashmem));
    actions.get(scope).setEnabled(enabled(scope) && supported);
  }

  private void addLegacy(LinearLayout body, LegacyScope.Scope scope) {
    Switch toggle = new Switch(body.getContext());
    toggle.setText(scope.label);
    toggle.setMinHeight(dp(48));
    toggle.setChecked(LegacyScope.enabled(app, scope));
    toggle.setOnCheckedChangeListener((v, checked) -> LegacyScope.setEnabled(app, scope, checked));
    body.addView(toggle);
  }

  private boolean enabled(Scope scope) {
    return prefs.getBoolean(scope.name(), false);
  }

  private void chooseTarget(Scope scope) {
    activity.work("正在读取缓存应用", () -> TargetReclaim.list(app), apps -> {
      if (!dialog.isShowing() || !enabled(scope)) return;
      if (apps.isEmpty()) {
        activity.message("暂无可选应用", "先使用目标应用，再回到落叶。只有已进入缓存状态且可安全识别的用户应用才会列出。");
        return;
      }
      String[] items = new String[apps.size()];
      for (int i = 0; i < items.length; i++) {
        TargetReclaim.App target = apps.get(i);
        items[i] = target.label + " · " + MainActivity.fmt(target.rss) + " RSS\n"
            + target.pkg + " · " + target.processCount() + " 个进程";
      }
      new AlertDialog.Builder(activity).setTitle(scope.label + " · 选择应用")
          .setItems(items, (d, i) -> confirm(scope, apps.get(i)))
          .setNegativeButton("取消", null).show();
    });
  }

  private void confirm(Scope scope, TargetReclaim.App target) {
    if (!enabled(scope)) return;
    if (scope != Scope.COLD && !ManualReclaim.confirm(app)) {
      execute(scope, target);
      return;
    }
    String message = scope.note;
    if (target != null) message = target.label + "\n" + target.pkg + "\n\n" + message;
    new AlertDialog.Builder(activity)
        .setTitle(scope == Scope.COLD ? "结束此应用的缓存进程？" : scope.label)
        .setMessage(message)
        .setPositiveButton(scope == Scope.COLD ? "结束缓存进程" : "执行", (d, which) -> execute(scope, target))
        .setNegativeButton("取消", null).show();
  }

  private void execute(Scope scope, TargetReclaim.App target) {
    String action = scope == Scope.COLD ? "结束缓存应用" : scope.label;
    activity.work("正在" + action, () -> {
      Outcome outcome;
      // Keep this app's tile and automatic file reclaim out of the measurement interval.
      synchronized (RamController.class) {
        if (!enabled(scope)) throw new IllegalStateException("此清理范围已关闭。");
        if (scope.targetMode != null) {
          if (target == null) throw new IllegalStateException("请重新选择缓存应用。");
          TargetReclaim.Result result = TargetReclaim.run(app, target, scope.targetMode);
          outcome = new Outcome(target.label + " · " + target.pkg + "\n" + result.text, result.partial);
        } else if (scope == Scope.COMPACT) {
          ZramPools.Result result = ZramPools.compact();
          outcome = new Outcome(result.text, result.partial);
        } else {
          outcome = new Outcome(PoolReclaim.purgeAshmem(app), false);
        }
      }
      History.add(app, action, outcome.text);
      return outcome;
    }, outcome -> {
      if (dialog.isShowing()) {
        TextView detail = results.get(scope);
        detail.setText(outcome.text);
        detail.setVisibility(View.VISIBLE);
      }
      if (outcome.partial) activity.message("部分完成 · " + action, outcome.text);
      else activity.resultMessage(action, outcome.text);
    });
  }

  private void probePools() {
    activity.work("正在检测内核接口", () -> PoolReclaim.capabilities(app), caps -> {
      if (!dialog.isShowing()) return;
      capabilities = caps;
      updateAction(Scope.COMPACT);
      updateAction(Scope.ASHMEM);
      activity.message("内核接口", "zRAM 压缩池\n" + caps.zramDetail
          + "\n\nashmem 可丢弃共享内存\n" + caps.ashmemDetail);
    });
  }
}

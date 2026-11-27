package dev.pagetrim.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class AppReviewActivity extends Activity {
  private static final int DETAILS = 510;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private LinearLayout body;
  private TextView status;
  private Bundle pending;
  private boolean busy, visible, waitingForGrant, returningFromDetails;

  @Override protected void attachBaseContext(Context base) {
    super.attachBaseContext(base);
    AppAppearance.applyThemeOverride(this, base);
  }

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    setTitle("应用使用与后台管理");
    ScrollView scroll = new ScrollView(this);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    int pad = Math.round(getResources().getDisplayMetrics().density * 16);
    body.setPadding(pad, pad, pad, pad);
    scroll.addView(body);
    setContentView(scroll);
    control(AppReview.REVIEW, "使用与存储对照", "比较最近 90 天的应用使用记录和缓存占用。从系统应用信息返回后可查看缓存变化。",
        "查看使用与缓存", true, this::review);
    boolean supported = Build.VERSION.SDK_INT < 34;
    control(AppReview.END, "请求结束所选应用后台进程", supported
        ? "Android 决定哪些后台进程可以结束。若有进程结束，下次使用可能冷启动。"
        : "Android 14 起，普通应用只能请求结束自己的后台进程。",
        "选择一个应用", supported, this::selectTarget);
    status = new TextView(this);
    status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
    body.addView(status);
    if (state != null) {
      pending = state.getBundle("pending");
      waitingForGrant = state.getBoolean("waiting");
      returningFromDetails = state.getBoolean("returning");
      status.setText(state.getString("status", ""));
    }
  }

  private void control(String key, String title, String note, String action, boolean supported, Runnable callback) {
    Switch toggle = new Switch(this);
    toggle.setText(title);
    toggle.setChecked(supported && AppReview.enabled(this, key));
    toggle.setEnabled(supported);
    body.addView(toggle);
    TextView help = new TextView(this);
    help.setText(note);
    body.addView(help);
    Button button = new Button(this);
    button.setText(action);
    button.setEnabled(toggle.isChecked());
    body.addView(button);
    button.setOnClickListener(v -> callback.run());
    toggle.setOnCheckedChangeListener((v, on) -> {
      getSharedPreferences(AppReview.PREFS, MODE_PRIVATE).edit().putBoolean(key, on).apply();
      button.setEnabled(on);
    });
  }

  @Override protected void onResume() {
    super.onResume();
    visible = true;
    if (returningFromDetails) compare();
    if (waitingForGrant) {
      waitingForGrant = false;
      if (AppReview.enabled(this, AppReview.REVIEW) && StorageRanking.hasUsageAccess(this)) review();
    }
  }

  @Override protected void onPause() {
    visible = false;
    super.onPause();
  }

  @Override protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putBundle("pending", pending);
    out.putBoolean("waiting", waitingForGrant);
    out.putBoolean("returning", returningFromDetails);
    out.putString("status", busy ? "" : status.getText().toString());
  }

  @Override protected void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }

  private <T> void task(String progress, Callable<T> read, Consumer<T> finish) {
    if (busy) return;
    busy = true;
    status.setText(progress);
    worker.execute(() -> {
      try {
        T value = read.call();
        runOnUiThread(() -> {
          if (isDestroyed() || isFinishing()) return;
          busy = false;
          status.setText("");
          if (visible) {
            try { finish.accept(value); } catch (RuntimeException e) { failure(e); }
          }
        });
      } catch (Exception e) {
        runOnUiThread(() -> {
          if (isDestroyed() || isFinishing()) return;
          busy = false;
          failure(e);
        });
      }
    });
  }

  private void failure(Exception e) {
    status.setText(e.getMessage() == null ? "操作未完成，请刷新后重试。" : e.getMessage());
  }

  private void review() {
    if (!StorageRanking.hasUsageAccess(this)) {
      new AlertDialog.Builder(this).setTitle("允许读取使用记录与缓存")
          .setMessage("仅在打开此页并请求查询时，读取当前用户的应用使用时间和存储统计。")
          .setPositiveButton("打开授权设置", (d,w) -> {
            try {
              waitingForGrant = true;
              StorageRanking.openUsageAccessSettings(this);
            } catch (RuntimeException e) {
              waitingForGrant = false;
              failure(e);
            }
          }).setNegativeButton("取消", null).show();
      return;
    }
    task("正在读取应用使用与缓存统计…", () -> AppReview.review(this), rows -> showRecords(rows, 0));
  }

  private void showRecords(List<AppReview.Record> all, int filter) {
    AppReview.requireReview(this);
    long now = System.currentTimeMillis();
    List<AppReview.Record> filtered = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    DateFormat date = DateFormat.getDateInstance();
    for (AppReview.Record row : all) {
      if (!AppReview.matches(row.lastUsed, now, filter)) continue;
      filtered.add(row);
      String used = row.lastUsed > 0 ? "上次使用 " + date.format(new Date(row.lastUsed)) : "90 天内无可读使用记录";
      labels.add(row.storage.label + " · " + row.storage.pkg + "\n" + used + "　"
          + (row.storage.available ? "缓存 " + MainActivity.fmt(row.storage.cacheBytes) : "缓存未知"));
    }
    String[] titles = {"全部应用", "至少 7 天前使用", "至少 30 天前使用", "90 天内无记录"};
    int[] values = {0, 7, 30, -1};
    int selected = filter == 7 ? 1 : filter == 30 ? 2 : filter == -1 ? 3 : 0;
    AlertDialog.Builder list = new AlertDialog.Builder(this).setTitle(titles[selected] + " · 按缓存大小")
        .setNeutralButton("筛选", (d,w) -> new AlertDialog.Builder(this).setTitle("按上次使用时间筛选")
            .setItems(titles, (dlg,i) -> {
              if (StorageRanking.hasUsageAccess(this)) showRecords(all, values[i]);
              else review();
            }).setNegativeButton("取消",null).show())
        .setNegativeButton("关闭", null);
    if (filtered.isEmpty()) list.setMessage("当前筛选没有匹配项。");
    else list.setItems(labels.toArray(new String[0]), (d,i) -> inspect(filtered.get(i)));
    list.show();
  }

  private void inspect(AppReview.Record row) {
    if (!StorageRanking.hasUsageAccess(this)) { review(); return; }
    StorageRanking.Entry s = row.storage;
    new AlertDialog.Builder(this).setTitle(s.label)
        .setMessage(s.pkg + "\n" + (s.available ? "应用 " + MainActivity.fmt(s.appBytes)
            + "　数据（扣除缓存） " + MainActivity.fmt(s.dataBytes) + "　缓存 " + MainActivity.fmt(s.cacheBytes)
            : "存储统计暂不可用") + "\n可在系统应用信息中选择存储并管理缓存。")
        .setPositiveButton("打开系统应用信息", (d,w) -> openDetails(s))
        .setNegativeButton("取消", null).show();
  }

  private void openDetails(StorageRanking.Entry app) {
    task("正在记录当前缓存…", () -> {
      AppReview.requireReview(this);
      PackageInfo info = StorageController.checkedPackage(this, app.pkg);
      Bundle baseline = new Bundle();
      baseline.putString("pkg", app.pkg);
      baseline.putString("label", app.label);
      baseline.putInt("uid", info.applicationInfo.uid);
      baseline.putLong("installed", info.firstInstallTime);
      baseline.putLong("updated", info.lastUpdateTime);
      try { baseline.putLong("bytes", StorageRanking.readStats(this, app.pkg).getCacheBytes()); }
      catch (java.io.IOException ignored) { /* The system page still works without a reading. */ }
      return baseline;
    }, baseline -> {
      AppReview.requireReview(this);
      Intent details = StorageController.settingsIntent(this, app.pkg);
      startActivityForResult(details, DETAILS);
      pending = baseline;
    });
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != DETAILS || pending == null) return;
    returningFromDetails = true;
    if (visible) compare();
  }

  private void compare() {
    returningFromDetails = false;
    Bundle baseline = pending;
    if (baseline == null) return;
    pending = null;
    task("正在复查缓存占用…", () -> {
      AppReview.requireReview(this);
      String pkg = baseline.getString("pkg");
      PackageInfo info = StorageController.checkedPackage(this, pkg);
      if (baseline.getInt("uid") != info.applicationInfo.uid || baseline.getLong("installed") != info.firstInstallTime
          || baseline.getLong("updated") != info.lastUpdateTime) throw new IllegalStateException("应用已更换或更新，请重新查询。");
      long after = StorageRanking.readStats(this, pkg).getCacheBytes();
      String summary = baseline.getString("label") + " · " + pkg + "\n当前缓存 " + MainActivity.fmt(after);
      if (baseline.containsKey("bytes")) {
        long before = baseline.getLong("bytes");
        String change = before >= after ? "减少 " + MainActivity.fmt(before - after) : "增加 " + MainActivity.fmt(after - before);
        summary = baseline.getString("label") + " · " + pkg + "\n缓存 " + MainActivity.fmt(before)
            + " → " + MainActivity.fmt(after) + "（" + change + "）";
      }
      AppReview.requireReview(this);
      return summary;
    }, status::setText);
  }

  private void selectTarget() {
    task("正在列出可选用户应用…", () -> AppReview.targets(this), targets -> {
      if (!AppReview.enabled(this, AppReview.END)) return;
      if (targets.isEmpty()) { status.setText("没有符合条件的用户应用。"); return; }
      String[] names = new String[targets.size()];
      for (int i=0; i<names.length; i++) names[i] = targets.get(i).label + "\n" + targets.get(i).pkg;
      new AlertDialog.Builder(this).setTitle("选择应用 · 运行状态未知").setItems(names, (d,i) -> {
        AppReview.Target target = targets.get(i);
        new AlertDialog.Builder(this).setTitle("请求结束后台进程？")
            .setMessage(target.label + "\n" + target.pkg + "\n如果系统结束其后台进程，下次打开可能需要冷启动，未保存的内存状态可能丢失。")
            .setPositiveButton("接受并请求", (dlg,w) -> task("正在提交请求…", () -> AppReview.requestEnd(this,target), status::setText))
            .setNegativeButton("取消", null).show();
      }).setNegativeButton("取消", null).show();
    });
  }
}

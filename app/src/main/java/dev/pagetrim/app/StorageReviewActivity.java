package dev.pagetrim.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class StorageReviewActivity extends Activity {
  private static final int PICK = 1;
  private static final String PREFS = "shared_folder_cleanup";
  private Session session;
  private LinearLayout body;
  private Button remove;

  // Retain the one approved scan or deletion across rotation, without retaining an Activity.
  private static final class Session {
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    final Set<String> checked = new HashSet<>();
    StorageReviewActivity owner;
    Uri tree;
    CancellationSignal cancel;
    StorageReview.Scan scan;
    String status = "";

    void redraw() { if (owner != null) owner.draw(); }

    void run(Context context, EnumSet<StorageReviewPolicy.Kind> scopes, List<StorageReview.Item> items) {
      if (cancel != null) return;
      CancellationSignal operation = new CancellationSignal();
      Uri chosen = tree;
      cancel = operation;
      scan = null;
      checked.clear();
      status = items == null ? "正在读取文件夹…" : "正在复核并删除勾选的文件…";
      redraw();
      worker.execute(() -> {
        StorageReview.Scan scanned = null;
        String detail = "";
        try {
          if (items == null) scanned = StorageReview.scan(context, chosen, scopes, operation);
          else detail = StorageReview.delete(context, chosen, items, operation);
        } catch (Exception error) {
          String message = error.getMessage();
          detail = operation.isCanceled() ? "已取消。" : "未完成。" + (message == null ? "" : message);
        }
        if (items != null) History.add(context, "所选文件夹清理", detail);
        StorageReview.Scan result = scanned;
        String report = detail;
        main.post(() -> {
          cancel = null;
          scan = result;
          status = report;
          redraw();
        });
      });
    }
  }

  @Override protected void attachBaseContext(Context context) {
    super.attachBaseContext(context);
    AppAppearance.applyThemeOverride(this, context);
  }

  @Override protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    session = (Session) getLastNonConfigurationInstance();
    if (session == null) session = new Session();
    session.owner = this;
    draw();
  }

  @Override public Object onRetainNonConfigurationInstance() { return session; }

  @Override protected void onDestroy() {
    if (session.owner == this) session.owner = null;
    if (!isChangingConfigurations()) {
      if (session.cancel != null) session.cancel.cancel();
      session.worker.shutdown();
    }
    super.onDestroy();
  }

  private void pick() {
    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    try { startActivityForResult(picker, PICK); }
    catch (ActivityNotFoundException unavailable) {
      session.status = "系统文件夹选择器不可用。";
      draw();
    }
  }

  @Override protected void onActivityResult(int code, int result, Intent data) {
    super.onActivityResult(code, result, data);
    if (code == PICK && result == RESULT_OK && data != null && data.getData() != null) {
      session.tree = data.getData();
      session.run(getApplicationContext(), scopes(), null);
    }
  }

  private EnumSet<StorageReviewPolicy.Kind> scopes() {
    EnumSet<StorageReviewPolicy.Kind> enabled = EnumSet.noneOf(StorageReviewPolicy.Kind.class);
    for (StorageReviewPolicy.Kind kind : StorageReviewPolicy.Kind.values()) {
      if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(kind.name(), false)) enabled.add(kind);
    }
    return enabled;
  }

  private void toggle(String label, StorageReviewPolicy.Kind kind) {
    Switch control = new Switch(this);
    control.setText(label);
    control.setChecked(scopes().contains(kind));
    control.setEnabled(session.cancel == null);
    control.setOnCheckedChangeListener((button, value) -> {
      getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(kind.name(), value).apply();
      session.scan = null;
      session.checked.clear();
      session.status = session.tree == null ? "" : "范围已更改，请重新扫描。";
      draw();
    });
    body.addView(control);
  }

  private void label(String value, int size, boolean bold) {
    TextView text = new TextView(this);
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(getColor(bold ? R.color.leaf_text : R.color.leaf_secondary));
    if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    text.setPadding(0, dp(5), 0, dp(5));
    body.addView(text);
  }

  private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

  private Button button(String title, Runnable action, boolean enabled) {
    Button control = new Button(this);
    control.setText(title);
    control.setOnClickListener(v -> action.run());
    control.setEnabled(enabled);
    body.addView(control);
    return control;
  }

  private void draw() {
    boolean idle = session.cancel == null;
    ScrollView scroll = new ScrollView(this);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(18), dp(14), dp(18), dp(20));
    scroll.addView(body);
    setContentView(scroll);
    button("返回存储", this::finish, true);
    label("所选文件夹清理", 24, true);
    label("选择本机共享存储文件夹查看占用。可逐项清理 cache、caches、.cache、temp、tmp 目录中满 7 天的指定文件。", 15, false);
    toggle("列出临时文件（.tmp、.temp）", StorageReviewPolicy.Kind.TEMP);
    toggle("列出缓存文件（.cache）", StorageReviewPolicy.Kind.CACHE);
    button("选择文件夹", this::pick, idle);
    if (session.tree != null) {
      if (StorageReview.AUTHORITY.equals(session.tree.getAuthority()) && DocumentsContract.isTreeUri(session.tree))
        label("所选文件夹　" + DocumentsContract.getTreeDocumentId(session.tree), 14, false);
      button("重新扫描", () -> session.run(getApplicationContext(), scopes(), null), idle);
    }
    if (!session.status.isEmpty()) label(session.status, 15, false);
    StorageReview.Scan scan = session.scan;
    if (scan == null) return;
    label("已扫描 " + scan.files + " 个文件 · 已知大小 " + MainActivity.fmt(scan.bytes), 17, true);
    if (scan.unavailable > 0 || scan.unknownSize > 0 || scan.limited)
      label("未完整列出。跳过项 " + scan.unavailable + "，大小未知 " + scan.unknownSize
          + (scan.limited ? "。本次已达到扫描或候选预览上限，可选择较小的子文件夹。" : "。"), 14, false);
    if (!scan.largest.isEmpty()) {
      label("占用较大的文件", 17, true);
      for (StorageReview.Item item : scan.largest)
        label(MainActivity.fmt(item.size) + "　" + relative(item), 14, false);
    }
    label("可勾选清理 · " + scan.candidates.size() + " 项", 17, true);
    if (scan.candidates.isEmpty()) {
      label(scopes().isEmpty() ? "打开上方清理范围并扫描，即可查看候选文件。" : "未找到符合所选范围且可删除的文件。", 14, false);
      return;
    }
    label("请核对文件名和修改日期，选择确实用不到的文件。", 14, false);
    for (StorageReview.Item item : scan.candidates) {
      CheckBox check = new CheckBox(this);
      check.setText(relative(item) + "\n" + MainActivity.fmt(item.size) + "　"
          + DateFormat.getDateInstance().format(new Date(item.modified)));
      check.setChecked(session.checked.contains(item.id));
      check.setOnCheckedChangeListener((v, selected) -> {
        if (selected) session.checked.add(item.id);
        else session.checked.remove(item.id);
        updateRemove();
      });
      body.addView(check);
    }
    remove = button("", this::confirm, false);
    updateRemove();
  }

  private String relative(StorageReview.Item item) {
    return item.id.substring(DocumentsContract.getTreeDocumentId(session.tree).length() + 1);
  }

  private void updateRemove() {
    remove.setEnabled(!session.checked.isEmpty());
    remove.setText("删除勾选项（" + session.checked.size() + "）");
  }

  private void confirm() {
    if (session.cancel != null || session.scan == null) return;
    List<StorageReview.Item> items = new ArrayList<>();
    EnumSet<StorageReviewPolicy.Kind> enabled = scopes();
    long bytes = 0;
    for (StorageReview.Item item : session.scan.candidates) {
      if (enabled.contains(item.kind) && session.checked.contains(item.id)) {
        items.add(item);
        bytes += item.size;
      }
    }
    if (items.isEmpty()) return;
    new AlertDialog.Builder(this).setTitle("永久删除勾选的文件？")
        .setMessage("共 " + items.size() + " 个，合计 " + MainActivity.fmt(bytes) + "。删除后无法在落叶中撤销。")
        .setNegativeButton("取消", null)
        .setPositiveButton("删除", (d, w) -> session.run(getApplicationContext(), enabled, items)).show();
  }
}

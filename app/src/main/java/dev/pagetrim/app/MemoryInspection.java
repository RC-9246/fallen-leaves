package dev.pagetrim.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.pagetrim.app.PublicMemorySample.Section;

/** Reads diagnostics when the user opens, refreshes or changes the selection. */
final class MemoryInspection {
  private final Activity activity;
  private final AlertDialog dialog;
  private final TextView report;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final EnumMap<Section, Switch> switches = new EnumMap<>(Section.class);
  private final SharedPreferences preferences;

  static AlertDialog show(Activity activity) {
    MemoryInspection inspection = new MemoryInspection(activity);
    inspection.open();
    return inspection.dialog;
  }

  private MemoryInspection(Activity activity) {
    this.activity = activity;
    preferences = activity.getSharedPreferences("public_memory_sections", Context.MODE_PRIVATE);
    ScrollView scroll = new ScrollView(activity);
    LinearLayout content = new LinearLayout(activity);
    content.setOrientation(LinearLayout.VERTICAL);
    int inset = Math.round(18 * activity.getResources().getDisplayMetrics().density);
    content.setPadding(inset, inset / 2, inset, inset);
    scroll.addView(content);
    for (Section section : Section.values()) {
      Switch toggle = new Switch(activity);
      toggle.setText(section.label);
      toggle.setChecked(preferences.getBoolean(section.name(), true));
      content.addView(toggle);
      switches.put(section, toggle);
    }
    report = new TextView(activity);
    report.setPadding(0, inset, 0, 0);
    report.setTextIsSelectable(true);
    report.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    content.addView(report);
    dialog = new AlertDialog.Builder(activity).setTitle("免 Root 运存诊断").setView(scroll)
        .setPositiveButton("刷新", null).setNeutralButton("设为起点", null)
        .setNegativeButton("关闭", null).create();
  }

  private void open() {
    dialog.setOnDismissListener(ignored -> worker.shutdownNow());
    dialog.show();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> refresh(false));
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> refresh(true));
    for (Section section : Section.values()) {
      switches.get(section).setOnCheckedChangeListener((button, checked) -> {
        preferences.edit().putBoolean(section.name(), checked).apply();
        refresh(false);
      });
    }
    refresh(false);
  }

  private void refresh(boolean markStart) {
    EnumSet<Section> selected = EnumSet.noneOf(Section.class);
    for (Section section : Section.values()) {
      if (switches.get(section).isChecked()) selected.add(section);
    }
    Button refresh = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
    Button mark = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
    refresh.setEnabled(false);
    mark.setEnabled(false);
    if (selected.isEmpty()) {
      report.setText("请开启要查看的项目。");
      return;
    }
    for (Switch toggle : switches.values()) toggle.setEnabled(false);
    report.setText("正在读取…");
    Context context = activity.getApplicationContext();
    worker.execute(() -> {
      PublicMemorySample sample = PublicMemorySample.read(context, selected);
      String exits = selected.contains(Section.EXITS) ? exitsReport(context) : "";
      activity.runOnUiThread(() -> {
        if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
        if (markStart && !sample.values.isEmpty()) sample.saveBaseline(context);
        String result = sample.report(selected, sample.baseline(context));
        if (markStart) result = (sample.values.isEmpty() ? "本次未取得可比较的读数。" : "已设置比较起点。") + "\n" + result;
        if (!result.isEmpty() && !exits.isEmpty()) result += "\n\n";
        report.setText(result + exits);
        for (Switch toggle : switches.values()) toggle.setEnabled(true);
        mark.setEnabled(!sample.values.isEmpty());
        refresh.setEnabled(true);
      });
    });
  }

  private static String exitsReport(Context context) {
    if (Build.VERSION.SDK_INT < 30) return "落叶的进程退出记录需要 Android 11 或以上。";
    try {
      return Api30.readExits(context);
    } catch (RuntimeException unavailable) {
      return "系统暂未开放落叶的进程退出记录。";
    }
  }

  private static final class Api30 {
    static String readExits(Context context) {
      ActivityManager manager = context.getSystemService(ActivityManager.class);
      if (manager == null) return "系统进程服务暂不可用。";
      List<ApplicationExitInfo> records = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
      if (records == null || records.isEmpty()) return "系统没有留存落叶的进程退出记录。";
      DateFormat time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
      StringBuilder result = new StringBuilder("落叶最近的进程退出记录");
      for (ApplicationExitInfo record : records) {
        result.append("\n").append(time.format(new Date(record.getTimestamp()))).append("　")
            .append(reason(record));
        if (record.getReason() == ApplicationExitInfo.REASON_SIGNALED) result.append(" ").append(record.getStatus());
        long pss = record.getPss();
        if (pss > 0 && pss <= Long.MAX_VALUE / 1024)
          result.append("（最近采样 PSS ").append(MainActivity.fmt(pss * 1024)).append("）");
      }
      if (!ActivityManager.isLowMemoryKillReportSupported()) {
        result.append("\n这台设备不能单独标记所有低内存终止事件。");
      }
      return result.toString();
    }

    private static String reason(ApplicationExitInfo record) {
      switch (record.getReason()) {
        case ApplicationExitInfo.REASON_LOW_MEMORY: return "系统低内存终止";
        case ApplicationExitInfo.REASON_SIGNALED: return "收到信号";
        case ApplicationExitInfo.REASON_EXIT_SELF: return "进程自行退出";
        case ApplicationExitInfo.REASON_CRASH: return "应用崩溃";
        case ApplicationExitInfo.REASON_CRASH_NATIVE: return "原生代码崩溃";
        case ApplicationExitInfo.REASON_ANR: return "应用无响应";
        case ApplicationExitInfo.REASON_USER_REQUESTED: return "按用户请求终止";
        case ApplicationExitInfo.REASON_USER_STOPPED: return "该 Android 用户已停止运行";
        case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "初始化失败";
        case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "权限已变更";
        case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "资源使用过多";
        case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "依赖进程已退出";
        case ApplicationExitInfo.REASON_FREEZER: return "Android 冻结器终止进程";
        case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "应用已更新";
        case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "应用状态已变更";
        case ApplicationExitInfo.REASON_MEMORY_LIMITER: return "超过系统分配的进程内存上限";
        case ApplicationExitInfo.REASON_ANOMALY: return "系统检测到应用异常行为";
        case ApplicationExitInfo.REASON_OTHER:
          String detail = record.getDescription();
          // Android 17 originally reported per-process limit hits through REASON_OTHER.
          return detail != null && detail.contains("MemoryLimiter:AnonSwap")
              ? "超过系统分配的进程内存上限" : "系统报告其他原因";
        default: return "系统未识别的原因（" + record.getReason() + "）";
      }
    }
  }
}

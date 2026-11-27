package dev.pagetrim.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Edits monitoring preferences. The caller handles privileges and starts or stops the service. */
public final class WatchEditor {
  public interface Callback {
    void onSave(WatchConfig config);
    void onStop();
  }

  private static final String FORM_TAG = "dev.pagetrim.app.WatchEditor.form";

  private static final class Trigger {
    final Switch enabled;
    final EditText value;
    final int maximum;
    final int savedValue;

    Trigger(LinearLayout parent, String name, String unit, int maximum, int savedValue) {
      Context context = parent.getContext();
      this.maximum = maximum;
      this.savedValue = savedValue;
      enabled = new Switch(context);
      enabled.setText(name);
      parent.addView(enabled);
      TextView label = new TextView(context);
      label.setText(unit + "（1–" + maximum + "）");
      parent.addView(label);
      value = new EditText(context);
      value.setId(View.generateViewId());
      label.setLabelFor(value.getId());
      value.setInputType(InputType.TYPE_CLASS_NUMBER);
      value.setSingleLine(true);
      parent.addView(value);
    }

    int read() {
      try {
        int number = Integer.parseInt(value.getText().toString().trim());
        if (number >= 1 && number <= maximum) return number;
      } catch (NumberFormatException ignored) {
        // Empty, malformed and overflowing values use the same range message.
      }
      if (!enabled.isChecked()) return savedValue;
      value.setError("请输入 1 到 " + maximum + " 之间的整数");
      return -1;
    }

    void updateEnabled() {
      value.setEnabled(enabled.isChecked());
      if (!enabled.isChecked()) value.setError(null);
    }
  }

  private static final class Form extends LinearLayout {
    final Trigger threshold, interval;
    final RadioButton reminder, reclaim;
    final Switch lowMemoryOnly;
    final TextView error;

    Form(Activity activity, WatchConfig saved, Bundle draft) {
      super(activity);
      setTag(FORM_TAG);
      setOrientation(VERTICAL);
      int pad = Math.round(20 * getResources().getDisplayMetrics().density);
      setPadding(pad, pad / 2, pad, pad / 2);
      setFocusableInTouchMode(true);
      threshold = new Trigger(this, "按内存占用触发", "占用比例（%）", 99, saved.usedPercent);
      interval = new Trigger(this, "按时间间隔触发", "间隔（分钟）", 1440, saved.intervalMinutes);
      threshold.value.setText(draft == null ? String.valueOf(saved.usedPercent) : draft.getString("percent"));
      interval.value.setText(draft == null ? String.valueOf(saved.intervalMinutes) : draft.getString("minutes"));
      threshold.enabled.setChecked(draft == null ? saved.thresholdEnabled : draft.getBoolean("threshold"));
      interval.enabled.setChecked(draft == null ? saved.intervalEnabled : draft.getBoolean("interval"));
      threshold.updateEnabled();
      interval.updateEnabled();

      TextView heading = new TextView(activity);
      heading.setText("满足任一已启用条件时");
      heading.setPadding(0, pad / 2, 0, 0);
      addView(heading);
      RadioGroup group = new RadioGroup(activity);
      reminder = new RadioButton(activity);
      reminder.setId(View.generateViewId());
      reminder.setText("仅通知提醒");
      reclaim = new RadioButton(activity);
      reclaim.setId(View.generateViewId());
      reclaim.setText("用 Root 自动回收干净的文件页缓存");
      group.addView(reminder);
      group.addView(reclaim);
      addView(group);
      boolean withRoot = draft == null ? saved.mode == WatchService.Mode.ROOT_RECLAIM : draft.getBoolean("root");
      group.check(withRoot ? reclaim.getId() : reminder.getId());
      lowMemoryOnly = new Switch(activity);
      lowMemoryOnly.setText("仅在 Android 报告低内存时自动回收");
      lowMemoryOnly.setChecked(draft == null ? saved.lowMemoryOnly : draft.getBoolean("lowMemoryOnly"));
      lowMemoryOnly.setEnabled(withRoot);
      addView(lowMemoryOnly);
      group.setOnCheckedChangeListener((buttons, selected) -> lowMemoryOnly.setEnabled(selected == reclaim.getId()));
      TextView background = new TextView(activity);
      background.setPadding(0, pad / 2, 0, 0);
      background.setText("监控开启后会显示常驻通知。切到其他应用、锁屏或划走落叶时，监控可继续，休眠可能推迟检查。"
          + "系统回收进程后可尝试恢复监控。手动停止或手机重启后，需要重新开启。");
      addView(background);
      error = new TextView(activity);
      error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
      error.setVisibility(GONE);
      addView(error);
      if (draft != null) {
        threshold.value.setError(draft.getString("percentError"));
        interval.value.setError(draft.getString("minutesError"));
        if (draft.containsKey("feedback")) showError(draft.getString("feedback"));
      }
    }

    void updateButton(AlertDialog dialog) {
      boolean active = threshold.enabled.isChecked() || interval.enabled.isChecked();
      String label = active ? (WatchService.isRunning() ? "保存更改" : "启用监控")
                            : (WatchService.isRunning() ? "保存并停止" : "保存设置");
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(label);
    }

    void showError(String message) {
      error.setText(message);
      error.setVisibility(VISIBLE);
    }

    Bundle snapshot() {
      Bundle state = new Bundle();
      state.putBoolean("threshold", threshold.enabled.isChecked());
      state.putString("percent", threshold.value.getText().toString());
      state.putBoolean("interval", interval.enabled.isChecked());
      state.putString("minutes", interval.value.getText().toString());
      state.putBoolean("root", reclaim.isChecked());
      state.putBoolean("lowMemoryOnly", lowMemoryOnly.isChecked());
      if (threshold.value.getError() != null)
        state.putString("percentError", threshold.value.getError().toString());
      if (interval.value.getError() != null)
        state.putString("minutesError", interval.value.getError().toString());
      if (error.getVisibility() == VISIBLE) state.putString("feedback", error.getText().toString());
      return state;
    }
  }

  public static AlertDialog show(Activity activity, Callback callback) {
    return show(activity, callback, null);
  }

  public static AlertDialog show(Activity activity, Callback callback, Bundle draft) {
    return show(activity, callback, draft, null);
  }

  static AlertDialog show(Activity activity, Callback callback, Bundle draft, WatchConfig pending) {
    Form form = new Form(activity, pending == null ? WatchConfig.load(activity) : pending, draft);
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(form);
    AlertDialog.Builder builder = new AlertDialog.Builder(activity)
        .setTitle("监控设置")
        .setView(scroll)
        .setPositiveButton("保存", null)
        .setNegativeButton("取消", null);
    if (WatchService.isRunning() || pending != null)
      builder.setNeutralButton(WatchService.isRunning() ? "停止监控" : "取消启用", (d, which) -> callback.onStop());
    AlertDialog dialog = builder.create();
    dialog.setOnShowListener(ignored -> {
      form.updateButton(dialog);
      for (Trigger trigger : new Trigger[] {form.threshold, form.interval}) {
        trigger.enabled.setOnCheckedChangeListener((button, checked) -> {
          trigger.updateEnabled();
          form.updateButton(dialog);
        });
      }
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
        form.error.setVisibility(View.GONE);
        int percent = form.threshold.read();
        int minutes = form.interval.read();
        if (percent < 0 || minutes < 0) {
          (percent < 0 ? form.threshold.value : form.interval.value).requestFocus();
          return;
        }
        WatchService.Mode mode = form.reclaim.isChecked() ? WatchService.Mode.ROOT_RECLAIM
            : WatchService.Mode.ALERT_ONLY;
        try {
          callback.onSave(new WatchConfig(form.threshold.enabled.isChecked(), percent,
              form.interval.enabled.isChecked(), minutes, mode, form.lowMemoryOnly.isChecked()));
          dialog.dismiss();
        } catch (IllegalArgumentException | IllegalStateException e) {
          form.showError(e.getMessage() == null ? "无法保存监控设置。" : e.getMessage());
        }
      });
    });
    dialog.show();
    return dialog;
  }

  /** Returns null when there are no open edits to restore. */
  public static Bundle saveState(AlertDialog dialog) {
    if (dialog == null || !dialog.isShowing() || dialog.getWindow() == null) return null;
    Form form = dialog.getWindow().getDecorView().findViewWithTag(FORM_TAG);
    return form == null ? null : form.snapshot();
  }

  private WatchEditor() {}
}

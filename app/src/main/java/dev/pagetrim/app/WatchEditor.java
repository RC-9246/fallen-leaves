package dev.pagetrim.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.Button;
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
      value.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
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
    final RadioButton reminder, reclaim, local;
    final CheckBox gc, sqlite, allocator;
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
      local = new RadioButton(activity);
      local.setId(View.generateViewId());
      local.setText("免 Root，只回收落叶自身运存");
      group.addView(reminder);
      group.addView(local);
      group.addView(reclaim);
      addView(group);
      WatchService.Mode mode = draft == null ? saved.mode
          : WatchService.Mode.fromPreference(draft.getString("mode"));
      group.check(mode == WatchService.Mode.ROOT_RECLAIM ? reclaim.getId()
          : mode == WatchService.Mode.LOCAL_RECLAIM ? local.getId() : reminder.getId());
      LinearLayout localOptions = new LinearLayout(activity);
      localOptions.setOrientation(VERTICAL);
      localOptions.setPadding(pad, 0, 0, 0);
      int scopes = draft == null ? saved.localScopes : draft.getInt("localScopes");
      gc = option(localOptions, "请求落叶的 Java 堆垃圾回收", scopes, LocalMemory.GC);
      sqlite = option(localOptions, "释放落叶的 SQLite 内存缓存", scopes, LocalMemory.SQLITE);
      allocator = option(localOptions, "归还落叶原生分配器的空闲页（Android 9 起）", scopes, LocalMemory.NATIVE);
      allocator.setEnabled(Build.VERSION.SDK_INT >= 28);
      addView(localOptions);
      localOptions.setVisibility(local.isChecked() ? VISIBLE : GONE);
      lowMemoryOnly = new Switch(activity);
      lowMemoryOnly.setText("仅在 Android 报告低内存时自动回收");
      lowMemoryOnly.setChecked(draft == null ? saved.lowMemoryOnly : draft.getBoolean("lowMemoryOnly"));
      lowMemoryOnly.setEnabled(!reminder.isChecked());
      addView(lowMemoryOnly);
      group.setOnCheckedChangeListener((buttons, selected) -> {
        lowMemoryOnly.setEnabled(selected != reminder.getId());
        localOptions.setVisibility(selected == local.getId() ? VISIBLE : GONE);
      });
      TextView background = new TextView(activity);
      background.setPadding(0, pad / 2, 0, 0);
      background.setText("监控开启后会显示常驻通知。切到其他应用、锁屏或划走落叶时，监控可继续，休眠可能推迟检查。"
          + "系统回收进程后可尝试恢复监控。手动停止或手机重启后，需要重新开启。");
      addView(background);
      Button battery = new Button(activity) {
        @Override public void onWindowFocusChanged(boolean focused) {
          super.onWindowFocusChanged(focused);
          if (focused) setText(batteryLabel(activity));
        }
      };
      battery.setText(batteryLabel(activity));
      battery.setOnClickListener(v -> {
        try {
          activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
          try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.getPackageName(), null)));
          } catch (ActivityNotFoundException | SecurityException missing) {
            showError("请在系统设置的电池优化列表中查找落叶。");
          }
        }
      });
      addView(battery);
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

    private String batteryLabel(Activity activity) {
      PowerManager power = activity.getSystemService(PowerManager.class);
      if (power == null) return "查看系统电池优化";
      return power.isIgnoringBatteryOptimizations(activity.getPackageName())
          ? "系统电池优化：已豁免" : "系统电池优化：查看后台限制";
    }

    private CheckBox option(LinearLayout parent, String title, int mask, int bit) {
      CheckBox check = new CheckBox(getContext());
      check.setText(title);
      check.setChecked((mask & bit) != 0);
      parent.addView(check);
      return check;
    }

    int scopes() {
      return (gc.isChecked() ? LocalMemory.GC : 0) | (sqlite.isChecked() ? LocalMemory.SQLITE : 0)
          | (allocator.isEnabled() && allocator.isChecked() ? LocalMemory.NATIVE : 0);
    }

    WatchService.Mode mode() {
      if (local.isChecked()) return WatchService.Mode.LOCAL_RECLAIM;
      return reclaim.isChecked() ? WatchService.Mode.ROOT_RECLAIM : WatchService.Mode.ALERT_ONLY;
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
      state.putString("mode", mode().name());
      state.putInt("localScopes", scopes());
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
    boolean shortWindow = activity.getResources().getConfiguration().screenHeightDp < 480;
    Form form = new Form(activity, pending == null ? WatchConfig.load(activity) : pending, draft);
    ScrollView scroll = new ScrollView(activity);
    if (shortWindow) {
      // A fixed title can take the entire visible area when a landscape keyboard is open.
      TextView title = new TextView(activity);
      title.setText("监控设置");
      title.setTextSize(20);
      title.setTextColor(activity.getColor(R.color.leaf_text));
      form.addView(title, 0);
    }
    scroll.addView(form);
    AlertDialog.Builder builder = new AlertDialog.Builder(activity)
        .setTitle(shortWindow ? null : "监控设置")
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
        WatchService.Mode mode = form.mode();
        try {
          callback.onSave(new WatchConfig(form.threshold.enabled.isChecked(), percent,
              form.interval.enabled.isChecked(), minutes, mode, form.lowMemoryOnly.isChecked(), form.scopes()));
          dialog.dismiss();
        } catch (IllegalArgumentException | IllegalStateException e) {
          form.showError(e.getMessage() == null ? "无法保存监控设置。" : e.getMessage());
        }
      });
    });
    android.view.Window window = dialog.getWindow();
    window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    dialog.show();
    if (Build.VERSION.SDK_INT >= 35) {
      // The alert layout reserves space for the entire actions panel, even if its inner
      // button row is hidden. Locate the outer panel shared by all dialog buttons.
      View panel = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      while (panel.getParent() instanceof View) {
        View parent = (View) panel.getParent();
        if (parent.findViewWithTag(FORM_TAG) != null) break;
        panel = parent;
      }
      final View actions = panel;
      // The frame of a floating platform dialog can extend below a docked keyboard.
      View decor = window.getDecorView();
      decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
        android.view.WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null) return;
        android.view.WindowManager.LayoutParams frame = window.getAttributes();
        boolean editing = insets.isVisible(android.view.WindowInsets.Type.ime());
        int height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
        int gravity = android.view.Gravity.CENTER;
        if (editing) {
          android.graphics.Rect visible = new android.graphics.Rect();
          decor.getWindowVisibleDisplayFrame(visible);
          if (visible.height() <= 0) return;
          height = visible.height();
          gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        }
        // When only a text line fits above a docked keyboard, its Done key or Back
        // ends editing. Restore the dialog actions once the keyboard closes.
        boolean inputOnly = editing && height < 160 * decor.getResources().getDisplayMetrics().density;
        int visibility = inputOnly ? View.GONE : View.VISIBLE;
        if (actions.getVisibility() != visibility) {
          actions.setVisibility(visibility);
          if (inputOnly) {
            View focus = form.findFocus();
            if (focus != null) focus.post(() ->
                focus.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
                    focus.getWidth(), focus.getHeight()), true));
          }
        }
        if (frame.height != height || frame.gravity != gravity) {
          frame.height = height;
          frame.gravity = gravity;
          window.setAttributes(frame);
        }
      });
    }
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

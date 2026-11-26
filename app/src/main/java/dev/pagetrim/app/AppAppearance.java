package dev.pagetrim.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

final class AppAppearance extends LinearLayout {
  private static final int FOLLOW = Configuration.UI_MODE_NIGHT_UNDEFINED;
  private static final int LIGHT = Configuration.UI_MODE_NIGHT_NO;
  private static final int DARK = Configuration.UI_MODE_NIGHT_YES;
  private static final String THEME = "theme", RESULTS = "show_results";
  private final RadioButton light, dark;
  private final Switch results;

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
  }

  static boolean showResults(Context context) {
    return prefs(context).getBoolean(RESULTS, true);
  }

  static void applyThemeOverride(Activity activity, Context base) {
    int mode = prefs(base).getInt(THEME, FOLLOW);
    if (mode == LIGHT || mode == DARK) {
      Configuration override = new Configuration();
      override.uiMode = mode;
      activity.applyOverrideConfiguration(override);
    }
  }

  private AppAppearance(Context context, Bundle draft) {
    super(context);
    setOrientation(VERTICAL);
    int pad = Math.round(20 * getResources().getDisplayMetrics().density);
    setPadding(pad, pad / 2, pad, 0);
    TextView label = new TextView(context);
    label.setText("主题");
    addView(label);
    RadioGroup modes = new RadioGroup(context);
    RadioButton follow = option(modes, "跟随系统");
    light = option(modes, "浅色");
    dark = option(modes, "深色");
    int mode = draft == null ? prefs(context).getInt(THEME, FOLLOW) : draft.getInt(THEME, FOLLOW);
    modes.check((mode == LIGHT ? light : mode == DARK ? dark : follow).getId());
    addView(modes);

    results = new Switch(context);
    results.setText("显示清理完成弹窗");
    results.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
    results.setChecked(draft == null ? showResults(context) : draft.getBoolean(RESULTS, true));
    addView(results);
    TextView note = new TextView(context);
    note.setText("关闭后可在「记录」查看清理结果。失败和部分完成时仍会提示。");
    addView(note);
  }

  private RadioButton option(RadioGroup group, String label) {
    RadioButton option = new RadioButton(getContext());
    option.setId(View.generateViewId());
    option.setText(label);
    group.addView(option);
    return option;
  }

  private int selectedTheme() {
    return light.isChecked() ? LIGHT : dark.isChecked() ? DARK : FOLLOW;
  }

  Bundle snapshot() {
    Bundle draft = new Bundle();
    draft.putInt(THEME, selectedTheme());
    draft.putBoolean(RESULTS, results.isChecked());
    return draft;
  }

  static AlertDialog show(Activity activity, Bundle draft) {
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    AppAppearance form = new AppAppearance(builder.getContext(), draft);
    form.setId(R.id.appearance_form);
    ScrollView scroll = new ScrollView(builder.getContext());
    scroll.addView(form);
    return builder.setTitle("界面与提示").setView(scroll)
        .setNegativeButton("取消", null)
        .setPositiveButton("保存", (dialog, which) -> {
          int mode = form.selectedTheme();
          SharedPreferences prefs = prefs(activity);
          boolean themeChanged = mode != prefs.getInt(THEME, FOLLOW);
          prefs.edit().putInt(THEME, mode).putBoolean(RESULTS, form.results.isChecked()).apply();
          dialog.dismiss();
          if (themeChanged) activity.recreate();
        }).show();
  }
}

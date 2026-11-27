package dev.pagetrim.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** Places controls in the usable part of an edge-to-edge activity window. */
final class WindowLayout {
  private WindowLayout() {}

  static void setContent(Activity activity, View root) {
    // Android 15's PhoneWindow needs the decor installed before accessing its insets controller.
    activity.setContentView(root);
    if (Build.VERSION.SDK_INT < 35) return;
    Window window = activity.getWindow();
    window.setDecorFitsSystemWindows(false);
    window.setNavigationBarContrastEnforced(false);
    WindowInsetsController controller = window.getInsetsController();
    if (controller != null) {
      int lightIcons = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
          | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
      Configuration config = activity.getResources().getConfiguration();
      boolean dark = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
      controller.setSystemBarsAppearance(dark ? 0 : lightIcons, lightIcons);
    }
    root.setBackgroundColor(activity.getColor(R.color.leaf_background));
    int left = root.getPaddingLeft(), top = root.getPaddingTop();
    int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
    root.setOnApplyWindowInsetsListener((view, insets) -> {
      Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
          | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
      view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom);
      return WindowInsets.CONSUMED;
    });
    root.requestApplyInsets();
  }
}

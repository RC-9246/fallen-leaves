package dev.pagetrim.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

final class History {
  static synchronized void add(Context context, String action, String detail) {
    try {
      SharedPreferences p = context.getSharedPreferences("local", Context.MODE_PRIVATE);
      JSONArray old = list(context);
      JSONArray next = new JSONArray();
      next.put(
          new JSONObject()
              .put("time", System.currentTimeMillis())
              .put("action", action)
              .put("detail", detail));
      for (int i = 0; i < Math.min(old.length(), 29); i++) next.put(old.get(i));
      p.edit().putString("history", next.toString()).apply();
    } catch (org.json.JSONException ignored) {
    }
  }

  static synchronized JSONArray list(Context context) {
    try {
      return new JSONArray(
          context.getSharedPreferences("local", Context.MODE_PRIVATE).getString("history", "[]"));
    } catch (org.json.JSONException e) {
      return new JSONArray();
    }
  }

  private History() {}
}

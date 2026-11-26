package dev.pagetrim.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.IOException;

final class FilesScope {
  private FilesScope() {}

  private static final int INSPECT = 0, EVICT = 1, SYNC = 2;
  private static final String[] NAME_COLUMN = {OpenableColumns.DISPLAY_NAME};

  static Intent pickIntent() {
    return new Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }

  static Intent syncIntent() {
    return pickIntent().addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  }

  static String run(Context context, Uri uri, boolean evict) throws IOException {
    return access(context, uri, evict ? EVICT : INSPECT);
  }

  static String runSync(Context context, Uri uri) throws IOException {
    return access(context, uri, SYNC);
  }

  private static void validateProvider(Context context, Uri uri) throws IOException {
    if (uri == null
        || !"content".equals(uri.getScheme())
        || !DocumentsContract.isDocumentUri(context, uri)) {
      throw new IOException("请选择系统文件选择器提供的本机文档。");
    }
    String authority = uri.getAuthority();
    if (!"com.android.externalstorage.documents".equals(authority)
        && !"com.android.providers.downloads.documents".equals(authority)
        && !"com.android.providers.media.documents".equals(authority)) {
      throw new IOException("仅支持系统的本机存储、下载和媒体文档提供器。");
    }
    ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
    if (provider == null
        || (provider.applicationInfo.flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
            == 0) {
      throw new IOException("无法核实本机系统文档提供器。");
    }
  }

  private static String displayName(Context context, Uri uri) {
    try (Cursor cursor = context.getContentResolver().query(uri, NAME_COLUMN, null, null, null)) {
      if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
        String name = cursor.getString(0).replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        if (!name.isEmpty()) return name.length() <= 120 ? name : name.substring(0, 119) + "…";
      }
    } catch (RuntimeException ignored) {
      // The opened descriptor remains authoritative when optional metadata is unavailable.
    }
    return "所选文件";
  }

  private static String access(Context context, Uri uri, int mode) throws IOException {
    validateProvider(context, uri);
    if (mode == SYNC
        && context.checkUriPermission(
                uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            != PackageManager.PERMISSION_GRANTED) {
      throw new IOException("未取得这个文档的写授权，已跳过写回。");
    }
    String name = displayName(context, uri);
    long[] r;
    try (ParcelFileDescriptor file = context.getContentResolver().openFileDescriptor(uri, "r")) {
      if (file == null) throw new IOException("文档提供器未返回文件描述符。");
      System.loadLibrary("fallenfilecache");
      r = operate(file.getFd(), mode);
    } catch (LinkageError e) {
      throw new IOException("此安装缺少当前架构的文件缓存组件。", e);
    } catch (SecurityException e) {
      throw new IOException("文件访问授权已失效，请重新选择。", e);
    }
    StringBuilder result = new StringBuilder(name).append(" · ").append(MainActivity.fmt(r[0]));
    if (mode == SYNC) {
      return result
          .append("\n文件系统已接受并完成对该文件的 fsync。" + "本次只请求同步；写入字节数及物理介质内部状态无法从该接口取得。")
          .toString();
    }
    if (mode == EVICT && r[0] > 0) result.append("\n已向内核提交整个文件的干净页缓存逐出建议。内核也可能安排写回已有的脏页。");
    if (r[5] != 0) return result.append("\n文件在操作期间发生变化或无法复核，驻留变化不可比较。").toString();
    if (r[2] == 0) return result.append("\n空文件，驻留 0 页。").toString();
    if (r[3] < 0) return result.append("\n驻留未知。当前身份或文件系统无法提供可信驻留统计。").toString();
    String coverage = r[2] == r[0] ? "整个文件" : "仅开头 " + MainActivity.fmt(r[2]);
    result.append("\n").append(coverage).append("的驻留页：").append(r[3]);
    if (mode == EVICT) {
      if (r[4] < 0) return result.append(" → 后读数不可用。").toString();
      long pages = r[3] - r[4];
      result
          .append(" → ")
          .append(r[4])
          .append("。观测到的驻留")
          .append(pages < 0 ? "增加 " : "减少 ")
          .append(MainActivity.fmt(Math.abs(pages) * r[1]))
          .append("。并发读写会影响结果。");
    } else {
      result.append(" 页（").append(MainActivity.fmt(r[3] * r[1])).append("）。");
    }
    return result.toString();
  }

  // size, system page size, sampled byte range, before/after resident page counts, changed
  private static native long[] operate(int fd, int mode) throws IOException;
}

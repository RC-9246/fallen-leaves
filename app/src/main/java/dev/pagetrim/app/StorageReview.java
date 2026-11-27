package dev.pagetrim.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.IOException;
import java.io.FileDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

final class StorageReview {
  static final String AUTHORITY = "com.android.externalstorage.documents";
  private static final int MAX_ENTRIES = 12000, MAX_CANDIDATES = 100;
  private static final String[] COLUMNS = { Document.COLUMN_DOCUMENT_ID,
      Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
      Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS };

  static final class Item {
    final String id;
    final long size, modified;
    final StorageReviewPolicy.Kind kind;
    final StructStat identity;
    Item(String id, long size, long modified, StorageReviewPolicy.Kind kind, StructStat identity) {
      this.id = id;
      this.size = size;
      this.modified = modified;
      this.kind = kind;
      this.identity = identity;
    }
  }

  static final class Scan {
    final List<Item> candidates = new ArrayList<>(), largest = new ArrayList<>();
    long bytes;
    int files, unknownSize, unavailable;
    boolean limited;
  }

  private static String checkTree(Context context, Uri tree) throws IOException {
    if (tree == null || !"content".equals(tree.getScheme()) || !AUTHORITY.equals(tree.getAuthority())
        || !DocumentsContract.isTreeUri(tree)) throw new IOException("请选择系统文件管理器中的本机存储文件夹。");
    String root = DocumentsContract.getTreeDocumentId(tree);
    if (!StorageReviewPolicy.allowedTree(root)) throw new IOException("请选择共享存储下的具体文件夹。");
    ProviderInfo provider = context.getPackageManager().resolveContentProvider(AUTHORITY, 0);
    if (provider == null || (provider.applicationInfo.flags &
        (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0)
      throw new IOException("无法核实本机存储提供器。");
    if (context.checkUriPermission(tree, Process.myPid(), Process.myUid(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
      throw new IOException("文件夹授权已失效，请重新选择。");
    return root;
  }

  private static Uri document(Uri tree, String id) {
    return DocumentsContract.buildDocumentUriUsingTree(tree, id);
  }

  private static boolean canWrite(Context context, Uri tree) {
    return context.checkUriPermission(tree, Process.myPid(), Process.myUid(),
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
  }

  private static long number(Cursor c, int column) {
    return c.isNull(column) ? -1 : c.getLong(column);
  }

  private static boolean deletable(Cursor c) {
    return !c.isNull(5) && (c.getInt(5) & Document.FLAG_SUPPORTS_DELETE) != 0
        && (c.getInt(5) & Document.FLAG_VIRTUAL_DOCUMENT) == 0;
  }

  private static StructStat archiveCheckedStat(ContentResolver resolver, Uri file, long expectedSize) {
    try (ParcelFileDescriptor handle = resolver.openFileDescriptor(file, "r")) {
      if (handle == null) return null;
      FileDescriptor fd = handle.getFileDescriptor();
      StructStat stat = Os.fstat(fd);
      if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != expectedSize) return null;
      byte[] prefix = new byte[2];
      int prefixSize = (int) Math.min(expectedSize, prefix.length);
      if (prefixSize > 0 && Os.pread(fd, prefix, 0, prefixSize, 0) != prefixSize) return null;
      if (StorageReviewPolicy.looksLikeZip(prefix[0], prefix[1])) return null;
      // ZIP archives can have a prepended stub; their end record precedes at most a 65535-byte comment.
      int tailLength = (int) Math.min(expectedSize, 65535 + 22);
      byte[] tail = new byte[tailLength];
      int offset = 0;
      while (offset < tailLength) {
        int n = Os.pread(fd, tail, offset, tailLength - offset, expectedSize - tailLength + offset);
        if (n <= 0) return null;
        offset += n;
      }
      return StorageReviewPolicy.zipDirectoryEnd(tail, tailLength) ? null : stat;
    } catch (IOException | ErrnoException | RuntimeException unavailable) {
      return null;
    }
  }

  private static boolean sameFile(StructStat first, StructStat next) {
    if (first == null || next == null || first.st_dev != next.st_dev || first.st_ino != next.st_ino
        || first.st_size != next.st_size || first.st_mtime != next.st_mtime
        || first.st_ctime != next.st_ctime) return false;
    return Build.VERSION.SDK_INT < 27
        || first.st_mtim.equals(next.st_mtim) && first.st_ctim.equals(next.st_ctim);
  }

  static Scan scan(Context context, Uri tree, EnumSet<StorageReviewPolicy.Kind> scopes, CancellationSignal cancel) throws IOException {
    String root = checkTree(context, tree);
    boolean writable = canWrite(context, tree);
    Scan scan = new Scan();
    ArrayDeque<String> folders = new ArrayDeque<>();
    folders.add(root);
    ContentResolver resolver = context.getContentResolver();
    int visited = 0, inspected = 0;
    long now = System.currentTimeMillis();
    while (!folders.isEmpty()) {
      cancel.throwIfCanceled();
      String parent = folders.removeFirst();
      Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
      try (Cursor c = resolver.query(children, COLUMNS, null, null, null, cancel)) {
        if (c == null || c.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING)) {
          scan.unavailable++;
          continue;
        }
        while (c.moveToNext()) {
          cancel.throwIfCanceled();
          if (++visited > MAX_ENTRIES) {
            scan.limited = true;
            folders.clear();
            break;
          }
          String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
          if (!StorageReviewPolicy.child(parent, id, name) || !StorageReviewPolicy.allowedTree(id)) {
            scan.unavailable++;
            continue;
          }
          if (Document.MIME_TYPE_DIR.equals(mime)) {
            folders.addLast(id);
            continue;
          }
          long size = number(c, 3), modified = number(c, 4);
          scan.files++;
          if (size < 0) scan.unknownSize++;
          else {
            scan.bytes += size;
            if (size > 0 && (scan.largest.size() < 5 || size > scan.largest.get(4).size)) {
              scan.largest.add(new Item(id, size, modified, null, null));
              scan.largest.sort(Comparator.comparingLong((Item i) -> i.size).reversed());
              if (scan.largest.size() > 5) scan.largest.remove(5);
            }
          }
          StorageReviewPolicy.Kind kind = StorageReviewPolicy.classify(id, mime, size, modified, now);
          if (!writable || !deletable(c) || !scopes.contains(kind)) continue;
          // Beyond the preview limit avoid opening additional files just to count them.
          if (scan.candidates.size() == MAX_CANDIDATES || inspected >= 250) {
            scan.limited = true;
            continue;
          }
          inspected++;
          StructStat identity = archiveCheckedStat(resolver, document(tree, id), size);
          if (identity != null) scan.candidates.add(new Item(id, size, modified, kind, identity));
        }
      } catch (OperationCanceledException e) {
        throw e;
      } catch (RuntimeException unavailable) {
        if (parent.equals(root)) throw new IOException("无法读取这个文件夹，请重新授权。", unavailable);
        scan.unavailable++;
      }
    }
    scan.candidates.sort(Comparator.comparingLong((Item i) -> i.size).reversed());
    return scan;
  }

  static String delete(Context context, Uri tree, List<Item> selected,
      CancellationSignal cancel) throws IOException {
    String root = checkTree(context, tree);
    if (!canWrite(context, tree)) throw new IOException("缺少这个文件夹的写入授权，请重新选择。");
    ContentResolver resolver = context.getContentResolver();
    int deleted = 0, skipped = 0;
    long bytes = 0;
    for (Item item : selected) {
      if (cancel.isCanceled()) break;
      if (!item.id.startsWith(root + "/")) { skipped++; continue; }
      Uri file = document(tree, item.id);
      try {
        boolean matches;
        // SAF has no compare-and-delete operation. Check current identity, metadata, and contents
        // immediately before submitting each single-file request to the trusted local provider.
        try (Cursor c = resolver.query(file, COLUMNS, null, null, null, cancel)) {
          matches = c != null && c.moveToFirst() && item.id.equals(c.getString(0))
              && item.id.substring(item.id.lastIndexOf('/') + 1).equals(c.getString(1))
              && number(c, 3) == item.size && number(c, 4) == item.modified && deletable(c)
              && item.kind != null && item.kind == StorageReviewPolicy.classify(item.id, c.getString(2),
                  item.size, item.modified, System.currentTimeMillis());
        }
        if (matches && sameFile(item.identity, archiveCheckedStat(resolver, file, item.size)) && !cancel.isCanceled()
            && DocumentsContract.deleteDocument(resolver, file)) {
          deleted++;
          bytes += item.size;
        } else skipped++;
      } catch (OperationCanceledException cancelled) {
        break;
      } catch (IOException | RuntimeException unavailable) {
        skipped++;
      }
    }
    int remaining = selected.size() - deleted - skipped;
    return "已删除 " + deleted + " 个文件，所选文件大小合计 " + MainActivity.fmt(bytes) + "。跳过 "
        + skipped + " 个。" + (remaining > 0 ? "未处理 " + remaining + " 个。" : "");
  }

  private StorageReview() {}
}

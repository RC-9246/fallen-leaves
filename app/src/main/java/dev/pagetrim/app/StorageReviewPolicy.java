package dev.pagetrim.app;

import java.util.Locale;

/** File selection rules for the platform ExternalStorageProvider's volume:path document IDs. */
final class StorageReviewPolicy {
  enum Kind { TEMP, CACHE }
  static final long WEEK = 7L * 24 * 60 * 60 * 1000;

  static boolean safeComponent(String name) {
    if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")) return false;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '/' || c == '\\' || Character.isISOControl(c)
          || Character.getType(c) == Character.FORMAT) return false;
    }
    return true;
  }

  static boolean allowedTree(String id) {
    if (id == null) return false;
    int colon = id.indexOf(':');
    if (colon < 1 || !id.substring(0, colon).matches("[A-Za-z0-9_-]+")) return false;
    String path = id.substring(colon + 1);
    for (String part : path.split("/", -1)) if (!safeComponent(part)) return false;
    String lower = (path + "/").toLowerCase(Locale.ROOT);
    return !lower.startsWith("android/data/") && !lower.startsWith("android/obb/");
  }

  static boolean child(String parent, String id, String name) {
    return safeComponent(name) && (parent + "/" + name).equals(id);
  }

  static Kind classify(String id, String mime, long size, long modified, long now) {
    if (!allowedTree(id) || mime == null || size < 0 || modified <= 0 || now - modified < WEEK)
      return null;
    String[] parts = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT).split("/");
    String filename = parts[parts.length - 1];
    int dot = filename.indexOf('.');
    // A single, ordinary extension excludes package.apk.tmp, compound archives and hidden files.
    if (dot < 1 || dot != filename.lastIndexOf('.')) return null;
    String type = mime.toLowerCase(Locale.ROOT);
    if (!(type.equals("application/octet-stream") || type.equals("application/x-temp")
        || type.equals("text/plain")
        // Android derives this MIME from the .cache suffix alone.
        || (filename.endsWith(".cache") && type.equals("chemical/x-cache")))) return null;
    boolean cacheDirectory = false;
    for (int i = 0; i < parts.length - 1; i++) {
      String part = parts[i];
      if (part.equals("cache") || part.equals("caches") || part.equals(".cache")
          || part.equals("temp") || part.equals("tmp")) cacheDirectory = true;
    }
    if (!cacheDirectory) return null;
    switch (filename.substring(dot + 1)) {
      case "cache": return Kind.CACHE;
      case "tmp":
      case "temp": return Kind.TEMP;
      default: return null;
    }
  }

  static boolean looksLikeZip(int first, int second) {
    // APK, split bundles, JAR and ZIP all normally start with this prefix.
    return first == 'P' && second == 'K';
  }

  static boolean zipDirectoryEnd(byte[] tail, int length) {
    for (int i = 0; i + 3 < length; i++) {
      if (tail[i] == 'P' && tail[i + 1] == 'K'
          && ((tail[i + 2] == 5 && tail[i + 3] == 6)
              || (tail[i + 2] == 6 && tail[i + 3] == 6))) return true;
    }
    return false;
  }

  private StorageReviewPolicy() {}
}

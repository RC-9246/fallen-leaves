package dev.pagetrim.app;

final class StorageReviewPolicyTest {
  private static final long NOW = 1_800_000_000_000L;
  private static final long OLD = NOW - StorageReviewPolicy.WEEK;
  private static final String MIME = "application/octet-stream";

  private static void check(boolean value) { if (!value) throw new AssertionError(); }
  private static StorageReviewPolicy.Kind kind(String id, String mime, long modified) {
    return StorageReviewPolicy.classify(id, mime, 100, modified, NOW);
  }

  public static void main(String[] args) {
    String cache = "primary:Documents/project/cache/";
    check(kind(cache + "scratch.cache", "chemical/x-cache", OLD) == StorageReviewPolicy.Kind.CACHE);
    check(kind(cache + "scratch.tmp", "chemical/x-cache", OLD) == null);
    check(kind(cache + "scratch.tmp", MIME, OLD) == StorageReviewPolicy.Kind.TEMP);
    check(kind(cache + "scratch.temp", MIME, OLD + 1) == null);
    check(kind(cache + "scratch.temp", MIME, NOW + 1) == null);
    check(kind(cache + "scratch.temp", MIME, 0) == null);
    check(kind("A043-02D1:app/.cache/a/part.CACHE", MIME, OLD) == StorageReviewPolicy.Kind.CACHE);
    check(kind("primary:Documents/contract.tmp", MIME, OLD) == null);
    for (String name : new String[]{"app.apk", "app.APK.tmp", "app.zip.cache", ".cache",
        "chat.bak", "image.jpg", "x\u202e.tmp", "../x.tmp"})
      check(kind(cache + name, MIME, OLD) == null);
    check(kind(cache + "app.tmp", "application/vnd.android.package-archive", OLD) == null);
    check(kind(cache + "app.tmp", "application/zip", OLD) == null);
    for (String root : new String[]{"primary:", "primary:Android/data", "primary:Android/Obb/cache",
        "primary:/", "primary:cache/../Download", "primary:cache//a", "../:cache"})
      check(!StorageReviewPolicy.allowedTree(root));
    check(!StorageReviewPolicy.child("primary:tmp", "primary:tmp2/a.tmp", "a.tmp"));
    check(!StorageReviewPolicy.child("primary:tmp", "primary:tmp/x/a.tmp", "x/a.tmp"));
    check(StorageReviewPolicy.child("primary:tmp", "primary:tmp/a.tmp", "a.tmp"));
    check(StorageReviewPolicy.looksLikeZip('P', 'K'));
    check(!StorageReviewPolicy.looksLikeZip('P', 'F'));
    check(StorageReviewPolicy.zipDirectoryEnd(new byte[]{0, 'P', 'K', 5, 6, 0}, 6));
    check(StorageReviewPolicy.zipDirectoryEnd(new byte[]{'P', 'K', 6, 6}, 4));
    check(!StorageReviewPolicy.zipDirectoryEnd(new byte[]{'P', 'K', 5, 6}, 3));
    System.out.println("Shared folder selection, age, boundary and archive protection fixtures passed.");
  }
}

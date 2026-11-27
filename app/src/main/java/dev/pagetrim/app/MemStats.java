package dev.pagetrim.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** A single sample. Sizes are bytes; -1 means the counter was unavailable. */
public final class MemStats {
  private static final long RETRY_PROC_MS = 60_000;
  private static volatile long nextMeminfoRead, nextPressureRead;
  public final long total;
  public final long available;

  /** Estimated file page cache, excluding shared memory. */
  public final long cached;

  /** Reclaimable slab reported by the kernel, distinct from file page cache. */
  public final long reclaimable;

  public final long swapTotal;
  public final long swapFree;

  /** Percentage of time with some memory pressure, averaged over ten seconds. */
  public final double psiSome;

  long usedRam() {
    return total > 0 && available >= 0 ? total - available : -1;
  }

  long usedSwap() {
    return swapTotal >= 0 && swapFree >= 0 && swapFree <= swapTotal ? swapTotal - swapFree : -1;
  }

  /** File cache excluding shmem plus reclaimable slab. Some pages may be dirty or in use. */
  long estimatedCache() {
    if (cached < 0 || reclaimable < 0 || cached > Long.MAX_VALUE - reclaimable) return -1;
    return cached + reclaimable;
  }

  private MemStats(Map<String, Long> values, long total, long available, double psiSome) {
    this.total = total;
    this.available = total > 0 ? Math.min(total, available) : available;
    long pageCache = values.getOrDefault("Cached", -1L);
    long sharedMemory = values.getOrDefault("Shmem", -1L);
    this.cached = pageCache >= 0 && sharedMemory >= 0 ? Math.max(0, pageCache - sharedMemory) : -1;
    this.reclaimable = values.getOrDefault("SReclaimable", -1L);
    this.swapTotal = values.getOrDefault("SwapTotal", -1L);
    this.swapFree = values.getOrDefault("SwapFree", -1L);
    this.psiSome = psiSome;
  }

  public static MemStats read(Context context) {
    Map<String, Long> values = new HashMap<>(8);
    long now = SystemClock.elapsedRealtime();
    if (now >= nextMeminfoRead) {
      try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
        readMeminfo(reader, values);
      } catch (IOException | SecurityException ignored) {
        nextMeminfoRead = now + RETRY_PROC_MS;
      }
    }
    long total = values.getOrDefault("MemTotal", -1L);
    long available = values.getOrDefault("MemAvailable", -1L);
    if (total <= 0 || available < 0) {
      try {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager != null) {
          ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
          manager.getMemoryInfo(info);
          if (total <= 0 && info.totalMem > 0) total = info.totalMem;
          if (available < 0 && info.availMem >= 0) available = info.availMem;
        }
      } catch (RuntimeException unavailable) {
        // Keep the partial sample if both procfs access and the system service are unavailable.
      }
    }
    double pressure = -1;
    if (now >= nextPressureRead) {
      try (BufferedReader reader = new BufferedReader(new FileReader("/proc/pressure/memory"))) {
        pressure = readPressure(reader);
      } catch (IOException | SecurityException ignored) {
        // Avoid one failed open and SELinux audit per dashboard refresh on restricted kernels.
        nextPressureRead = now + RETRY_PROC_MS;
      }
    }
    return new MemStats(values, total, available, pressure);
  }

  static void readMeminfo(BufferedReader reader, Map<String, Long> values)
      throws IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      String name = line.substring(0, colon);
      switch (name) {
        case "MemTotal":
        case "MemAvailable":
        case "Cached":
        case "Shmem":
        case "SReclaimable":
        case "SwapTotal":
        case "SwapFree":
          break;
        default:
          continue;
      }
      String value = line.substring(colon + 1).trim();
      if (!value.endsWith(" kB")) continue;
      try {
        long kib = Long.parseLong(value.substring(0, value.length() - 3).trim());
        if (kib >= 0 && kib <= Long.MAX_VALUE / 1024) {
          values.put(name, kib * 1024);
        }
      } catch (NumberFormatException ignored) {
        // A malformed counter should not hide other measurements.
      }
    }
  }

  private static double readPressure(BufferedReader reader) throws IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      if (!line.startsWith("some ")) continue;
      for (String field : line.split("\\s+")) {
        if (!field.startsWith("avg10=")) continue;
        try {
          double value = Double.parseDouble(field.substring(6));
          return value >= 0 && value <= 100 ? value : -1;
        } catch (NumberFormatException ignored) {
          return -1;
        }
      }
    }
    return -1;
  }
}

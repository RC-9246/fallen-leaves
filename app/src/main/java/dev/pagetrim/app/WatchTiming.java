package dev.pagetrim.app;

/** One serial sampling session using elapsed time; missed ticks are never replayed. */
final class WatchTiming {
  static final int THRESHOLD = 1, INTERVAL = 2;
  static final long SAMPLE_MS = 30_000, COOLDOWN_MS = 30 * 60_000L;
  private final boolean thresholdEnabled;
  private final int limit;
  private final long intervalMs;
  private long nextSample, nextInterval;
  private int consecutive;
  private boolean armed = true;

  WatchTiming(boolean thresholdEnabled, int limit, boolean intervalEnabled, int minutes, long now) {
    this.thresholdEnabled = thresholdEnabled;
    this.limit = limit;
    this.intervalMs = intervalEnabled ? minutes * 60_000L : 0;
    nextSample = now + SAMPLE_MS;
    nextInterval = intervalEnabled ? now + intervalMs : Long.MAX_VALUE;
  }

  boolean sampleDue(long now) { return now >= nextSample; }
  long delay(long now) { return Math.max(1L, Math.min(nextInterval, nextSample) - now); }
  boolean isArmed() { return armed; }

  int tick(long now, double used, long lastThreshold) {
    int reasons = 0;
    if (intervalMs > 0 && now >= nextInterval) {
      reasons |= INTERVAL;
      // Advance past any missed deadlines without drifting from the original cadence.
      nextInterval = now + intervalMs - (now - nextInterval) % intervalMs;
    }
    if (!sampleDue(now)) return reasons;
    nextSample = now + SAMPLE_MS;
    if (!thresholdEnabled || !(used >= 0 && used <= 100)) {
      consecutive = 0;
      return reasons;
    }
    if (used < limit) {
      consecutive = 0;
      // Thresholds near zero still need a reachable recovery range.
      if (used <= limit - 3 || limit <= 3) armed = true;
      return reasons;
    }
    if (armed) {
      consecutive = Math.min(3, consecutive + 1);
      long cooldownStart = lastThreshold > now ? 0 : lastThreshold;
      if (consecutive == 3 && (lastThreshold < 0 || now - cooldownStart >= COOLDOWN_MS)) {
        reasons |= THRESHOLD;
        armed = false;
        consecutive = 0;
      }
    }
    return reasons;
  }
}

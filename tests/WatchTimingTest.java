package dev.pagetrim.app;

public final class WatchTimingTest {
  private static final int T = WatchTiming.THRESHOLD, I = WatchTiming.INTERVAL;
  private static final long M = 60_000;
  private static void eq(long expected, long actual) {
    if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
  }

  public static void main(String[] args) {
    // Interval-only operation does not depend on a memory reading or a threshold cooldown.
    WatchTiming interval = new WatchTiming(false, 90, true, 1, 100);
    eq(30_000, interval.delay(100));
    eq(0, interval.tick(30_100, Double.NaN, 100));
    eq(I, interval.tick(60_100, Double.NaN, 100));
    eq(I, interval.tick(120_100, 0, 119_999));
    eq(I, interval.tick(370_100, 0, 370_000)); // missed several periods
    eq(0, interval.tick(370_101, 0, -1));      // no backlog
    eq(0, interval.tick(400_100, 0, -1));
    eq(20_000, interval.delay(400_100));       // next interval anchored to start
    eq(I, interval.tick(420_100, 0, -1));

    // Intersecting conditions produce one event with both reasons. Equality is inclusive.
    WatchTiming both = new WatchTiming(true, 90, true, 1, 0);
    eq(0, both.tick(30_000, 89.99, -1));
    eq(I, both.tick(M, 90, -1));
    eq(0, both.tick(90_000, 91, -1));
    eq(T | I, both.tick(2*M, 90, -1));
    eq(0, both.tick(150_000, 88, 2*M));      // two points of recovery do not rearm
    eq(I, both.tick(3*M, 90, 2*M));
    eq(0, both.tick(210_000, 87, 2*M));      // three points recover
    eq(I, both.tick(4*M, 99, 2*M));
    eq(0, both.tick(270_000, 99, 2*M));
    eq(I, both.tick(5*M, 99, 2*M));          // threshold cooldown still applies
    eq(T | I, both.tick(32*M, 99, 2*M));

    // Valid samples must be consecutive, at least 30s apart; delayed sampling only counts once.
    WatchTiming threshold = new WatchTiming(true, 99, false, 1440, 0);
    eq(0, threshold.tick(10*M, 99.1, -1));
    eq(0, threshold.tick(10*M + 1, 100, -1));
    eq(0, threshold.tick(11*M, Double.NaN, -1));
    eq(0, threshold.tick(12*M, 100, -1));
    eq(0, threshold.tick(13*M, 100, -1));
    eq(T, threshold.tick(14*M, 99, -1));
    eq(0, threshold.tick(90*M, 100, -1));     // no repeat without recovery

    WatchTiming edge = new WatchTiming(true, 1, false, 1, 0);
    eq(0, edge.tick(M, 1, -1));
    eq(0, edge.tick(2*M, 1, -1));
    eq(T, edge.tick(3*M, 1, -1));
    eq(0, edge.tick(4*M, 0.75, 3*M));         // reachable recovery for thresholds 1–3
    eq(0, edge.tick(31*M, 1.1, 3*M));
    eq(0, edge.tick(32*M, 1.1, 3*M));
    eq(T, edge.tick(33*M, 1.1, 3*M));
    WatchTiming day = new WatchTiming(false, 99, true, 1440, 0);
    eq(0, day.tick(1439*M, Double.NaN, -1));
    eq(I, day.tick(1440*M, Double.NaN, -1));
    WatchTiming reboot = new WatchTiming(true, 90, false, 30, 0);
    eq(0, reboot.tick(M, 100, 40*M));
    eq(0, reboot.tick(2*M, 100, 40*M));
    eq(0, reboot.tick(3*M, 100, 40*M));
    eq(T, reboot.tick(30*M, 100, 40*M));
    System.out.println("Watch timing tests passed");
  }
}

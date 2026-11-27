package dev.pagetrim.app;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.EnumSet;

public final class PublicMemoryTest {
  private static void expect(String expected, String actual) {
    if (!expected.equals(actual)) throw new AssertionError(actual + " != " + expected);
  }

  public static void main(String[] args) throws Exception {
    PublicMemorySample start = new PublicMemorySample();
    start.elapsed = 1000;
    start.readCounters(new BufferedReader(new StringReader(
        "some avg10=10.5 total=70000\nfull avg10=12.5 total=1000\n")), true);
    start.readCounters(new BufferedReader(new StringReader(
        "pswpin 10\npswpout 0\npgmajfault 18014398509481985\n")), false);
    PublicMemorySample now = new PublicMemorySample();
    now.elapsed = 3000;
    now.readCounters(new BufferedReader(new StringReader(
        "some avg10=0.02 total=270000\nfull avg10=0 total=0\nother total=40\n")), true);
    now.readCounters(new BufferedReader(new StringReader(
        "pswpin 9223372036854775808\npswpout -3\npgmajfault 18014398509481986\nunknown 5\n")), false);

    expect("区间 0.200 秒（10.00%）", now.counter("some", start, true));
    expect("计数已重置，请重设起点", now.counter("full", start, true));
    expect("区间 1", now.counter("pgmajfault", start, false));
    expect("未提供", now.counter("pswpin", start, false));
    expect("未提供", now.counter("pswpout", start, false));
    expect("未提供", now.counter("other", start, true));
    expect("累计 0.000 秒", now.counter("full", null, true));
    now.elapsed = start.elapsed;
    expect("区间 0.200 秒", now.counter("some", start, true));
    String report = now.report(EnumSet.of(PublicMemorySample.Section.PRESSURE), start);
    if (report.contains("换入") || report.contains("运存阈值")) throw new AssertionError(report);
    System.out.println("Public memory counters: partial access, reset, units, large deltas and section filters passed.");
  }
}

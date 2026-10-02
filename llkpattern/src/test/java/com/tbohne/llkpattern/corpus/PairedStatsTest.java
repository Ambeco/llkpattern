package com.tbohne.llkpattern.corpus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

public class PairedStatsTest {
  private static final List<String> NAMES = Arrays.asList("a", "b");
  private static final List<Integer> ROWS = Arrays.asList(10, 20);

  /** Samples for kind 0 only (compile), llk = trueRatio * regex with multiplicative noise. */
  private static List<PairedBench.Sample> synthetic(double trueRatio, int blocks, int rounds,
      double noise, long seed) {
    Random rnd = new Random(seed);
    List<PairedBench.Sample> out = new ArrayList<>();
    for (int block = 0; block < blocks; block++) {
      double blockDrift = Math.exp(rnd.nextGaussian() * 0.01);
      for (int round = 0; round < rounds; round++) {
        for (int b = 0; b < 2; b++) {
          double regex = 1000 * (b + 1) * Math.exp(rnd.nextGaussian() * noise);
          double llk = trueRatio * blockDrift * 1000 * (b + 1) * Math.exp(rnd.nextGaussian() * noise);
          out.add(new PairedBench.Sample(0, b, block, round, llk, regex, false));
        }
      }
    }
    return out;
  }

  private static PairedStats.Entry find(List<PairedStats.Entry> entries, String key) {
    for (PairedStats.Entry e : entries) {
      if (e.key.equals(key)) {
        return e;
      }
    }
    throw new AssertionError("no entry " + key + " in " + entries.size() + " entries");
  }

  @Test
  public void recoversTrueRatioAndIntervalCoversIt() {
    List<PairedStats.Entry> entries =
        PairedStats.summarize(synthetic(2.0, 6, 400, 0.05, 1), NAMES, ROWS);
    PairedStats.Entry all = find(entries, "compile/ALL");
    assertEquals(2.0, all.ratio, 0.05);
    assertTrue("CI [" + all.ciLow + "," + all.ciHigh + "]", all.ciLow <= 2.0 && 2.0 <= all.ciHigh);
    assertEquals(6, all.blocks);
    assertEquals(2.0, find(entries, "compile/a").ratio, 0.1);
    assertEquals(2.0, find(entries, "compile/b").ratio, 0.1);
  }

  @Test
  public void detectsATwoPercentRegressionBetweenTwoRuns() {
    Map<String, String> meta = new LinkedHashMap<>();
    String before = PairedStats.toJson(
        PairedStats.summarize(synthetic(2.00, 6, 400, 0.05, 2), NAMES, ROWS), meta);
    String after = PairedStats.toJson(
        PairedStats.summarize(synthetic(2.06, 6, 400, 0.05, 3), NAMES, ROWS), meta);
    Map<String, double[]> a = PairedStats.parseJson(before);
    Map<String, double[]> b = PairedStats.parseJson(after);
    String report = PairedStats.compare(a, b);
    String allLine = null;
    for (String line : report.split("\n")) {
      if (line.startsWith("compile/ALL")) {
        allLine = line;
      }
    }
    assertTrue(report, allLine != null && allLine.contains("<--"));
  }

  @Test
  public void identicalRunsDoNotFlagTheAggregate() {
    Map<String, String> meta = new LinkedHashMap<>();
    Map<String, double[]> a = PairedStats.parseJson(PairedStats.toJson(
        PairedStats.summarize(synthetic(2.0, 6, 400, 0.05, 4), NAMES, ROWS), meta));
    Map<String, double[]> b = PairedStats.parseJson(PairedStats.toJson(
        PairedStats.summarize(synthetic(2.0, 6, 400, 0.05, 5), NAMES, ROWS), meta));
    for (String line : PairedStats.compare(a, b).split("\n")) {
      assertTrue(line, !line.startsWith("compile/ALL") || !line.contains("<--"));
    }
  }

  @Test
  public void trimmedMeanIgnoresOutlierChains() {
    List<PairedBench.Sample> samples = synthetic(2.0, 3, 100, 0.01, 6);
    samples.add(new PairedBench.Sample(0, 0, 0, 1000, 1e9, 1.0, false));
    PairedStats.Entry e = find(PairedStats.summarize(samples, NAMES, ROWS), "compile/a");
    assertEquals(2.0, e.ratio, 0.05);
  }

  @Test
  public void gcFlaggedChainsAreExcludedFromTheNoGcRatio() {
    List<PairedBench.Sample> samples = new ArrayList<>();
    for (int round = 0; round < 100; round++) {
      boolean gc = round % 2 == 0;
      samples.add(new PairedBench.Sample(0, 0, 0, round, gc ? 4000 : 2000, 1000, gc));
      samples.add(new PairedBench.Sample(0, 1, 0, round, 2000, 1000, false));
    }
    PairedStats.Entry e = find(PairedStats.summarize(samples, NAMES, ROWS), "compile/a");
    assertEquals(2.0, e.ratioExcludingGc, 1e-9);
    assertEquals(0.5, e.gcChainFraction, 1e-9);
    assertTrue(e.ratio > 2.5);
  }

  @Test
  public void sampleTsvRoundTrips() {
    PairedBench.Sample s = new PairedBench.Sample(1, 3, 2, 17, 123.456, 78.9, true);
    PairedBench.Sample back = PairedBench.Sample.fromTsv(s.toTsv());
    assertEquals(1, back.kind);
    assertEquals(3, back.bucket);
    assertEquals(17, back.round);
    assertEquals(123.456, back.llkNs, 1e-3);
    assertTrue(back.gc);
  }

  @Test
  public void allAggregateIsTimeWeightedNotMeanOfRatios() {
    List<PairedBench.Sample> samples = new ArrayList<>();
    for (int round = 0; round < 50; round++) {
      samples.add(new PairedBench.Sample(0, 0, 0, round, 9000, 1000, false)); // 9x but tiny
      samples.add(new PairedBench.Sample(0, 1, 0, round, 1000, 9000, false)); // 1/9x but huge
    }
    PairedStats.Entry all = find(PairedStats.summarize(samples, NAMES, ROWS), "compile/ALL");
    assertEquals(1.0, all.ratio, 1e-9);
  }
}

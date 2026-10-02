package com.tbohne.llkpattern.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Interleaved ("paired") timing of {@code java.util.regex} against {@code Ll1Pattern}, shared by the
 * desktop runner ({@code PairedRunner}) and the Android instrumentation test.
 *
 * <p>Why: timing regex and llk in separate blocks minutes apart lets background load and thermal
 * drift hit numerator and denominator differently, and that noise (not llk's own speed) dominated
 * the ratio. Here, for every (feature bucket, compile|match) the passes run as a <b>chain</b>
 * {@code R L R L R ... L R}: each llk pass is divided by the mean of the two regex passes adjacent
 * to it in time, so drift cancels to first order. Chains for every bucket repeat round after round,
 * so each bucket sees the same spread of conditions.
 *
 * <p>Plain Java 8, no dependencies (compiled into both the desktop and Android builds).
 */
public final class PairedBench {
  private PairedBench() {}

  public interface Pass {
    void run();
  }

  /** Total GCs so far on this VM; a chain during which it moved is flagged. */
  public interface GcCounter {
    long count();
  }

  public static final int COMPILE = 0;
  public static final int MATCH = 1;
  public static final String[] KIND_NAMES = {"compile", "match"};

  /** One feature bucket: its rows' four passes, indexed by {@link #COMPILE}/{@link #MATCH}. Each
   *  pass runs every row of the bucket once. */
  public static final class Bucket {
    public final String name;
    public final int rowCount;
    public final Pass[] regex;
    public final Pass[] llk;

    public Bucket(String name, int rowCount, Pass[] regex, Pass[] llk) {
      this.name = name;
      this.rowCount = rowCount;
      this.regex = regex;
      this.llk = llk;
    }
  }

  /** One chain's result: llk and (neighbour-averaged) regex time per single bucket pass. */
  public static final class Sample {
    public final int kind;
    public final int bucket;
    public final int block;
    public final int round;
    public final double llkNs;
    public final double regexNs;
    public final boolean gc;

    public Sample(int kind, int bucket, int block, int round, double llkNs, double regexNs,
        boolean gc) {
      this.kind = kind;
      this.bucket = bucket;
      this.block = block;
      this.round = round;
      this.llkNs = llkNs;
      this.regexNs = regexNs;
      this.gc = gc;
    }

    public Sample withBlock(int newBlock) {
      return new Sample(kind, bucket, newBlock, round, llkNs, regexNs, gc);
    }

    public String toTsv() {
      return String.format(Locale.ROOT, "%d\t%d\t%d\t%d\t%.3f\t%.3f\t%d", kind, bucket, block,
          round, llkNs, regexNs, gc ? 1 : 0);
    }

    public static Sample fromTsv(String line) {
      String[] f = line.split("\t");
      if (f.length != 7) {
        throw new IllegalArgumentException(
            "Malformed Sample TSV line (expected 7 tab-separated fields, got " + f.length + "): '"
                + line + "'");
      }
      return new Sample(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
          Integer.parseInt(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]),
          f[6].equals("1"));
    }
  }

  /** A timing unit shorter than this is dominated by timer resolution/overhead, so small buckets run
   *  their pass several times per timing. */
  private static final long MIN_TIMED_NS = 100_000;
  private static final int MAX_REPS = 20_000;

  /**
   * @param warmupRounds untimed interleaved rounds before calibration and measurement
   * @param rounds measured rounds
   * @param chainPairs llk passes per chain (each is paired with its two adjacent regex passes)
   * @param blockCount measured rounds are split into this many contiguous blocks (Android: the
   *     substitute for forks); desktop runs use 1 and re-tag blocks per fork
   */
  public static List<Sample> run(List<Bucket> buckets, GcCounter gc, int warmupRounds, int rounds,
      int chainPairs, int blockCount) {
    int[][] reps = new int[2][buckets.size()];
    for (int[] row : reps) {
      java.util.Arrays.fill(row, 1);
    }
    for (int w = 0; w < warmupRounds; w++) {
      for (int kind = 0; kind < 2; kind++) {
        for (int b = 0; b < buckets.size(); b++) {
          Bucket bucket = buckets.get(b);
          for (int i = 0; i < chainPairs; i++) {
            bucket.regex[kind].run();
            bucket.llk[kind].run();
          }
        }
      }
      if (w == warmupRounds / 2) {
        calibrate(buckets, reps);
      }
    }
    calibrate(buckets, reps);

    List<Sample> samples = new ArrayList<>(rounds * 2 * buckets.size());
    for (int round = 0; round < rounds; round++) {
      int block = (int) ((long) round * blockCount / rounds);
      for (int kind = 0; kind < 2; kind++) {
        for (int b = 0; b < buckets.size(); b++) {
          samples.add(chain(buckets.get(b), kind, b, block, round, reps[kind][b], chainPairs, gc));
        }
      }
    }
    return samples;
  }

  /** Picks each (kind, bucket)'s repetition count from a median of its own single-pass times. */
  private static void calibrate(List<Bucket> buckets, int[][] reps) {
    for (int kind = 0; kind < 2; kind++) {
      for (int b = 0; b < buckets.size(); b++) {
        long[] t = new long[5];
        for (int i = 0; i < t.length; i++) {
          long start = System.nanoTime();
          buckets.get(b).llk[kind].run();
          t[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(t);
        long median = Math.max(1, t[t.length / 2]);
        reps[kind][b] = (int) Math.max(1, Math.min(MAX_REPS, (MIN_TIMED_NS + median - 1) / median));
      }
    }
  }

  private static Sample chain(Bucket bucket, int kind, int bucketIndex, int block, int round,
      int reps, int chainPairs, GcCounter gc) {
    Pass regex = bucket.regex[kind];
    Pass llk = bucket.llk[kind];
    long gcBefore = gc.count();
    double llkSum = 0;
    double regexNeighbourSum = 0;
    long previousRegex = timed(regex, reps);
    for (int i = 0; i < chainPairs; i++) {
      long l = timed(llk, reps);
      long r = timed(regex, reps);
      llkSum += l;
      regexNeighbourSum += (previousRegex + r) / 2.0;
      previousRegex = r;
    }
    boolean gcSeen = gc.count() != gcBefore;
    double perPass = (double) chainPairs * reps;
    return new Sample(kind, bucketIndex, block, round, llkSum / perPass,
        regexNeighbourSum / perPass, gcSeen);
  }

  private static long timed(Pass pass, int reps) {
    long start = System.nanoTime();
    for (int i = 0; i < reps; i++) {
      pass.run();
    }
    return System.nanoTime() - start;
  }
}

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

  /** Per-VM counters sampled around each chain: GCs so far (count and cumulative collection
   *  milliseconds) and the calling thread's CPU time. CPU time next to wall time shows how much of a
   *  pass the thread was actually running (vs descheduled by other apps), and a chain during which
   *  the GC count moved is flagged. */
  public interface Env {
    long gcCount();

    long gcMillis();

    long threadCpuNanos();

    /** Called at the start of every measured round (lets an environment logger line its own timeline
     *  up with round numbers). */
    void onRound(int round);
  }

  public static final int COMPILE = 0;
  public static final int MATCH = 1;
  /** Kinds 2 and 3 are the same work timed in BLOCKS (all llk passes, then all regex passes) instead
   *  of interleaved -- the control for "does interleaving itself change the ratio" (GC/cache
   *  attribution). */
  public static final String[] KIND_NAMES =
      {"compile", "match", "compile-blocked", "match-blocked"};

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

  /** One chain's result: llk and (neighbour-averaged) regex time per single bucket pass, wall and
   *  thread-CPU, plus the GCs seen during the chain. */
  public static final class Sample {
    public final int kind;
    public final int bucket;
    public final int block;
    public final int round;
    public final double llkNs;
    public final double regexNs;
    public final boolean gc;
    public final double llkCpuNs;
    public final double regexCpuNs;
    public final int gcCount;
    public final int gcMillis;

    public Sample(int kind, int bucket, int block, int round, double llkNs, double regexNs,
        boolean gc, double llkCpuNs, double regexCpuNs, int gcCount, int gcMillis) {
      this.kind = kind;
      this.bucket = bucket;
      this.block = block;
      this.round = round;
      this.llkNs = llkNs;
      this.regexNs = regexNs;
      this.gc = gc;
      this.llkCpuNs = llkCpuNs;
      this.regexCpuNs = regexCpuNs;
      this.gcCount = gcCount;
      this.gcMillis = gcMillis;
    }

    /** Wall-time-only sample (CPU time unknown, recorded as NaN). */
    public Sample(int kind, int bucket, int block, int round, double llkNs, double regexNs,
        boolean gc) {
      this(kind, bucket, block, round, llkNs, regexNs, gc, Double.NaN, Double.NaN, gc ? 1 : 0, 0);
    }

    public Sample withBlock(int newBlock) {
      return new Sample(kind, bucket, newBlock, round, llkNs, regexNs, gc, llkCpuNs, regexCpuNs,
          gcCount, gcMillis);
    }

    public String toTsv() {
      return String.format(Locale.ROOT, "%d\t%d\t%d\t%d\t%.3f\t%.3f\t%d\t%.3f\t%.3f\t%d\t%d", kind,
          bucket, block, round, llkNs, regexNs, gc ? 1 : 0, llkCpuNs, regexCpuNs, gcCount,
          gcMillis);
    }

    public static Sample fromTsv(String line) {
      String[] f = line.split("\t");
      if (f.length != 7 && f.length != 11) {
        throw new IllegalArgumentException(
            "Malformed Sample TSV line (expected 7 or 11 tab-separated fields, got " + f.length
                + "): '" + line + "'");
      }
      if (f.length == 7) {
        return new Sample(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
            Integer.parseInt(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]),
            f[6].equals("1"));
      }
      return new Sample(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
          Integer.parseInt(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]),
          f[6].equals("1"), Double.parseDouble(f[7]), Double.parseDouble(f[8]),
          Integer.parseInt(f[9]), Integer.parseInt(f[10]));
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
   * @param alsoBlocked after each interleaved chain also time the same work as {@code 2*chainPairs}
   *     llk passes then {@code 2*chainPairs} regex passes (order alternating per round), recorded as
   *     kinds 2/3 -- the non-interleaved control
   */
  public static List<Sample> run(List<Bucket> buckets, Env env, int warmupRounds, int rounds,
      int chainPairs, int blockCount, boolean alsoBlocked) {
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

    List<Sample> samples = new ArrayList<>(rounds * 4 * buckets.size());
    for (int round = 0; round < rounds; round++) {
      int block = (int) ((long) round * blockCount / rounds);
      env.onRound(round);
      for (int kind = 0; kind < 2; kind++) {
        for (int b = 0; b < buckets.size(); b++) {
          Bucket bucket = buckets.get(b);
          samples.add(chain(bucket, kind, b, block, round, reps[kind][b], chainPairs, env));
          if (alsoBlocked) {
            samples.add(blockedChain(bucket, kind, b, block, round, reps[kind][b], 2 * chainPairs,
                env, round % 2 == 0));
          }
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

  private static final class Timing {
    long wall;
    long cpu;
  }

  private static void time(Pass pass, int reps, Env env, Timing out) {
    long cpu0 = env.threadCpuNanos();
    long wall0 = System.nanoTime();
    for (int i = 0; i < reps; i++) {
      pass.run();
    }
    out.wall = System.nanoTime() - wall0;
    out.cpu = env.threadCpuNanos() - cpu0;
  }

  private static Sample chain(Bucket bucket, int kind, int bucketIndex, int block, int round,
      int reps, int chainPairs, Env env) {
    Pass regex = bucket.regex[kind];
    Pass llk = bucket.llk[kind];
    long gcBefore = env.gcCount();
    long gcMsBefore = env.gcMillis();
    double llkSum = 0;
    double llkCpu = 0;
    double regexSum = 0;
    double regexCpu = 0;
    Timing t = new Timing();
    time(regex, reps, env, t);
    long prevWall = t.wall;
    long prevCpu = t.cpu;
    for (int i = 0; i < chainPairs; i++) {
      time(llk, reps, env, t);
      llkSum += t.wall;
      llkCpu += t.cpu;
      time(regex, reps, env, t);
      regexSum += (prevWall + t.wall) / 2.0;
      regexCpu += (prevCpu + t.cpu) / 2.0;
      prevWall = t.wall;
      prevCpu = t.cpu;
    }
    long gcs = env.gcCount() - gcBefore;
    double perPass = (double) chainPairs * reps;
    return new Sample(kind, bucketIndex, block, round, llkSum / perPass, regexSum / perPass,
        gcs != 0, llkCpu / perPass, regexCpu / perPass, (int) gcs,
        (int) (env.gcMillis() - gcMsBefore));
  }

  private static Sample blockedChain(Bucket bucket, int kind, int bucketIndex, int block, int round,
      int reps, int passes, Env env, boolean llkFirst) {
    long gcBefore = env.gcCount();
    long gcMsBefore = env.gcMillis();
    double[] sums = new double[4]; // llkWall, llkCpu, regexWall, regexCpu
    Timing t = new Timing();
    for (int phase = 0; phase < 2; phase++) {
      boolean llkPhase = (phase == 0) == llkFirst;
      Pass pass = llkPhase ? bucket.llk[kind] : bucket.regex[kind];
      for (int i = 0; i < passes; i++) {
        time(pass, reps, env, t);
        sums[llkPhase ? 0 : 2] += t.wall;
        sums[llkPhase ? 1 : 3] += t.cpu;
      }
    }
    long gcs = env.gcCount() - gcBefore;
    double perPass = (double) passes * reps;
    return new Sample(2 + kind, bucketIndex, block, round, sums[0] / perPass, sums[2] / perPass,
        gcs != 0, sums[1] / perPass, sums[3] / perPass, (int) gcs,
        (int) (env.gcMillis() - gcMsBefore));
  }
}

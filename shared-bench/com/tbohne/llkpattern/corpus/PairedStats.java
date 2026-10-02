package com.tbohne.llkpattern.corpus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns {@link PairedBench.Sample}s into llk/regex ratios with confidence intervals, and compares
 * two result files. Plain Java 8, shared with the Android build.
 *
 * <p>Statistics: passes within one VM run are autocorrelated (drift, JIT, heap state), so a
 * t-interval over thousands of chains would be falsely tight. Instead each <b>block</b> (a desktop
 * fork, or a contiguous slice of an Android run) yields ONE estimate -- the 10%-trimmed mean of its
 * chains' log(llk/regex) -- and the confidence interval is a t-interval across those block
 * estimates, in log space (so the interval is multiplicative and symmetric in log).
 */
public final class PairedStats {
  private PairedStats() {}

  public static final String ALL = "ALL";
  private static final double TRIM = 0.10;
  private static final double[] T95 = {
      12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160,
      2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056,
      2.052, 2.048, 2.045, 2.042};

  /** One row of the report: a (kind, bucket) or (kind, ALL). */
  public static final class Entry {
    public final String key; // "compile/ALL", "match/lookaround", ...
    public final int rows;
    public final int blocks;
    public final int chains;
    public final double gcChainFraction;
    public final double logMean;
    public final double logSe;
    public final double ratio;
    public final double ciLow;
    public final double ciHigh;
    public final double ratioExcludingGc;
    public final double medianChainRatio;
    public final double llkMs;
    public final double regexMs;

    Entry(String key, int rows, int blocks, int chains, double gcChainFraction, double logMean,
        double logSe, double ci, double ratioExcludingGc, double medianChainRatio, double llkMs,
        double regexMs) {
      this.key = key;
      this.rows = rows;
      this.blocks = blocks;
      this.chains = chains;
      this.gcChainFraction = gcChainFraction;
      this.logMean = logMean;
      this.logSe = logSe;
      this.ratio = Math.exp(logMean);
      this.ciLow = Math.exp(logMean - ci);
      this.ciHigh = Math.exp(logMean + ci);
      this.ratioExcludingGc = ratioExcludingGc;
      this.medianChainRatio = medianChainRatio;
      this.llkMs = llkMs;
      this.regexMs = regexMs;
    }
  }

  public static List<Entry> summarize(List<PairedBench.Sample> samples, List<String> bucketNames,
      List<Integer> bucketRows) {
    List<Entry> entries = new ArrayList<>();
    for (int kind = 0; kind < 2; kind++) {
      // (round-key) -> per-bucket samples, for the ALL aggregate and for per-bucket entries.
      List<List<PairedBench.Sample>> perBucket = new ArrayList<>();
      for (int b = 0; b < bucketNames.size(); b++) {
        perBucket.add(new ArrayList<PairedBench.Sample>());
      }
      for (PairedBench.Sample s : samples) {
        if (s.kind == kind) {
          perBucket.get(s.bucket).add(s);
        }
      }
      String prefix = PairedBench.KIND_NAMES[kind] + "/";
      int totalRows = 0;
      for (int rows : bucketRows) {
        totalRows += rows;
      }
      entries.add(entry(prefix + ALL, totalRows, aggregateAcrossBuckets(perBucket)));
      for (int b = 0; b < bucketNames.size(); b++) {
        List<Chain> chains = new ArrayList<>();
        for (PairedBench.Sample s : perBucket.get(b)) {
          chains.add(new Chain(s.block, s.llkNs, s.regexNs, s.gc));
        }
        entries.add(entry(prefix + bucketNames.get(b), bucketRows.get(b), chains));
      }
    }
    return entries;
  }

  private static final class Chain {
    final int block;
    final double llkNs;
    final double regexNs;
    final boolean gc;

    Chain(int block, double llkNs, double regexNs, boolean gc) {
      this.block = block;
      this.llkNs = llkNs;
      this.regexNs = regexNs;
      this.gc = gc;
    }
  }

  /** The whole-corpus chain for each (block, round): sum of llk times over buckets / sum of regex
   *  times, so the aggregate is a time-weighted ratio, not a mean of per-bucket ratios. */
  private static List<Chain> aggregateAcrossBuckets(List<List<PairedBench.Sample>> perBucket) {
    Map<Long, double[]> byRound = new TreeMap<>();
    Map<Long, Boolean> gcByRound = new TreeMap<>();
    for (List<PairedBench.Sample> list : perBucket) {
      for (PairedBench.Sample s : list) {
        long key = ((long) s.block << 32) | (s.round & 0xffffffffL);
        double[] sums = byRound.get(key);
        if (sums == null) {
          sums = new double[2];
          byRound.put(key, sums);
          gcByRound.put(key, Boolean.FALSE);
        }
        sums[0] += s.llkNs;
        sums[1] += s.regexNs;
        if (s.gc) {
          gcByRound.put(key, Boolean.TRUE);
        }
      }
    }
    List<Chain> chains = new ArrayList<>();
    for (Map.Entry<Long, double[]> e : byRound.entrySet()) {
      chains.add(new Chain((int) (e.getKey() >> 32), e.getValue()[0], e.getValue()[1],
          gcByRound.get(e.getKey())));
    }
    return chains;
  }

  private static Entry entry(String key, int rows, List<Chain> chains) {
    int maxBlock = 0;
    for (Chain c : chains) {
      maxBlock = Math.max(maxBlock, c.block);
    }
    int blocks = maxBlock + 1;
    double[] est = blockEstimates(chains, blocks, false);
    double[] estNoGc = blockEstimates(chains, blocks, true);
    double mean = mean(est);
    double se = est.length > 1 ? sd(est) / Math.sqrt(est.length) : Double.NaN;
    double t = est.length > 1 ? (est.length - 2 < T95.length ? T95[est.length - 2] : 1.96) : Double.NaN;
    double[] all = new double[chains.size()];
    int gcCount = 0;
    double llk = 0;
    double regex = 0;
    for (int i = 0; i < all.length; i++) {
      Chain c = chains.get(i);
      all[i] = c.llkNs / c.regexNs;
      llk += c.llkNs;
      regex += c.regexNs;
      if (c.gc) {
        gcCount++;
      }
    }
    Arrays.sort(all);
    double median = all.length == 0 ? Double.NaN : all[all.length / 2];
    double n = Math.max(1, chains.size());
    return new Entry(key, rows, blocks, chains.size(), gcCount / n, mean, se, t * se,
        Math.exp(mean(estNoGc)), median, llk / n / 1e6, regex / n / 1e6);
  }

  private static double[] blockEstimates(List<Chain> chains, int blocks, boolean skipGc) {
    List<List<Double>> logs = new ArrayList<>();
    for (int b = 0; b < blocks; b++) {
      logs.add(new ArrayList<Double>());
    }
    for (Chain c : chains) {
      if (skipGc && c.gc) {
        continue;
      }
      logs.get(c.block).add(Math.log(c.llkNs / c.regexNs));
    }
    List<Double> estimates = new ArrayList<>();
    for (List<Double> l : logs) {
      if (l.isEmpty()) {
        continue;
      }
      double[] v = new double[l.size()];
      for (int i = 0; i < v.length; i++) {
        v[i] = l.get(i);
      }
      estimates.add(trimmedMean(v));
    }
    double[] out = new double[estimates.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = estimates.get(i);
    }
    return out;
  }

  private static double trimmedMean(double[] v) {
    Arrays.sort(v);
    int cut = (int) (v.length * TRIM);
    double sum = 0;
    int n = 0;
    for (int i = cut; i < v.length - cut; i++) {
      sum += v[i];
      n++;
    }
    return sum / n;
  }

  private static double mean(double[] v) {
    double sum = 0;
    for (double d : v) {
      sum += d;
    }
    return v.length == 0 ? Double.NaN : sum / v.length;
  }

  private static double sd(double[] v) {
    double m = mean(v);
    double sum = 0;
    for (double d : v) {
      sum += (d - m) * (d - m);
    }
    return Math.sqrt(sum / (v.length - 1));
  }

  /** One line per entry, flat so {@link #parseJson} can read it back with a regex. */
  public static String toJson(List<Entry> entries, Map<String, String> metadata) {
    StringBuilder sb = new StringBuilder("{\n");
    for (Map.Entry<String, String> m : metadata.entrySet()) {
      sb.append(String.format(Locale.ROOT, "  \"%s\": \"%s\",\n", m.getKey(), m.getValue()));
    }
    sb.append("  \"results\": {\n");
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      sb.append(String.format(Locale.ROOT,
          "    \"%s\": {\"logMean\": %.6f, \"logSe\": %.6f, \"ratio\": %.4f, \"ciLow\": %.4f, "
              + "\"ciHigh\": %.4f, \"ratioExcludingGc\": %.4f, \"medianChainRatio\": %.4f, "
              + "\"llkMs\": %.5f, \"regexMs\": %.5f, \"rows\": %d, \"blocks\": %d, \"chains\": %d, "
              + "\"gcChainFraction\": %.4f}%s\n",
          e.key, e.logMean, e.logSe, e.ratio, e.ciLow, e.ciHigh, e.ratioExcludingGc,
          e.medianChainRatio, e.llkMs, e.regexMs, e.rows, e.blocks, e.chains, e.gcChainFraction,
          i + 1 < entries.size() ? "," : ""));
    }
    sb.append("  }\n}\n");
    return sb.toString();
  }

  public static String toTable(List<Entry> entries) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format(Locale.ROOT, "%-26s %5s %-22s %8s %8s %8s %6s%n", "kind/bucket", "rows",
        "llk/regex [95% CI]", "noGC", "llk ms", "regex ms", "gc%"));
    for (Entry e : entries) {
      sb.append(String.format(Locale.ROOT, "%-26s %5d %6.3f [%5.3f, %5.3f] %8.3f %8.4f %8.4f %5.1f%n",
          e.key, e.rows, e.ratio, e.ciLow, e.ciHigh, e.ratioExcludingGc, e.llkMs, e.regexMs,
          100 * e.gcChainFraction));
    }
    return sb.toString();
  }

  private static final Pattern JSON_ENTRY =
      Pattern.compile("^\\s*\"([a-z]+/[^\"]+)\": \\{(.*)\\}", Pattern.MULTILINE);
  private static final double[] T99 = {
      63.657, 9.925, 5.841, 4.604, 4.032, 3.707, 3.499, 3.355, 3.250, 3.169, 3.106, 3.055, 3.012,
      2.977, 2.947, 2.921, 2.898, 2.878, 2.861, 2.845, 2.831, 2.819, 2.807, 2.797, 2.787, 2.779,
      2.771, 2.763, 2.756, 2.750};

  private static double field(String body, String name) {
    Matcher m = Pattern.compile("\"" + name + "\": ([-0-9.eENaN]+)").matcher(body);
    if (!m.find()) {
      throw new IllegalArgumentException("Missing field '" + name + "' in entry: " + body);
    }
    return Double.parseDouble(m.group(1));
  }

  /** key -> {logMean, logSe, blocks}. */
  public static Map<String, double[]> parseJson(String json) {
    Map<String, double[]> out = new TreeMap<>();
    Matcher m = JSON_ENTRY.matcher(json);
    while (m.find()) {
      String body = m.group(2);
      out.put(m.group(1),
          new double[] {field(body, "logMean"), field(body, "logSe"), field(body, "blocks")});
    }
    if (out.isEmpty()) {
      throw new IllegalArgumentException(
          "No paired-benchmark entries found -- is this a *_paired_ratio_results.json file (or the "
              + "Pixel's corpus_benchmark_results.json \"paired\" section), not the older JMH JSON?");
    }
    return out;
  }

  /** Two-sided 99% t critical value for a Welch-Satterthwaite comparison of two block-mean
   *  estimates; the across-block SEs rest on only a handful of blocks, so a normal-theory cutoff
   *  would flag identical runs far too often. */
  static double flagThreshold(double[] a, double[] b) {
    double v1 = a[1] * a[1];
    double v2 = b[1] * b[1];
    double df = (v1 + v2) * (v1 + v2) / (v1 * v1 / (a[2] - 1) + v2 * v2 / (b[2] - 1));
    int idx = (int) Math.floor(df) - 1;
    if (Double.isNaN(df) || idx < 0) {
      return Double.POSITIVE_INFINITY;
    }
    return idx < T99.length ? T99[idx] : 2.576;
  }

  /** Per-key change of llk/regex from {@code before} to {@code after}, with a t-score from the two
   *  across-block standard errors; {@code <--} marks |t| above the 99% Welch critical value. With
   *  ~20 keys compared, expect an occasional lone false flag among the per-bucket rows; the
   *  {@code ALL} rows are the ones to act on. */
  public static String compare(Map<String, double[]> before, Map<String, double[]> after) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format(Locale.ROOT, "%-26s %9s %9s %8s %6s %6s%n", "kind/bucket", "before",
        "after", "change", "t", "crit"));
    for (Map.Entry<String, double[]> e : before.entrySet()) {
      double[] a = after.get(e.getKey());
      if (a == null) {
        continue;
      }
      double delta = a[0] - e.getValue()[0];
      double se = Math.sqrt(a[1] * a[1] + e.getValue()[1] * e.getValue()[1]);
      double t = delta / se;
      double crit = flagThreshold(e.getValue(), a);
      sb.append(String.format(Locale.ROOT, "%-26s %9.3f %9.3f %+7.1f%% %6.1f %6.2f%s%n", e.getKey(),
          Math.exp(e.getValue()[0]), Math.exp(a[0]), 100 * (Math.exp(delta) - 1), t, crit,
          Math.abs(t) > crit ? "  <--" : ""));
    }
    return sb.toString();
  }

  /** {@code PairedStats compare <before.json> <after.json>}. */
  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !args[0].equals("compare")) {
      throw new IllegalArgumentException(
          "Usage: PairedStats compare <before.json> <after.json> (did you mean the pairedCompare"
              + " Gradle task, -Pbefore=... -Pafter=...?)");
    }
    String a = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[1])),
        java.nio.charset.StandardCharsets.UTF_8);
    String b = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[2])),
        java.nio.charset.StandardCharsets.UTF_8);
    System.out.print(compare(parseJson(a), parseJson(b)));
  }
}

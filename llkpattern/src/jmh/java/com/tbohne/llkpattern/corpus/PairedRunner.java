package com.tbohne.llkpattern.corpus;

import com.tbohne.llkpattern.Ll1Pattern;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Interleaved llk-vs-regex timing over the golden corpus, split by regex feature (see {@link
 * PairedBench} for the method and {@link PairedStats} for the statistics). Replaces the old
 * "regex JMH run, then llk JMH run, minutes apart" ratio with one measured against the two regex
 * passes adjacent in time to each llk pass. Not JMH -- JMH can't alternate two benchmark methods
 * (its {@code @Group} runs them on concurrent threads) -- but it keeps JMH's structure where it
 * matters: each of several forked JVMs warms up and measures independently, since between-fork
 * variance dominated.
 *
 * <p>Parent mode (the default) spawns {@code forks} child JVMs, each of which writes its raw samples
 * as TSV; the parent tags them with the fork index (one statistics "block" per fork), and writes
 * {@code benchmarks/<machine>_paired_ratio_results.json} plus a console table. Run via {@code
 * ./gradlew :llkpattern:jmhPaired}. Compare two result files with {@code :llkpattern:pairedCompare}.
 *
 * <p>Args: {@code <machine> <benchmarksDir> <forks> <rounds> <chainPairs> <warmupRounds>
 * <injectPercent> [rawSamplesFile]}; a leading {@code --child <outFile>} runs one fork instead. {@code
 * injectPercent} re-runs that percent of each bucket's rows in the llk passes only -- a known,
 * source-free slowdown for validating that the method detects a small regression.
 */
public final class PairedRunner {
  private PairedRunner() {}

  private static final int MIN_BUCKET_ROWS = 40;
  private static final String BLACKHOLE_MAGIC =
      "Today's password is swordfish. I understand instantiating Blackholes directly is"
          + " dangerous.";

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("--child")) {
      child(Paths.get(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]),
          Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]));
      return;
    }
    if (args.length != 7 && args.length != 8) {
      throw new IllegalArgumentException(
          "Usage: PairedRunner <machine> <benchmarksDir> <forks> <rounds> <chainPairs>"
              + " <warmupRounds> <injectPercent> (did the jmhPaired Gradle task's args change"
              + " shape?)");
    }
    String machine = args[0];
    Path benchmarksDir = Paths.get(args[1]);
    int forks = Integer.parseInt(args[2]);
    int rounds = Integer.parseInt(args[3]);
    int chainPairs = Integer.parseInt(args[4]);
    int warmupRounds = Integer.parseInt(args[5]);
    int injectPercent = Integer.parseInt(args[6]);
    Path rawOut = args.length == 8 && !args[7].isEmpty() ? Paths.get(args[7]) : null;

    List<PairedBench.Sample> all = new ArrayList<>();
    for (int fork = 0; fork < forks; fork++) {
      Path out = Files.createTempFile("paired-fork-" + fork, ".tsv");
      try {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        // Fixed, large young generation: llk compile allocates ~3 MB per pass, and a GC landing
        // inside a regex pass would be charged to the wrong engine.
        cmd.add("-Xms2g");
        cmd.add("-Xmx2g");
        cmd.add("-Xmn1500m");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(PairedRunner.class.getName());
        cmd.add("--child");
        cmd.add(out.toString());
        cmd.add(Integer.toString(rounds));
        cmd.add(Integer.toString(chainPairs));
        cmd.add(Integer.toString(warmupRounds));
        cmd.add(Integer.toString(injectPercent));
        cmd.add(Integer.toString(fork));
        System.out.println("PairedRunner: fork " + (fork + 1) + "/" + forks);
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        if (p.waitFor() != 0) {
          throw new IllegalStateException("PairedRunner child fork " + fork + " failed (exit "
              + p.exitValue() + "); its stderr is above.");
        }
        for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
          if (!line.startsWith("#") && !line.isEmpty()) {
            all.add(PairedBench.Sample.fromTsv(line).withBlock(fork));
          }
        }
      } finally {
        Files.deleteIfExists(out);
      }
    }

    if (rawOut != null) {
      // Every chain, block = fork index; lets offline analysis re-slice forks/rounds.
      StringBuilder raw = new StringBuilder();
      for (PairedBench.Sample sample : all) {
        raw.append(sample.toTsv()).append(System.lineSeparator());
      }
      Files.write(rawOut, raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    // Bucket names/rows are recomputed here from the same deterministic corpus the children used.
    List<String> names = new ArrayList<>();
    List<Integer> rows = new ArrayList<>();
    for (Map.Entry<String, List<Integer>> e : bucketRowIndices(CorpusBenchmark.loadAgreesRows())
        .entrySet()) {
      names.add(e.getKey());
      rows.add(e.getValue().size());
    }
    List<PairedStats.Entry> entries = PairedStats.summarize(all, names, rows);
    Map<String, String> meta = new LinkedHashMap<>();
    meta.put("machine", machine);
    meta.put("captured", LocalDate.now().toString());
    meta.put("sourceHash", System.getProperty("llk.sourceHash", "unknown"));
    meta.put("forks", Integer.toString(forks));
    meta.put("roundsPerFork", Integer.toString(rounds));
    meta.put("chainPairs", Integer.toString(chainPairs));
    meta.put("injectPercent", Integer.toString(injectPercent));
    Path outFile = benchmarksDir.resolve(machine + "_paired_ratio_results.json");
    Files.write(outFile, PairedStats.toJson(entries, meta).getBytes(StandardCharsets.UTF_8));
    System.out.print(PairedStats.toTable(entries));
    System.out.println("PairedRunner: wrote " + outFile);
  }

  static Map<String, List<Integer>> bucketRowIndices(List<GoldenRow> rows) {
    List<String> patterns = new ArrayList<>();
    List<String> flags = new ArrayList<>();
    for (GoldenRow r : rows) {
      patterns.add(r.pattern);
      flags.add(r.flags);
    }
    return RowBuckets.group(patterns, flags, MIN_BUCKET_ROWS);
  }

  private static void child(Path outFile, int rounds, int chainPairs, int warmupRounds,
      int injectPercent, int fork) throws Exception {
    List<GoldenRow> rows = CorpusBenchmark.loadAgreesRows();
    Blackhole bh = new Blackhole(BLACKHOLE_MAGIC);
    List<Pattern> regexPatterns = new ArrayList<>(rows.size());
    List<Ll1Pattern> llkPatterns = new ArrayList<>(rows.size());
    for (GoldenRow row : rows) {
      regexPatterns.add(Pattern.compile(row.pattern, row.flagBits()));
      llkPatterns.add(Ll1Pattern.compile(row.pattern, row.flagBits()));
    }
    List<PairedBench.Bucket> buckets = new ArrayList<>();
    for (Map.Entry<String, List<Integer>> e : bucketRowIndices(rows).entrySet()) {
      int[] idx = new int[e.getValue().size()];
      for (int i = 0; i < idx.length; i++) {
        idx[i] = e.getValue().get(i);
      }
      int extra = (int) Math.ceil(idx.length * injectPercent / 100.0);
      buckets.add(new PairedBench.Bucket(e.getKey(), idx.length,
          new PairedBench.Pass[] {
              new RegexCompile(rows, idx, bh), new RegexMatch(rows, regexPatterns, idx, bh)},
          new PairedBench.Pass[] {
              new LlkCompile(rows, idx, extra, bh),
              new LlkMatch(rows, llkPatterns, idx, extra, bh)}));
    }
    final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    PairedBench.GcCounter gc = () -> {
      long n = 0;
      for (GarbageCollectorMXBean b : gcBeans) {
        n += b.getCollectionCount();
      }
      return n;
    };
    List<PairedBench.Sample> samples =
        PairedBench.run(buckets, gc, warmupRounds, rounds, chainPairs, 1);
    StringBuilder sb = new StringBuilder("# fork " + fork + "\n");
    for (PairedBench.Sample s : samples) {
      sb.append(s.toTsv()).append('\n');
    }
    Files.write(outFile, sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  // One class per (engine, kind), one instance per bucket: the call sites inside each class see
  // every bucket's rows, matching CorpusBenchmark's single shared call site per method.

  private static final class RegexCompile implements PairedBench.Pass {
    final List<GoldenRow> rows;
    final int[] idx;
    final Blackhole bh;

    RegexCompile(List<GoldenRow> rows, int[] idx, Blackhole bh) {
      this.rows = rows;
      this.idx = idx;
      this.bh = bh;
    }

    @Override
    public void run() {
      for (int i : idx) {
        GoldenRow row = rows.get(i);
        bh.consume(Pattern.compile(row.pattern, row.flagBits()));
      }
    }
  }

  private static final class LlkCompile implements PairedBench.Pass {
    final List<GoldenRow> rows;
    final int[] idx;
    final int extra;
    final Blackhole bh;

    LlkCompile(List<GoldenRow> rows, int[] idx, int extra, Blackhole bh) {
      this.rows = rows;
      this.idx = idx;
      this.extra = extra;
      this.bh = bh;
    }

    @Override
    public void run() {
      for (int i : idx) {
        GoldenRow row = rows.get(i);
        bh.consume(Ll1Pattern.compile(row.pattern, row.flagBits()));
      }
      for (int k = 0; k < extra; k++) {
        GoldenRow row = rows.get(idx[k]);
        bh.consume(Ll1Pattern.compile(row.pattern, row.flagBits()));
      }
    }
  }

  private static final class RegexMatch implements PairedBench.Pass {
    final List<GoldenRow> rows;
    final List<Pattern> patterns;
    final int[] idx;
    final Blackhole bh;

    RegexMatch(List<GoldenRow> rows, List<Pattern> patterns, int[] idx, Blackhole bh) {
      this.rows = rows;
      this.patterns = patterns;
      this.idx = idx;
      this.bh = bh;
    }

    @Override
    public void run() {
      for (int i : idx) {
        bh.consume(CorpusBenchmark.runRegexMatch(patterns.get(i), rows.get(i)));
      }
    }
  }

  private static final class LlkMatch implements PairedBench.Pass {
    final List<GoldenRow> rows;
    final List<Ll1Pattern> patterns;
    final int[] idx;
    final int extra;
    final Blackhole bh;

    LlkMatch(List<GoldenRow> rows, List<Ll1Pattern> patterns, int[] idx, int extra,
        Blackhole bh) {
      this.rows = rows;
      this.patterns = patterns;
      this.idx = idx;
      this.extra = extra;
      this.bh = bh;
    }

    @Override
    public void run() {
      for (int i : idx) {
        bh.consume(CorpusBenchmark.runLlkMatch(patterns.get(i), rows.get(i)));
      }
      for (int k = 0; k < extra; k++) {
        int i = idx[k];
        bh.consume(CorpusBenchmark.runLlkMatch(patterns.get(i), rows.get(i)));
      }
    }
  }
}

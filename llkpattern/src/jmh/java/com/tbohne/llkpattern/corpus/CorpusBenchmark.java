package com.tbohne.llkpattern.corpus;

import com.tbohne.llkpattern.Ll1Pattern;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares {@code java.util.regex} vs {@code Ll1Pattern} speed over the scraped-corpus golden
 * files (see {@code documents/remaining_work.md}'s "Scraped-corpus microbenchmark" entry for the
 * original design sketch this implements).
 *
 * <p>Only rows tagged {@code AGREES} (see {@link GoldenRow#status}) are used: comparing speed on a
 * row where the two engines disagree about *correctness* isn't a meaningful speed comparison.
 * Compile and match time are benchmarked separately (per row {@link #row(GoldenRow)}
 * {@code compile}/{@code match} methods below), since llk is expected to compile slower --
 * it builds a full dispatch graph upfront -- but may match faster per call; a combined number
 * would hide that story.
 *
 * <p>Each {@code @Benchmark} method's single "operation" is one pass over every {@code AGREES} row
 * in both golden files (rather than one row per operation) -- JMH still reports a real
 * average-time-per-operation with proper warmup/forking, this just makes "one operation" mean "one
 * full corpus pass" instead of introducing thousands of separate trivial benchmark methods.
 *
 * <p>Run via {@code ./gradlew :llkpattern:jmh}. Results print to the console and are also written
 * as JSON to {@code benchmarks/Intel-i7-9750H_corpus_benchmark_results.json} (named
 * after the desktop it's run on -- see llkpattern/build.gradle's {@code jmh {}} block) -- that file
 * is checked in as a baseline, so a deliberate benchmark run's
 * output should be committed and future runs diffed against it to spot regressions.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class CorpusBenchmark {
  private List<GoldenRow> agreesRows;

  /** Parallel to {@link #agreesRows}: each row's pattern precompiled once, for the match-only
   *  benchmarks (which must not also be timing compilation). */
  private List<Pattern> regexPatterns;
  private List<Ll1Pattern> llkPatterns;

  @Setup(Level.Trial)
  public void setUp() {
    agreesRows = new ArrayList<>();
    for (Path goldenFile : goldenFiles()) {
      for (GoldenRow row : GoldenTsv.read(goldenFile)) {
        // "AGREES" alone isn't enough: it also covers rows where both engines agree by both
        // throwing the same compile exception (e.g. a pattern that's invalid even for
        // java.util.regex) -- not something either engine's *speed* can be measured on. Only
        // rows where both actually compiled successfully are usable here.
        if (row.status.equals("AGREES")
            && row.regexCompileException.isEmpty()
            && row.llkCompileException.isEmpty()) {
          agreesRows.add(row);
        }
      }
    }
    if (agreesRows.isEmpty()) {
      throw new IllegalStateException(
          "No usable AGREES rows (both engines compiling successfully) found across "
              + goldenFiles() + " -- did the golden files move, or did every such row's status "
              + "change away from AGREES? CorpusBenchmark has nothing to measure without at "
              + "least one.");
    }

    regexPatterns = new ArrayList<>(agreesRows.size());
    llkPatterns = new ArrayList<>(agreesRows.size());
    for (GoldenRow row : agreesRows) {
      regexPatterns.add(Pattern.compile(row.pattern, row.flagBits()));
      llkPatterns.add(Ll1Pattern.compile(row.pattern, row.flagBits()));
    }
  }

  private static List<Path> goldenFiles() {
    // Mirrors OpenJdkBmpCorpusTest/OpenJdkSupplementaryCorpusTest's own golden-file paths -- see
    // those classes if this project adds more scraped-corpus sources (remaining_work.md).
    List<Path> paths = new ArrayList<>();
    paths.add(Paths.get("src", "test", "resources", "golden", "openjdk_bmp.tsv"));
    paths.add(Paths.get("src", "test", "resources", "golden", "openjdk_supplementary.tsv"));
    return paths;
  }

  @Benchmark
  public void regexCompile(Blackhole bh) {
    for (GoldenRow row : agreesRows) {
      bh.consume(Pattern.compile(row.pattern, row.flagBits()));
    }
  }

  @Benchmark
  public void llkCompile(Blackhole bh) {
    for (GoldenRow row : agreesRows) {
      bh.consume(Ll1Pattern.compile(row.pattern, row.flagBits()));
    }
  }

  @Benchmark
  public void regexMatch(Blackhole bh) {
    for (int i = 0; i < agreesRows.size(); i++) {
      bh.consume(runRegexMatch(regexPatterns.get(i), agreesRows.get(i)));
    }
  }

  @Benchmark
  public void llkMatch(Blackhole bh) {
    for (int i = 0; i < agreesRows.size(); i++) {
      bh.consume(runLlkMatch(llkPatterns.get(i), agreesRows.get(i)));
    }
  }

  private static boolean runRegexMatch(Pattern pattern, GoldenRow row) {
    java.util.regex.Matcher m = pattern.matcher(row.input);
    switch (row.mode) {
      case MATCHES:
        return m.matches();
      case LOOKING_AT:
        return m.lookingAt();
      case FIND:
        return m.find();
      default:
        throw new IllegalArgumentException("Unhandled GoldenRow.Mode " + row.mode);
    }
  }

  private static boolean runLlkMatch(Ll1Pattern pattern, GoldenRow row) {
    com.tbohne.llkpattern.Matcher m = pattern.matcher(row.input);
    switch (row.mode) {
      case MATCHES:
        return m.matches();
      case LOOKING_AT:
        return m.lookingAt();
      case FIND:
        return m.find();
      default:
        throw new IllegalArgumentException("Unhandled GoldenRow.Mode " + row.mode);
    }
  }
}

package com.tbohne.llkpattern.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import org.openjdk.jmh.profile.StackProfiler;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Re-runs one or more {@link CorpusBenchmark} methods under JMH's built-in {@link StackProfiler}
 * and writes each one's sampling text straight to {@code benchmarks/<machine>_<name>_sampling.txt}.
 * Invoked by the {@code jmhSampling} Gradle task ({@code llkpattern/build.gradle}) right after the
 * normal {@code jmh} task -- kept as a separate JMH run (rather than folded into the main {@code
 * jmh {}} block's profiler list) so the stack profiler's per-sample thread-interruption overhead
 * never perturbs the ms/op numbers written to {@code corpus_benchmark_results.json}. See
 * documents/notes.md's 2026-09-08/09 entries for that reasoning.
 *
 * <p>Uses JMH's Java API ({@link Runner}/{@link RunResult}) rather than shelling out to {@code
 * org.openjdk.jmh.Main} and scraping its console output -- {@link RunResult#getSecondaryResults()}
 * hands back the stack profiler's {@link Result} object directly, so the sampling text comes from
 * {@link Result#extendedInfo()} instead of a regex match against human-readable console formatting
 * that JMH makes no compatibility promises about.
 *
 * <p>Args: {@code <machineName> <benchmarksDir> <warmupIterations> <warmupTime> <iterations>
 * <measurementTime> <benchmarkName>...} -- {@code warmupTime}/{@code measurementTime} are JMH
 * duration strings (e.g. {@code "1s"}), passed through from the {@code jmh {}} block's own
 * {@code warmup}/{@code timeOnIteration} so this sampling run's per-iteration wall-clock budget
 * always matches the main timing run's, rather than silently falling back to JMH's 10s default
 * whenever that block's times are tuned.
 */
public final class SamplingRunner {
  private SamplingRunner() {}

  public static void main(String[] args) throws RunnerException, IOException {
    if (args.length < 7) {
      throw new IllegalArgumentException(
          "Usage: SamplingRunner <machineName> <benchmarksDir> <warmupIterations> <warmupTime>"
              + " <iterations> <measurementTime> <benchmarkName>... (did the jmhSampling Gradle"
              + " task's args change shape?)");
    }
    String machineName = args[0];
    Path benchmarksDir = Path.of(args[1]);
    int warmupIterations = Integer.parseInt(args[2]);
    String warmupTime = args[3];
    int iterations = Integer.parseInt(args[4]);
    String measurementTime = args[5];
    for (int i = 6; i < args.length; i++) {
      captureOneBenchmark(
          machineName, benchmarksDir, warmupIterations, warmupTime, iterations, measurementTime,
          args[i]);
    }
  }

  private static void captureOneBenchmark(
      String machineName, Path benchmarksDir, int warmupIterations, String warmupTime,
      int iterations, String measurementTime, String name)
      throws RunnerException, IOException {
    Options opts =
        new OptionsBuilder()
            .include(".*" + CorpusBenchmark.class.getSimpleName() + "\\." + name + "$")
            .addProfiler(StackProfiler.class, "lines=4;detailLine=true")
            .warmupIterations(warmupIterations)
            .warmupTime(TimeValue.fromString(warmupTime))
            .measurementIterations(iterations)
            .measurementTime(TimeValue.fromString(measurementTime))
            .forks(1)
            .build();
    Collection<RunResult> results = new Runner(opts).run();
    if (results.isEmpty()) {
      throw new IllegalStateException(
          "SamplingRunner: JMH returned no results for benchmark '"
              + name
              + "' -- was it renamed or removed from CorpusBenchmark? Did you mean one of its"
              + " other @Benchmark methods?");
    }
    RunResult result = results.iterator().next();
    // Keyed "<separator>stack" (JMH's internal scope separator, a middle dot -- not a plain
    // "stack") -- match by suffix instead of hardcoding that separator character.
    Result<?> stackResult =
        result.getSecondaryResults().entrySet().stream()
            .filter(e -> e.getKey().endsWith("stack"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    if (stackResult == null) {
      throw new IllegalStateException(
          "SamplingRunner: no 'stack' secondary result for benchmark '"
              + name
              + "' -- StackProfiler's result label may have changed upstream; actual secondary"
              + " result keys were: "
              + result.getSecondaryResults().keySet());
    }

    StringBuilder body = new StringBuilder();
    body.append("Desktop CPU-sampling profile of CorpusBenchmark.").append(name).append('\n');
    body.append("Device: ").append(machineName).append(" (this dev machine)\n");
    body.append("Captured: ")
        .append(LocalDate.now())
        .append(", via JMH's built-in `stack` profiler at 4-frame depth (this project's default\n");
    body.append(
        "  sampling depth -- see CLAUDE.md), auto-generated by the `jmhSampling` Gradle task\n");
    body.append(
        "  (llkpattern/build.gradle) -- see documents/notes.md for what code state this run"
            + " reflects.\n\n");
    body.append(result.getPrimaryResult()).append('\n');
    body.append(stackResult.extendedInfo());

    Path outFile = benchmarksDir.resolve(machineName + "_" + name + "_sampling.txt");
    Files.write(outFile, body.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println("SamplingRunner: wrote " + outFile);
  }
}

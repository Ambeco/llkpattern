package com.tbohne.llkpattern.corpus;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
// Sampling-profile-only imports (see the commented-out testZZSamplingProfileMatch/
// testZZSamplingProfileCompile blocks below) -- uncomment alongside those to re-enable them:
// import static org.junit.Assert.assertFalse;
// import static org.junit.Assume.assumeTrue;
// import android.os.Bundle;
// import java.util.HashMap;
// import java.util.concurrent.atomic.AtomicBoolean;

import com.tbohne.llkpattern.Ll1Pattern;

/**
 * On-device counterpart of {@code llkpattern/src/jmh/java/.../corpus/CorpusBenchmark.java},
 * comparing {@code java.util.regex} vs {@code Ll1Pattern} compile/match speed over the same
 * scraped-corpus golden files, but run through {@code androidx.test} instrumentation on a real
 * phone rather than JMH on desktop -- see documents/notes.md's on-device-benchmark entry for why
 * a separate harness is needed (JMH itself doesn't run on Android; the two share golden data and
 * benchmark structure, but not test infrastructure).
 *
 * <p>Differences worth knowing about versus the desktop {@code CorpusBenchmark}:
 *
 * <ul>
 *   <li>No JMH: iteration/warmup/timing is done by hand with {@link System#nanoTime}. This is
 *       cruder than JMH (no fork isolation, no statistical rigor), but is enough to compare
 *       device-to-device and against the desktop numbers at a coarse grain.
 *   <li>{@link #FRACTION_OF_TEST_ROWS} subsamples the corpus, since phones are slower and have
 *       much smaller heaps than the desktop JMH run -- see its own javadoc.
 *   <li>Results are written as JSON to this app's external files dir, named after the actual
 *       device ({@link Build#MODEL}/{@link Build#DEVICE}), since the point of running this on
 *       several phones is comparing wildly different hardware -- see {@link #resultsFile}.
 *   <li>GC counts (not GC time or allocation bytes -- {@link Debug} doesn't expose those cheaply)
 *       are recorded per benchmark via {@link Debug#getGlobalGcInvocationCount()}.
 *   <li>The checked-in code only runs the four timing benchmarks below -- CPU sampling profilers
 *       for both {@code llkMatch} and {@code llkCompile} (an 8-frame-deep hand-rolled stack
 *       sampler, not {@code Debug.startMethodTracingSampling}, which can't limit depth) are kept
 *       as commented-out {@code testZZSamplingProfileMatch}/{@code testZZSamplingProfileCompile}
 *       blocks near the bottom of this class, ready to uncomment (along with their imports,
 *       marked the same way at the top of the file) when profiling is actually needed again --
 *       see documents/benchmarks/ for the last captured samples and remaining_work.md for how
 *       they were run. Left commented rather than gated some other way so neither shows up as a
 *       normal runnable {@code @Test} at all in the common case, which is just the four
 *       benchmarks.
 * </ul>
 *
 * <p>Run via {@code ./gradlew :app:connectedAndroidTest} (all connected devices) or Android
 * Studio's test runner for one device. Pull results with:
 * {@code adb pull /sdcard/Android/data/com.tbohne.llkpattern/files/<device>_corpus_benchmark_results.json}
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AndroidCorpusBenchmark {
  /**
   * Fraction (0, 1] of each golden file's usable ({@code AGREES}, both-compiled) rows to actually
   * benchmark, selected by deterministic stride so the sample stays spread across the whole
   * corpus rather than just its first rows. Phones are slower and have much smaller Java heaps
   * than the desktop this corpus was sized for; lower this if a run times out or a device's
   * `-Xmx` can't hold every precompiled {@link Pattern}/{@link Ll1Pattern} at once. 1.0f runs the
   * full corpus, matching the desktop {@code CorpusBenchmark}.
   */
  private static final float FRACTION_OF_TEST_ROWS = 1.0f;

  // Sized (2026-09-08) from a real first run at FRACTION_OF_TEST_ROWS = 0.25f / warmup=3,
  // measured=5 on a Pixel 3a: the whole run (all 4 benchmarks, both loops) took well under a
  // second of actual corpus-pass time -- see documents/notes.md's on-device-benchmark entry --
  // leaving a phone's compute budget wildly underused at those defaults. At the full corpus
  // (1.0f above), one pass of all four benchmarks combined runs roughly 250ms on that device;
  // these counts aim to spend close to (but comfortably under) 5 minutes total on a device of
  // similar speed for better-averaged numbers, without risking an instrumentation timeout. A
  // much slower or faster device will land elsewhere -- lower both if a run is inconveniently
  // slow, since nothing else here depends on hitting any particular wall-clock target.
  private static final int WARMUP_ITERATIONS = 50;
  private static final int MEASURED_ITERATIONS = 1000;

  // Iterations for the commented-out testZZSamplingProfile's capture, below -- separate from
  // MEASURED_ITERATIONS since a profile needs enough wall-clock time to collect a useful number
  // of samples, not a small number of precisely-timed iterations. Uncomment alongside that block.
  // private static final int PROFILE_ITERATIONS = 200;

  private static List<AndroidGoldenRow> agreesRows;
  private static List<Pattern> regexPatterns;
  private static List<Ll1Pattern> llkPatterns;

  /** Accumulates one entry per {@code @Test} method; dumped to {@link #resultsFile} in {@link
   *  #writeResults}. JUnit doesn't guarantee test method order across JVMs/runners in general,
   *  but {@link FixMethodOrder} pins it here purely for readability of the resulting file --
   *  nothing depends on the order. */
  private static final Map<String, Object> results = new LinkedHashMap<>();

  @BeforeClass
  public static void setUpCorpus() throws IOException {
    Context context = InstrumentationRegistry.getInstrumentation().getContext();
    agreesRows = new ArrayList<>();
    for (String asset : new String[] {"golden/openjdk_bmp.tsv", "golden/openjdk_supplementary.tsv"}) {
      List<AndroidGoldenRow> allRows;
      try (InputStream in = context.getAssets().open(asset)) {
        allRows = AndroidGoldenTsv.read(in, asset);
      }
      List<AndroidGoldenRow> usable = new ArrayList<>();
      for (AndroidGoldenRow row : allRows) {
        // See CorpusBenchmark.setUp(): only rows where both engines actually compiled
        // successfully are usable for a *speed* comparison.
        if (row.status.equals("AGREES")
            && row.regexCompileException.isEmpty()
            && row.llkCompileException.isEmpty()) {
          usable.add(row);
        }
      }
      agreesRows.addAll(subsample(usable, FRACTION_OF_TEST_ROWS));
    }
    if (agreesRows.isEmpty()) {
      throw new IllegalStateException(
          "No usable AGREES rows found in the golden assets after subsampling at "
              + FRACTION_OF_TEST_ROWS + " -- did the golden files fail to copy into "
              + "androidTest assets (see app/build.gradle's copyGoldenAssetsForAndroidTest), or "
              + "is FRACTION_OF_TEST_ROWS too small for this corpus size?");
    }

    regexPatterns = new ArrayList<>(agreesRows.size());
    llkPatterns = new ArrayList<>(agreesRows.size());
    for (AndroidGoldenRow row : agreesRows) {
      regexPatterns.add(Pattern.compile(row.pattern, row.flagBits()));
      llkPatterns.add(Ll1Pattern.compile(row.pattern, row.flagBits()));
    }

    results.put("device", deviceName());
    results.put("androidRelease", Build.VERSION.RELEASE);
    results.put("androidSdkInt", Build.VERSION.SDK_INT);
    results.put("fractionOfTestRows", FRACTION_OF_TEST_ROWS);
    results.put("rowCount", agreesRows.size());
  }

  /** Deterministic evenly-spread subsample: every {@code stride}-th row, rather than a random or
   *  prefix subset, so lowering {@link #FRACTION_OF_TEST_ROWS} still exercises pattern variety
   *  from across the whole file instead of just whatever sorts first. */
  private static List<AndroidGoldenRow> subsample(List<AndroidGoldenRow> rows, float fraction) {
    if (fraction >= 1.0f || rows.isEmpty()) {
      return rows;
    }
    if (fraction <= 0.0f) {
      throw new IllegalArgumentException("FRACTION_OF_TEST_ROWS must be > 0, was " + fraction);
    }
    int stride = Math.max(1, Math.round(1.0f / fraction));
    List<AndroidGoldenRow> sampled = new ArrayList<>();
    for (int i = 0; i < rows.size(); i += stride) {
      sampled.add(rows.get(i));
    }
    return sampled;
  }

  @Test
  public void test1RegexCompile() {
    runBenchmark("regexCompile", () -> {
      for (AndroidGoldenRow row : agreesRows) {
        Pattern.compile(row.pattern, row.flagBits());
      }
    });
  }

  @Test
  public void test2LlkCompile() {
    runBenchmark("llkCompile", () -> {
      for (AndroidGoldenRow row : agreesRows) {
        Ll1Pattern.compile(row.pattern, row.flagBits());
      }
    });
  }

  @Test
  public void test3RegexMatch() {
    runBenchmark("regexMatch", () -> {
      for (int i = 0; i < agreesRows.size(); i++) {
        runRegexMatch(regexPatterns.get(i), agreesRows.get(i));
      }
    });
  }

  @Test
  public void test4LlkMatch() {
    runBenchmark("llkMatch", () -> {
      for (int i = 0; i < agreesRows.size(); i++) {
        runLlkMatch(llkPatterns.get(i), agreesRows.get(i));
      }
    });
  }

  // CPU sampling profiles of llkMatch/llkCompile, commented out -- see documents/notes.md's
  // on-device-benchmark entry for how they were captured and documents/benchmarks/
  // Google_Pixel_3a_sargo_llk{Match,Compile}_sampling.txt for the last real results. To
  // re-enable: uncomment this whole block plus the sampling-only imports marked at the top of the
  // file, then run just the one test needed with:
  //   adb shell am instrument -w -e profile true \
  //       -e class com.tbohne.llkpattern.corpus.AndroidCorpusBenchmark#testZZSamplingProfileMatch \
  //       com.tbohne.llkpattern.test/androidx.test.runner.AndroidJUnitRunner
  // (swap in #testZZSamplingProfileCompile for the compile-side profile) and pull the result with:
  //   adb pull /sdcard/Android/data/com.tbohne.llkpattern/files/<device>_llk{Match,Compile}_sampling.txt
  //
  // /** Frames kept per stack sample in {@link #testZZSamplingProfileMatch}/{@link
  //  *  #testZZSamplingProfileCompile} -- deep enough to see past {@code Matcher.match}/{@code
  //  *  MatcherConstruct} dispatch (or, for compile, {@code PatternParser}/{@code PatternConstruct})
  //  *  into whichever concrete construct is hot, shallow enough to keep the aggregated-chain table
  //  *  small and readable. {@link Debug#startMethodTracingSampling} (the built-in Android sampling
  //  *  tracer, tried first) has no way to cap this -- see documents/notes.md's on-device-benchmark
  //  *  entry for why this hand-rolled sampler replaced it. */
  // private static final int STACK_SAMPLE_DEPTH = 8;
  //
  // private static final long SAMPLE_INTERVAL_MILLIS = 2;
  //
  // /**
  //  * Captures a <b>sampling</b> profile (periodic stack snapshots, not per-call tracing -- this
  //  * runs the benchmarked work on the test thread while a separate sampler thread periodically
  //  * snapshots it via {@link Thread#getAllStackTraces()}, rather than instrumenting every call
  //  * the way {@link Debug#startMethodTracing} does, which would badly distort timing) of a
  //  * repeated {@code llkMatch} pass, aggregated into a plain-text table of the hottest top-
  //  * {@link #STACK_SAMPLE_DEPTH}-frame call chains.
  //  *
  //  * <p>Opt-in: skipped unless run with {@code -e profile true} -- it's deliberately excluded
  //  * from the default run since it doesn't produce a timing/GC result, just a profile, and
  //  * running it every time would slow down routine benchmark runs.
  //  */
  // @Test
  // public void testZZSamplingProfileMatch() throws InterruptedException, IOException {
  //   captureSamplingProfile("llkMatch", () -> {
  //     for (int i = 0; i < agreesRows.size(); i++) {
  //       runLlkMatch(llkPatterns.get(i), agreesRows.get(i));
  //     }
  //   });
  // }
  //
  // /** Same as {@link #testZZSamplingProfileMatch}, but of {@code llkCompile} instead -- see
  //  *  {@link #test2LlkCompile} for the equivalent timed (non-profiled) benchmark. */
  // @Test
  // public void testZZSamplingProfileCompile() throws InterruptedException, IOException {
  //   captureSamplingProfile("llkCompile", () -> {
  //     for (AndroidGoldenRow row : agreesRows) {
  //       Ll1Pattern.compile(row.pattern, row.flagBits());
  //     }
  //   });
  // }
  //
  // private interface ProfiledWork {
  //   void runOnePass();
  // }
  //
  // private void captureSamplingProfile(String name, ProfiledWork work)
  //     throws InterruptedException, IOException {
  //   Bundle args = InstrumentationRegistry.getArguments();
  //   assumeTrue(
  //       "Skipped by default -- pass -e profile true to capture a sampling profile.",
  //       args != null && Boolean.parseBoolean(args.getString("profile", "false")));
  //
  //   Thread targetThread = Thread.currentThread();
  //   Map<String, Integer> chainCounts = new HashMap<>();
  //   AtomicBoolean sampling = new AtomicBoolean(true);
  //   Thread sampler = new Thread(() -> {
  //     while (sampling.get()) {
  //       StackTraceElement[] frames = Thread.getAllStackTraces().get(targetThread);
  //       if (frames != null && frames.length > 0) {
  //         synchronized (chainCounts) {
  //           chainCounts.merge(formatChain(frames), 1, Integer::sum);
  //         }
  //       }
  //       try {
  //         Thread.sleep(SAMPLE_INTERVAL_MILLIS);
  //       } catch (InterruptedException e) {
  //         break;
  //       }
  //     }
  //   }, name + "-sampler");
  //   sampler.setDaemon(true);
  //   sampler.start();
  //   try {
  //     for (int iter = 0; iter < PROFILE_ITERATIONS; iter++) {
  //       work.runOnePass();
  //     }
  //   } finally {
  //     sampling.set(false);
  //     sampler.interrupt();
  //     sampler.join(1000);
  //   }
  //
  //   assertFalse("Sampler collected zero stack samples -- SAMPLE_INTERVAL_MILLIS too coarse for "
  //       + "how fast this pass ran, or Thread.getAllStackTraces() couldn't see the target "
  //       + "thread?", chainCounts.isEmpty());
  //   writeSamplingProfile(name, chainCounts);
  // }
  //
  // /** The top {@link #STACK_SAMPLE_DEPTH} frames of one stack sample, most-recent-call-first (as
  //  *  {@link StackTraceElement}s already are), joined into one aggregation key. */
  // private static String formatChain(StackTraceElement[] frames) {
  //   int depth = Math.min(STACK_SAMPLE_DEPTH, frames.length);
  //   StringBuilder sb = new StringBuilder();
  //   for (int i = 0; i < depth; i++) {
  //     if (i > 0) {
  //       sb.append(" <- ");
  //     }
  //     StackTraceElement f = frames[i];
  //     sb.append(f.getClassName()).append('.').append(f.getMethodName())
  //         .append(':').append(f.getLineNumber());
  //   }
  //   return sb.toString();
  // }
  //
  // private static void writeSamplingProfile(String name, Map<String, Integer> chainCounts)
  //     throws IOException {
  //   List<Map.Entry<String, Integer>> sorted = new ArrayList<>(chainCounts.entrySet());
  //   sorted.sort((a, b) -> b.getValue() - a.getValue());
  //   int totalSamples = 0;
  //   for (Map.Entry<String, Integer> e : sorted) {
  //     totalSamples += e.getValue();
  //   }
  //
  //   File file = new File(externalFilesDir(), deviceName() + "_" + name + "_sampling.txt");
  //   try (Writer w = new FileWriter(file)) {
  //     w.write(String.format(Locale.ROOT,
  //         "Sampling profile of %s on %s%n"
  //             + "stack depth: %d, sample interval: %dms, total samples: %d, distinct chains: %d%n"
  //             + "count (%% of samples)  top-%d-frame call chain (most-recent-call-first)%n%n",
  //         name, deviceName(), STACK_SAMPLE_DEPTH, SAMPLE_INTERVAL_MILLIS, totalSamples,
  //         sorted.size(), STACK_SAMPLE_DEPTH));
  //     for (Map.Entry<String, Integer> e : sorted) {
  //       double pct = 100.0 * e.getValue() / totalSamples;
  //       w.write(String.format(Locale.ROOT, "%6d (%5.1f%%)  %s%n", e.getValue(), pct, e.getKey()));
  //     }
  //   }
  //   System.out.println("AndroidCorpusBenchmark sampling profile written to " + file.getAbsolutePath());
  // }

  private interface CorpusPass {
    void run();
  }

  /** Runs {@link #WARMUP_ITERATIONS} untimed passes, then {@link #MEASURED_ITERATIONS} timed
   *  passes over the whole subsampled corpus, and records the average per-pass time plus GCs
   *  triggered during the measured passes under {@code name} in {@link #results}. This is a much
   *  cruder methodology than JMH's (no forked JVM per benchmark, no statistical error bars) --
   *  good enough for coarse device comparisons, not for chasing single-digit-percent regressions
   *  the way the desktop {@code CorpusBenchmark} run is used for. */
  private static void runBenchmark(String name, CorpusPass pass) {
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      pass.run();
    }

    long gcCountBefore = Debug.getGlobalGcInvocationCount();
    long startNanos = System.nanoTime();
    for (int i = 0; i < MEASURED_ITERATIONS; i++) {
      pass.run();
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    long gcCount = Debug.getGlobalGcInvocationCount() - gcCountBefore;

    double avgMillisPerPass = (elapsedNanos / 1_000_000.0) / MEASURED_ITERATIONS;
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("avgMillisPerCorpusPass", avgMillisPerPass);
    entry.put("measuredIterations", MEASURED_ITERATIONS);
    entry.put("gcCountDuringMeasuredIterations", gcCount);
    results.put(name, entry);
  }

  private static boolean runRegexMatch(Pattern pattern, AndroidGoldenRow row) {
    java.util.regex.Matcher m = pattern.matcher(row.input);
    switch (row.mode) {
      case MATCHES:
        return m.matches();
      case LOOKING_AT:
        return m.lookingAt();
      case FIND:
        return m.find();
      default:
        throw new IllegalArgumentException("Unhandled AndroidGoldenRow.Mode " + row.mode);
    }
  }

  private static boolean runLlkMatch(Ll1Pattern pattern, AndroidGoldenRow row) {
    com.tbohne.llkpattern.Matcher m = pattern.matcher(row.input);
    switch (row.mode) {
      case MATCHES:
        return m.matches();
      case LOOKING_AT:
        return m.lookingAt();
      case FIND:
        return m.find();
      default:
        throw new IllegalArgumentException("Unhandled AndroidGoldenRow.Mode " + row.mode);
    }
  }

  @AfterClass
  public static void writeResults() throws IOException {
    File file = resultsFile();
    try (Writer w = new FileWriter(file)) {
      w.write(toJson(results));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing benchmark results to " + file, e);
    }
    // No JSON assertion library pulled in for this (androidTest keeps a small dependency set);
    // this is a plain System.out so `adb logcat` / the test runner's own output shows the path
    // even if the test host doesn't offer file access.
    System.out.println("AndroidCorpusBenchmark results written to " + file.getAbsolutePath());
  }

  private static File resultsFile() {
    return new File(externalFilesDir(), deviceName() + "_corpus_benchmark_results.json");
  }

  private static File externalFilesDir() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File dir = context.getExternalFilesDir(null);
    if (dir == null) {
      // Falls back to the legacy shared external storage path if per-app external storage isn't
      // available (e.g. no SD card mounted) -- getExternalFilesDir can return null in that case.
      dir = new File(Environment.getExternalStorageDirectory(), "Android/data/"
          + context.getPackageName() + "/files");
      dir.mkdirs();
    }
    return dir;
  }

  /** Sanitized to a safe filename component: real device model/product strings can contain
   *  spaces or other characters that are awkward in a filename. */
  private static String deviceName() {
    String raw = Build.MANUFACTURER + "_" + Build.MODEL + "_" + Build.DEVICE;
    return raw.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static String toJson(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    int i = 0;
    for (Map.Entry<String, Object> e : map.entrySet()) {
      sb.append("  ").append(jsonString(e.getKey())).append(": ");
      appendJsonValue(sb, e.getValue());
      if (++i < map.size()) {
        sb.append(",");
      }
      sb.append("\n");
    }
    sb.append("}\n");
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static void appendJsonValue(StringBuilder sb, Object value) {
    if (value instanceof Map) {
      Map<String, Object> nested = (Map<String, Object>) value;
      sb.append("{");
      int i = 0;
      for (Map.Entry<String, Object> e : nested.entrySet()) {
        sb.append(jsonString(e.getKey())).append(": ");
        appendJsonValue(sb, e.getValue());
        if (++i < nested.size()) {
          sb.append(", ");
        }
      }
      sb.append("}");
    } else if (value instanceof String) {
      sb.append(jsonString((String) value));
    } else if (value instanceof Double || value instanceof Float) {
      sb.append(String.format(Locale.ROOT, "%.4f", ((Number) value).doubleValue()));
    } else {
      sb.append(value); // numbers/booleans print fine via their own toString
    }
  }

  private static String jsonString(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}

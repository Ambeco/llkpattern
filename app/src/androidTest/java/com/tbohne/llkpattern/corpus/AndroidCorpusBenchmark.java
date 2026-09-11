package com.tbohne.llkpattern.corpus;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.platform.io.PlatformTestStorageRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.tbohne.llkpattern.BuildConfig;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
 import static org.junit.Assert.assertFalse;
 import android.os.Bundle;
 import java.util.concurrent.atomic.AtomicBoolean;

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
 *       several phones is comparing wildly different hardware.
 *   <li>GC counts (not GC time or allocation bytes -- {@link Debug} doesn't expose those cheaply)
 *       are recorded per benchmark via {@link Debug#getGlobalGcInvocationCount()}.
 *   <li>The checked-in code only runs the four timing benchmarks below -- CPU sampling profilers
 *       for both {@code MatchLlk} and {@code CompileLlk} (an 8-frame-deep hand-rolled stack
 *       sampler, not {@code Debug.startMethodTracingSampling}, which can't limit depth) are kept
 *       as commented-out {@code testZZSamplingProfileMatch}/{@code testZZSamplingProfileCompile}
 *       blocks near the bottom of this class, ready to uncomment (along with their imports,
 *       marked the same way at the top of the file) when profiling is actually needed again --
 *       see benchmarks/ for the last captured samples and remaining_work.md for how
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
  private static final int WARMUP_ITERATIONS = 100;
  private static final int COMPILE_PERFORMANCE_ITERATIONS = 1000;
  private static final int MATCH_PERFORMANCE_ITERATIONS = 3500;

  // Iterations for sampling's capture, below -- separate from PERFORMANCE_ITERATIONS since a 
  // profile needs enough wall-clock time to collect a useful number of samples, not a small
  // number of precisely-timed iterations. Uncomment alongside that block.
  private static final int COMPILE_PROFILE_ITERATIONS = 600;
  private static final int MATCH_PROFILE_ITERATIONS = 10000;

  private static List<AndroidGoldenRow> agreesRows;
  private static List<Pattern> regexPatterns;
  private static List<Ll1Pattern> llkPatterns;

  /** Frames kept per stack sample in {@link #sampleMatchLlk}/{@link
   *  #sampleCompileLlk} -- deep enough to see past {@code Matcher.match}/{@code
   *  MatcherConstruct} dispatch (or, for compile, {@code PatternParser}/{@code PatternConstruct})
   *  into whichever concrete construct is hot, shallow enough to keep the aggregated-chain table
   *  small and readable. {@link Debug#startMethodTracingSampling} (the built-in Android sampling
   *  tracer, tried first) has no way to cap this -- see documents/notes.md's on-device-benchmark
   *  entry for why this hand-rolled sampler replaced it. */
  private static final int STACK_SAMPLE_DEPTH = 4;
  private static final long SAMPLE_INTERVAL_MILLIS = 1;
  /**
   * Accumulates one entry per {@code @Test} method; dumped to _corpus_benchmark_results in {@link
   * #writeResults}. JUnit doesn't guarantee test method order across JVMs/runners in general, but
   * {@link FixMethodOrder} pins it here purely for readability of the resulting file -- nothing
   * depends on the order.
   */
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
    results.put("buildTime", Instant.ofEpochMilli(BuildConfig.BUILD_TIME).toString());
    results.put("testTime", Instant.now().toString());
    results.put("androidRelease", Build.VERSION.RELEASE);
    results.put("androidSdkInt", Build.VERSION.SDK_INT);
    results.put("fractionOfTestRows", FRACTION_OF_TEST_ROWS);
    results.put("compileIterations", COMPILE_PERFORMANCE_ITERATIONS);
    results.put("matchIterations", MATCH_PERFORMANCE_ITERATIONS);
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
  public void testCompileRegex() {
    runBenchmark("compileRegex", COMPILE_PERFORMANCE_ITERATIONS, () -> {
      for (AndroidGoldenRow row : agreesRows) {
        Pattern.compile(row.pattern, row.flagBits());
      }
    });
  }

  @Test
  public void testCompileLlk() {
    runBenchmark("compileLlk", COMPILE_PERFORMANCE_ITERATIONS, () -> {
      for (AndroidGoldenRow row : agreesRows) {
        Ll1Pattern.compile(row.pattern, row.flagBits());
      }
    });
  }

  @Test
  public void testMatchRegex() {
    runBenchmark("matchRegex", MATCH_PERFORMANCE_ITERATIONS, () -> {
      for (int i = 0; i < agreesRows.size(); i++) {
        runMatchRegex(regexPatterns.get(i), agreesRows.get(i));
      }
    });
  }

  @Test
  public void testMatchLlk() {
    runBenchmark("matchLlk", MATCH_PERFORMANCE_ITERATIONS, ()-> {
      for (int i = 0; i < agreesRows.size(); i++) {
        runMatchLlk(llkPatterns.get(i), agreesRows.get(i));
      }
    });
  }

   /**
    * Captures a <b>sampling</b> profile (periodic stack snapshots, not per-call tracing -- this
    * runs the benchmarked work on the test thread while a separate sampler thread periodically
    * snapshots it via {@link Thread#getAllStackTraces()}, rather than instrumenting every call
    * the way {@link Debug#startMethodTracing} does, which would badly distort timing) of a
    * repeated {@code MatchLlk} pass, aggregated into a plain-text table of the hottest top-
    * {@link #STACK_SAMPLE_DEPTH}-frame call chains.
    *
    * <p>Opt-in: skipped unless run with {@code -e profile true} -- it's deliberately excluded
    * from the default run since it doesn't produce a timing/GC result, just a profile, and
    * running it every time would slow down routine benchmark runs.
    */
   @Test
   public void sampleMatchLlk() throws InterruptedException, IOException {
     captureSamplingProfile("MatchLlk", MATCH_PROFILE_ITERATIONS, () -> {
       for (int i = 0; i < agreesRows.size(); i++) {
         runMatchLlk(llkPatterns.get(i), agreesRows.get(i));
       }
     });
   }

   /** Same as {@link #sampleMatchLlk}, but of {@code CompileLlk} instead -- see
    *  {@link #testCompileLlk} for the equivalent timed (non-profiled) benchmark. */
   @Test
   public void sampleCompileLlk() throws InterruptedException, IOException {
     captureSamplingProfile("CompileLlk", COMPILE_PROFILE_ITERATIONS, () -> {
       for (AndroidGoldenRow row : agreesRows) {
         Ll1Pattern.compile(row.pattern, row.flagBits());
       }
     });
   }

   private interface ProfiledWork {
     void runOnePass();
   }

   private void captureSamplingProfile(String name, int profileIterations, ProfiledWork work)
       throws InterruptedException, IOException {
     Bundle args = InstrumentationRegistry.getArguments();
     Thread targetThread = Thread.currentThread();
     Map<String, Integer> chainCounts = new TreeMap<>();
     AtomicBoolean sampling = new AtomicBoolean(true);
     Thread sampler = new Thread(() -> {
       while (sampling.get()) {
         StackTraceElement[] frames = targetThread.getStackTrace();
         if (frames != null && frames.length > 0) {
           synchronized (chainCounts) {
             chainCounts.merge(formatChain(frames), 1, Integer::sum);
           }
         }
         try {
           Thread.sleep(SAMPLE_INTERVAL_MILLIS);
         } catch (InterruptedException e) {
           break;
         }
       }
     }, name + "-sampler");
     sampler.setDaemon(true);
     sampler.start();
     try {
       for (int iter = 0; iter < profileIterations; iter++) {
         work.runOnePass();
       }
     } finally {
       sampling.set(false);
       sampler.interrupt();
       sampler.join(1000);
     }

     assertFalse("Sampler collected zero stack samples -- SAMPLE_INTERVAL_MILLIS too coarse for "
         + "how fast this pass ran, or Thread.getAllStackTraces() couldn't see the target "
         + "thread?", chainCounts.isEmpty());
     writeSamplingProfile(name, chainCounts, profileIterations);
   }

   /** The top {@link #STACK_SAMPLE_DEPTH} frames of one stack sample, most-recent-call-first (as
    *  {@link StackTraceElement}s already are), joined into one aggregation key. */
   private static String formatChain(StackTraceElement[] frames) {
     int depth = Math.min(STACK_SAMPLE_DEPTH, frames.length);
     StringBuilder sb = new StringBuilder();
     for (int i = 0; i < depth; i++) {
       StackTraceElement f = frames[i];
       sb.append(f.getClassName()).append('.').append(f.getMethodName())
           .append(':').append(f.getLineNumber()).append("\n\t\t\t");
     }
     return sb.toString();
   }

   private static void writeSamplingProfile(String name, Map<String, Integer> chainCounts, int profileIterations)
       throws IOException {
     List<Map.Entry<String, Integer>> sorted = new ArrayList<>(chainCounts.entrySet());
     sorted.sort((a, b) -> b.getValue() - a.getValue());
     int totalSamples = 0;
     for (Map.Entry<String, Integer> e : sorted) {
       totalSamples += e.getValue();
     }

     String fileName = deviceName() + "_" + name + "_sampling.txt";
     try (OutputStream w = PlatformTestStorageRegistry.getInstance().openOutputFile(fileName)) {
       w.write(String.format(Locale.ROOT,
           "Sampling profile of %s on %s%n"
               + "stack depth: %d, sample interval: %dms, profile iterations: %d, total samples: %d, distinct chains: %d%n"
               + "count (%% of samples)  top-%d-frame call chain (most-recent-call-first)%n%n",
           name, deviceName(), STACK_SAMPLE_DEPTH, SAMPLE_INTERVAL_MILLIS, profileIterations, totalSamples,
           sorted.size(), STACK_SAMPLE_DEPTH).getBytes(StandardCharsets.UTF_8));
       for (int i=0; i<20 && i<sorted.size(); i++) {
         Map.Entry<String, Integer> e = sorted.get(i);
         double pct = 100.0 * e.getValue() / totalSamples;
         w.write(String.format(Locale.ROOT, "%d\t%2.1f%%\t%s%n", e.getValue(), pct, e.getKey()).getBytes(StandardCharsets.UTF_8));
       }
     }
     System.out.println("AndroidCorpusBenchmark sampling profile written to " + fileName);
   }

  private interface CorpusPass {
    void run();
  }

  /** Runs {@link #WARMUP_ITERATIONS} untimed passes, then measuredIterations timed
   *  passes over the whole subsampled corpus, and records the average per-pass time plus GCs
   *  triggered during the measured passes under {@code name} in {@link #results}. This is a much
   *  cruder methodology than JMH's (no forked JVM per benchmark, no statistical error bars) --
   *  good enough for coarse device comparisons, not for chasing single-digit-percent regressions
   *  the way the desktop {@code CorpusBenchmark} run is used for. */
  private static void runBenchmark(String name, int measuredIterations, CorpusPass pass) {
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      pass.run();
    }

    long gcCountBefore = Debug.getGlobalGcInvocationCount();
    long startNanos = System.nanoTime();
    for (int i = 0; i < measuredIterations; i++) {
      pass.run();
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    long gcCount = Debug.getGlobalGcInvocationCount() - gcCountBefore;

    double avgMillisPerPass = (elapsedNanos / 1_000_000.0) / measuredIterations;
    Map<String, Object> entry = new TreeMap<>();
    entry.put("avgMillisPerCorpusPass", avgMillisPerPass);
    entry.put("gcCountDuringMeasuredIterations", gcCount);
    results.put(name, entry);
  }

  private static boolean runMatchRegex(Pattern pattern, AndroidGoldenRow row) {
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

  private static boolean runMatchLlk(Ll1Pattern pattern, AndroidGoldenRow row) {
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
    String fileName = deviceName() + "_corpus_benchmark_results.json";
    try (OutputStream file = PlatformTestStorageRegistry.getInstance().openOutputFile(fileName)) {
      file.write(toJson(results).getBytes(StandardCharsets.UTF_8));
      // No JSON assertion library pulled in for this (androidTest keeps a small dependency set);
      // this is a plain System.out so `adb logcat` / the test runner's own output shows the path
      // even if the test host doesn't offer file access.
      System.out.println("AndroidCorpusBenchmark results written to " + fileName);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing benchmark results to " + fileName, e);
    }
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

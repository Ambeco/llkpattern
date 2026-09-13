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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import static org.junit.Assert.assertFalse;

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
 *   <li>{@link #sampleMatchLlk}/{@link #sampleCompileLlk} run unconditionally alongside the four
 *       timing benchmarks (not gated behind an instrumentation arg) -- a hand-rolled stack sampler,
 *       not {@code Debug.startMethodTracingSampling}, which can't limit depth or format its own
 *       output as the reversed call-tree {@link #captureSamplingProfile} produces. See
 *       benchmarks/ for the last captured samples.
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

  /** Frames captured per stack sample in {@link #sampleMatchLlk}/{@link #sampleCompileLlk} --
   *  deep enough to reach past {@code Matcher.match}/{@code MatcherConstruct} dispatch (or, for
   *  compile, {@code PatternParser}/{@code PatternConstruct}) into whichever concrete construct is
   *  hot. Unlike the old flat-chain format (see documents/notes.md's 2026-09-08/09 entries for why
   *  that one was capped at 4 frames -- deeper made it too flat/diffuse to read), the reversed
   *  call-tree format in {@link #writeSamplingProfile} collapses shared prefixes across samples, so
   *  capturing deeper doesn't cost readability -- printed depth is governed separately by {@link
   *  #printCallers}'s cutoff threshold. {@link Debug#startMethodTracingSampling} (the built-in
   *  Android sampling tracer, tried first) has no way to cap this at all -- see
   *  documents/notes.md's on-device-benchmark entry for why this hand-rolled sampler replaced it. */
  private static final int STACK_SAMPLE_DEPTH = 10;
  private static final long SAMPLE_INTERVAL_MILLIS = 1;
  /** Leaf rank (1-based, so 10 means "the 10th most common leaf") whose most-common caller's
   *  percentage becomes the depth cutoff in {@link #printCallers} -- see that method's javadoc. */
  private static final int CUTOFF_LEAF_RANK = 10;
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
    * <p>Runs unconditionally alongside the four timing benchmarks -- it doesn't produce a
    * timing/GC result, just a profile, but the extra wall-clock cost is small next to the timing
    * benchmarks' own iteration counts.
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

   /** One node of the reversed call tree built by {@link #captureSamplingProfile}: the root's
    *  children are leaf (innermost) frames, each of *their* children is a caller of that leaf, and
    *  so on outward -- i.e. a frame's depth in this tree is its distance from the leaf, not from
    *  the harness. {@code count} is the number of samples whose call chain passed through this
    *  exact node's path from the root, so a node's count is always &lt;= its parent's. */
   private static final class ChainNode {
     final String frame; // null only for the synthetic root.
     int count;
     final Map<String, ChainNode> children = new HashMap<>();

     ChainNode(String frame) {
       this.frame = frame;
     }

     ChainNode child(String frameKey) {
       return children.computeIfAbsent(frameKey, ChainNode::new);
     }

     /** Children ranked most-samples-first -- the order both leaf ranking and caller printing use. */
     List<ChainNode> rankedChildren() {
       List<ChainNode> ranked = new ArrayList<>(children.values());
       ranked.sort((a, b) -> b.count - a.count);
       return ranked;
     }
   }

   private void captureSamplingProfile(String name, int profileIterations, ProfiledWork work)
       throws InterruptedException, IOException {
     Thread targetThread = Thread.currentThread();
     ChainNode root = new ChainNode(null);
     AtomicBoolean sampling = new AtomicBoolean(true);
     Thread sampler = new Thread(() -> {
       while (sampling.get()) {
         StackTraceElement[] frames = targetThread.getStackTrace();
         if (frames != null && frames.length > 0) {
           List<String> leafToOuter = extractFrames(frames);
           if (!leafToOuter.isEmpty()) {
             synchronized (root) {
               root.count++;
               ChainNode node = root;
               for (String frameKey : leafToOuter) {
                 node = node.child(frameKey);
                 node.count++;
               }
             }
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
         + "how fast this pass ran, or Thread.getStackTrace() couldn't see the target thread?",
         root.children.isEmpty());
     writeSamplingProfile(name, root, profileIterations);
   }

   /** The innermost-to-outermost frames of one stack sample, as trie-insertion keys (leaf first,
    *  matching {@link StackTraceElement}s' own most-recent-call-first order), capped at {@link
    *  #STACK_SAMPLE_DEPTH} and trimmed at the {@link #captureSamplingProfile} boundary so trees
    *  bottom out in benchmarked code rather than continuing into the sampler-thread/harness/JUnit
    *  frames below it (which are identical across every sample and would just be dead weight at
    *  the bottom of every branch). */
   private static List<String> extractFrames(StackTraceElement[] frames) {
     String harnessClass = AndroidCorpusBenchmark.class.getName();
     List<String> keys = new ArrayList<>(STACK_SAMPLE_DEPTH);
     for (StackTraceElement f : frames) {
       if (keys.size() >= STACK_SAMPLE_DEPTH) {
         break;
       }
       if (f.getClassName().equals(harnessClass) && f.getMethodName().equals("captureSamplingProfile")) {
         break;
       }
       keys.add(f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber());
     }
     return keys;
   }

   /** Never prints a caller node below this percentage of total samples, however the rank-based
    *  cutoff in {@link #computeCallerCutoffPercent} computes out -- a floor against a corpus with
    *  very few distinct leaves or callers making that computation degenerate (e.g. resolving to
    *  ~0%, which would print every node down to single-sample noise). */
   private static final double MIN_CALLER_CUTOFF_PERCENT = 0.5;

   /**
    * Writes {@code root}'s reversed call tree as a plain-text report: leaves (root's children)
    * ranked most-common-first, each followed by its own callers recursively ranked the same way.
    * Each printed percentage is that exact node's sample count over the grand total -- i.e. "what
    * fraction of all samples took this leaf via this specific caller chain", not how often the
    * caller method appears anywhere else or how often it's a leaf in its own right.
    *
    * <p>Caller printing stops once a node's percentage drops below {@link
    * #computeCallerCutoffPercent}'s threshold -- see that method's own javadoc for how it's
    * derived from the leaf ranking, so this stays a data-driven cutoff rather than a fixed depth.
    */
   private static void writeSamplingProfile(String name, ChainNode root, int profileIterations)
       throws IOException {
     int totalSamples = root.count;
     List<ChainNode> leaves = root.rankedChildren();
     double cutoffPercent = computeCallerCutoffPercent(leaves, totalSamples);

     StringBuilder body = new StringBuilder();
     body.append(String.format(Locale.ROOT,
         "Sampling profile of %s on %s%n"
             + "capture depth: %d frames, sample interval: %dms, profile iterations: %d, "
             + "total samples: %d, distinct leaf methods: %d%n"
             + "caller cutoff: %.2f%% (the %d%s most common leaf's most common caller's own "
             + "%%-of-total-samples, floored at %.1f%%)%n"
             + "Reversed call tree: leaves ranked by frequency, then each leaf's callers ranked "
             + "the same way beneath it. A caller's %% is its own share of all samples, not the "
             + "leaf's -- see this file's generating code for the exact semantics.%n%n",
         name, deviceName(), STACK_SAMPLE_DEPTH, SAMPLE_INTERVAL_MILLIS, profileIterations,
         totalSamples, leaves.size(), cutoffPercent, CUTOFF_LEAF_RANK, ordinalSuffix(CUTOFF_LEAF_RANK),
         MIN_CALLER_CUTOFF_PERCENT));
     for (ChainNode leaf : leaves) {
       double pct = 100.0 * leaf.count / totalSamples;
       body.append(String.format(Locale.ROOT, "* %s (%.1f%%)%n", leaf.frame, pct));
       printCallers(body, leaf, totalSamples, cutoffPercent, 1);
     }

     String fileName = deviceName() + "_" + name + "_sampling.txt";
     try (OutputStream w = PlatformTestStorageRegistry.getInstance().openOutputFile(fileName)) {
       w.write(body.toString().getBytes(StandardCharsets.UTF_8));
     }
     System.out.println("AndroidCorpusBenchmark sampling profile written to " + fileName);
   }

   /**
    * The depth cutoff for {@link #printCallers}: the {@link #CUTOFF_LEAF_RANK}-th most common
    * leaf's most common caller's own percentage of total samples (or, if that leaf has no captured
    * callers at all, the leaf's own percentage) -- per the project owner's rule of thumb, this
    * tracks where the data itself stops distinguishing meaningfully hot chains from noise, rather
    * than a fixed depth that would be too shallow for some benchmarks and too deep for others.
    * Falls back to {@link #MIN_CALLER_CUTOFF_PERCENT} when fewer than {@link #CUTOFF_LEAF_RANK}
    * distinct leaves exist, or whenever the computed value is smaller than that floor.
    */
   private static double computeCallerCutoffPercent(List<ChainNode> leaves, int totalSamples) {
     int rankIndex = Math.min(CUTOFF_LEAF_RANK, leaves.size()) - 1;
     if (rankIndex < 0) {
       return MIN_CALLER_CUTOFF_PERCENT; // Only reachable if leaves is empty, which the caller
                                          // (via captureSamplingProfile's assertFalse) prevents.
     }
     ChainNode cutoffLeaf = leaves.get(rankIndex);
     List<ChainNode> callers = cutoffLeaf.rankedChildren();
     ChainNode reference = callers.isEmpty() ? cutoffLeaf : callers.get(0);
     double pct = 100.0 * reference.count / totalSamples;
     return Math.max(pct, MIN_CALLER_CUTOFF_PERCENT);
   }

   /** Recursively prints {@code node}'s callers (its children in the reversed tree), most common
    *  first, stopping -- for this node and, since siblings are sorted desc, every remaining sibling
    *  too -- as soon as a caller's percentage drops below {@code cutoffPercent}. */
   private static void printCallers(
       StringBuilder body, ChainNode node, int totalSamples, double cutoffPercent, int depth) {
     for (ChainNode caller : node.rankedChildren()) {
       double pct = 100.0 * caller.count / totalSamples;
       if (pct < cutoffPercent) {
         break;
       }
       for (int i = 0; i < depth; i++) {
         body.append("   ");
       }
       body.append(String.format(Locale.ROOT, "* %s (%.1f%%)%n", caller.frame, pct));
       printCallers(body, caller, totalSamples, cutoffPercent, depth + 1);
     }
   }

   private static String ordinalSuffix(int n) {
     if (n % 100 >= 11 && n % 100 <= 13) {
       return "th";
     }
     switch (n % 10) {
       case 1: return "st";
       case 2: return "nd";
       case 3: return "rd";
       default: return "th";
     }
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

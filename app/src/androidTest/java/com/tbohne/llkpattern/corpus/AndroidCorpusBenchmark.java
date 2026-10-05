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
   * Fraction (0, 1] of each golden file's usable rows to benchmark, selected by deterministic stride so the
   * sample spans the whole corpus. Phones are slower with smaller heaps: lower this if a run times out or a heap
   * can't hold every precompiled pattern. 1.0f is the full corpus, as on desktop.
   */
  private static final float FRACTION_OF_TEST_ROWS = 1.0f;

  // Sized from a first Pixel 3a run (2026-09-08): a full-corpus pass of all four benchmarks takes ~250 ms, so
  // these counts aim for just under 5 minutes total on a similar device. Lower both if a run is inconveniently
  // slow; nothing depends on a particular wall-clock target.
  // Paired (interleaved) run: 1 round = every bucket's compile and match chain once. Sized for ~2-3
  // minutes on a Pixel 3a (one round ~0.5 s with 4-pair chains).
  // Overridable per run with instrumentation arguments, e.g. -Pandroid.testInstrumentationRunnerArguments.pairedRounds=1200
  // (names: pairedWarmupRounds, pairedRounds, pairedChainPairs, pairedBlocks; rawSamples=true also dumps every chain).
  private static final int PAIRED_WARMUP_ROUNDS = intArg("pairedWarmupRounds", 100);
  private static final int PAIRED_ROUNDS = intArg("pairedRounds", 150);
  private static final int PAIRED_CHAIN_PAIRS = intArg("pairedChainPairs", 4);
  private static final int PAIRED_BLOCKS = intArg("pairedBlocks", 15);
  /** Re-runs this percent of each bucket's rows in the llk passes only: a known, source-free slowdown for
   *  validating that the method detects a small regression (pairedInjectPercent=2). */
  private static final int PAIRED_INJECT_PERCENT = intArg("pairedInjectPercent", 0);

  private static int intArg(String name, int defaultValue) {
    String v = InstrumentationRegistry.getArguments().getString(name);
    return v == null ? defaultValue : Integer.parseInt(v);
  }
  private static final int MIN_BUCKET_ROWS = 40;

  // Iterations for sampling's capture: a profile needs wall-clock time to collect samples, not a few
  // precisely-timed iterations.
  private static final int COMPILE_PROFILE_ITERATIONS = 600;
  private static final int MATCH_PROFILE_ITERATIONS = 10000;

  private static List<AndroidGoldenRow> agreesRows;
  private static List<Pattern> regexPatterns;
  private static List<Ll1Pattern> llkPatterns;

  /** Frames captured per stack sample: deep enough to reach past Matcher.match/MatcherConstruct dispatch (or,
   *  for compile, PatternParser/PatternConstruct) into the hot construct. The reversed call tree in
   *  {@link #writeSamplingProfile} collapses shared prefixes, so depth costs no readability (printed depth is set
   *  by {@link #printCallers}' cutoff); the old flat-chain format was capped at 4 for that reason (notes.md
   *  2026-09-08/09). {@link Debug#startMethodTracingSampling} can't cap depth at all, hence this hand-rolled
   *  sampler. */
  private static final int STACK_SAMPLE_DEPTH = 10;
  private static final long SAMPLE_INTERVAL_MILLIS = 1;
  /** Leaf rank (1-based, so 10 means "the 10th most common leaf") whose most-common caller's
   *  percentage becomes the depth cutoff in {@link #printCallers} -- see that method's javadoc. */
  private static final int CUTOFF_LEAF_RANK = 30;
  /** One entry per {@code @Test} method, dumped by {@link #writeResults}. {@link FixMethodOrder} pins the order
   *  only for readability of the file. */
  private static final Map<String, Object> results = new LinkedHashMap<>();

  /**
   * The golden files record desktop {@code java.util.regex} behavior, but Android's is ICU-based and
   * rejects some patterns the desktop JDK accepts (e.g. {@code \pL}); such rows can't be timed
   * against it.
   */
  private static boolean deviceRegexCompiles(AndroidGoldenRow row) {
    try {
      Pattern.compile(row.pattern, row.flagBits());
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  @BeforeClass
  public static void setUpCorpus() throws IOException {
    Context context = InstrumentationRegistry.getInstrumentation().getContext();
    agreesRows = new ArrayList<>();
    // Every golden file is included automatically, mirroring CorpusBenchmark.goldenFiles().
    String[] goldenFileNames = context.getAssets().list("golden");
    if (goldenFileNames == null || goldenFileNames.length == 0) {
      throw new IllegalStateException("No golden/*.tsv assets found -- did app/build.gradle's "
          + "copyGoldenAssets task not run, or the golden directory move?");
    }
    java.util.Arrays.sort(goldenFileNames);
    for (String fileName : goldenFileNames) {
      String asset = "golden/" + fileName;
      List<AndroidGoldenRow> allRows;
      try (InputStream in = context.getAssets().open(asset)) {
        allRows = AndroidGoldenTsv.read(in, asset);
      }
      List<AndroidGoldenRow> usable = new ArrayList<>();
      for (AndroidGoldenRow row : allRows) {
        // Only rows where both engines compiled are usable for a speed comparison (see CorpusBenchmark.setUp()).
        if (row.status.equals("AGREES")
            && row.regexCompileException.isEmpty()
            && row.llkCompileException.isEmpty()
            && deviceRegexCompiles(row)) {
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
    results.put("sourceHash", BuildConfig.SOURCE_HASH);
    results.put("buildTime", Instant.ofEpochMilli(BuildConfig.BUILD_TIME).toString());
    results.put("testTime", Instant.now().toString());
    results.put("androidRelease", Build.VERSION.RELEASE);
    results.put("androidSdkInt", Build.VERSION.SDK_INT);
    results.put("fractionOfTestRows", FRACTION_OF_TEST_ROWS);
    results.put("pairedRounds", PAIRED_ROUNDS);
    results.put("pairedChainPairs", PAIRED_CHAIN_PAIRS);
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

  /**
   * Interleaved llk-vs-regex timing split by regex feature (see {@link PairedBench}), replacing four separate
   * timed blocks whose ratio was dominated by device noise hitting one block but not another. Writes the legacy
   * {@code corpus_benchmark_results.json} keys (absolute ms per corpus pass, now means of the interleaved
   * passes) and {@code paired_ratio_results.json} (per-bucket ratios with confidence intervals; blocks are
   * contiguous slices of this run, standing in for the desktop runner's forks).
   */
  @Test
  public void testPaired() {
    List<PairedBench.Bucket> buckets = new ArrayList<>();
    List<String> names = new ArrayList<>();
    List<Integer> bucketRows = new ArrayList<>();
    List<String> patternTexts = new ArrayList<>();
    List<String> flagTexts = new ArrayList<>();
    for (AndroidGoldenRow row : agreesRows) {
      patternTexts.add(row.pattern);
      flagTexts.add(row.flags);
    }
    for (Map.Entry<String, List<Integer>> e :
        RowBuckets.group(patternTexts, flagTexts, MIN_BUCKET_ROWS).entrySet()) {
      final List<AndroidGoldenRow> rows = new ArrayList<>();
      final List<Pattern> regex = new ArrayList<>();
      final List<Ll1Pattern> llk = new ArrayList<>();
      for (int i : e.getValue()) {
        rows.add(agreesRows.get(i));
        regex.add(regexPatterns.get(i));
        llk.add(llkPatterns.get(i));
      }
      PairedBench.Pass regexCompile = () -> {
        for (AndroidGoldenRow row : rows) {
          sink ^= Pattern.compile(row.pattern, row.flagBits()).hashCode();
        }
      };
      final int extra = (int) Math.ceil(rows.size() * PAIRED_INJECT_PERCENT / 100.0);
      PairedBench.Pass llkCompile = () -> {
        for (AndroidGoldenRow row : rows) {
          sink ^= Ll1Pattern.compile(row.pattern, row.flagBits()).hashCode();
        }
        for (int k = 0; k < extra; k++) {
          AndroidGoldenRow row = rows.get(k);
          sink ^= Ll1Pattern.compile(row.pattern, row.flagBits()).hashCode();
        }
      };
      PairedBench.Pass regexMatch = () -> {
        for (int i = 0; i < rows.size(); i++) {
          sink ^= runMatchRegex(regex.get(i), rows.get(i)) ? 1 : 0;
        }
      };
      PairedBench.Pass llkMatch = () -> {
        for (int i = 0; i < rows.size(); i++) {
          sink ^= runMatchLlk(llk.get(i), rows.get(i)) ? 1 : 0;
        }
        for (int k = 0; k < extra; k++) {
          sink ^= runMatchLlk(llk.get(k), rows.get(k)) ? 1 : 0;
        }
      };
      buckets.add(new PairedBench.Bucket(e.getKey(), rows.size(),
          new PairedBench.Pass[] {regexCompile, regexMatch},
          new PairedBench.Pass[] {llkCompile, llkMatch}));
      names.add(e.getKey());
      bucketRows.add(rows.size());
    }

    final boolean raw = "true".equals(InstrumentationRegistry.getArguments().getString("rawSamples"));
    final boolean compareBlocked =
        !"false".equals(InstrumentationRegistry.getArguments().getString("compareBlocked"));
    final long startNanos = System.nanoTime();
    final StringBuilder roundMarks = new StringBuilder();
    final DeviceMonitor monitor = raw ? new DeviceMonitor(startNanos) : null;
    if (monitor != null) {
      monitor.start();
    }
    long gcBefore = artStat("art.gc.gc-count");
    List<PairedBench.Sample> samples;
    try {
      samples = PairedBench.run(buckets,
          new PairedBench.Env() {
            @Override
            public long gcCount() {
              return artStat("art.gc.gc-count");
            }

            @Override
            public long gcMillis() {
              return artStat("art.gc.gc-time");
            }

            @Override
            public long threadCpuNanos() {
              return Debug.threadCpuTimeNanos();
            }

            @Override
            public void onRound(int round) {
              roundMarks.append(round).append('\t').append((System.nanoTime() - startNanos) / 1_000_000)
                  .append('\n');
            }
          },
          PAIRED_WARMUP_ROUNDS, PAIRED_ROUNDS, PAIRED_CHAIN_PAIRS, PAIRED_BLOCKS, compareBlocked);
    } finally {
      if (monitor != null) {
        monitor.stop();
      }
    }
    long gcCount = artStat("art.gc.gc-count") - gcBefore;

    if (monitor != null) {
      writeFile(deviceName() + "_device_log.tsv", monitor.toTsv());
      writeFile(deviceName() + "_round_marks.tsv", roundMarks.toString());
    }
    if (raw) {
      StringBuilder rawTsv = new StringBuilder();
      for (PairedBench.Sample sample : samples) {
        rawTsv.append(sample.toTsv()).append("\n");
      }
      writeFile(deviceName() + "_paired_raw.tsv", rawTsv.toString());
    }
    List<PairedStats.Entry> entries = PairedStats.summarize(samples, names, bucketRows);
    for (PairedStats.Entry e : entries) {
      if (e.key.equals("compile/ALL") || e.key.equals("match/ALL")) {
        boolean compile = e.key.startsWith("compile");
        putLegacy(compile ? "compileLlk" : "matchLlk", e.llkMs, gcCount);
        putLegacy(compile ? "compileRegex" : "matchRegex", e.regexMs, gcCount);
      }
    }
    Map<String, String> meta = new LinkedHashMap<>();
    meta.put("machine", deviceName());
    meta.put("captured", Instant.now().toString());
    meta.put("sourceHash", BuildConfig.SOURCE_HASH);
    meta.put("blocks", Integer.toString(PAIRED_BLOCKS));
    meta.put("injectPercent", Integer.toString(PAIRED_INJECT_PERCENT));
    meta.put("roundsTotal", Integer.toString(PAIRED_ROUNDS));
    meta.put("chainPairs", Integer.toString(PAIRED_CHAIN_PAIRS));
    meta.put("batteryTempTenthsC", Integer.toString(batteryTemperatureTenthsC()));
    writeFile(deviceName() + "_paired_ratio_results.json", PairedStats.toJson(entries, meta));
    System.out.print(PairedStats.toTable(entries));
  }

  /** ART runtime statistic ("art.gc.gc-count", "art.gc.gc-time" in ms, ...); 0 if unavailable. */
  private static long artStat(String name) {
    try {
      String v = Debug.getRuntimeStat(name);
      return v == null ? 0 : Long.parseLong(v);
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /** Logs every core's current frequency and the battery temperature every ~100 ms on a background
   *  thread (sysfs thermal zones aren't readable by an app; battery temperature is the available proxy),
   *  so a slow phase in the timing data can be matched against throttling. Only run with rawSamples. */
  private static final class DeviceMonitor implements Runnable {
    private final long startNanos;
    private final StringBuilder log = new StringBuilder("ms\tbatteryTempTenthsC\tfreqKHz_cpu0..7\n");
    private volatile boolean running = true;
    private Thread thread;

    DeviceMonitor(long startNanos) {
      this.startNanos = startNanos;
    }

    void start() {
      thread = new Thread(this, "device-monitor");
      thread.setDaemon(true);
      thread.start();
    }

    void stop() {
      running = false;
      try {
        thread.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    synchronized String toTsv() {
      return log.toString();
    }

    @Override
    public void run() {
      while (running) {
        StringBuilder line = new StringBuilder();
        line.append((System.nanoTime() - startNanos) / 1_000_000).append('\t')
            .append(batteryTemperatureTenthsC());
        for (int cpu = 0; cpu < 8; cpu++) {
          line.append('\t').append(readFreq(cpu));
        }
        synchronized (this) {
          log.append(line).append('\n');
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    private static String readFreq(int cpu) {
      try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
          "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/scaling_cur_freq"))) {
        return r.readLine();
      } catch (IOException e) {
        return "-1";
      }
    }
  }

  private static void putLegacy(String name, double avgMillis, long gcCount) {
    Map<String, Object> entry = new TreeMap<>();
    entry.put("avgMillisPerCorpusPass", avgMillis);
    entry.put("gcCountDuringMeasuredIterations", gcCount);
    results.put(name, entry);
  }

  /** Battery temperature is the cheap thermal-state proxy available to an instrumentation test;
   *  recorded so a run on a warm phone is identifiable afterwards. -1 if unavailable. */
  private static int batteryTemperatureTenthsC() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    android.content.Intent battery = context.registerReceiver(null,
        new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
    return battery == null ? -1 : battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1);
  }

  private static void writeFile(String fileName, String content) {
    try (OutputStream file = PlatformTestStorageRegistry.getInstance().openOutputFile(fileName)) {
      file.write(content.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing benchmark results to " + fileName, e);
    }
  }

  /** Consumes every pass result so the JIT can't discard the benchmarked work. */
  private static volatile int sink;

   /**
    * Captures a sampling profile of a repeated {@code MatchLlk} pass: a sampler thread snapshots the test thread
    * via {@link Thread#getAllStackTraces()} (rather than instrumenting every call like {@link
    * Debug#startMethodTracing}, which would distort timing), aggregated into a table of the hottest
    * {@link #STACK_SAMPLE_DEPTH}-frame call chains. Runs unconditionally alongside the timing benchmarks; the
    * extra wall-clock cost is small.
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

   /** One node of the reversed call tree: the root's children are leaf frames, their children are callers of that
    *  leaf, and so on, so depth is distance from the leaf. {@code count} is the number of samples through this
    *  node's path, so a node's count is always &lt;= its parent's. */
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

   /** One stack sample's innermost-to-outermost frames as trie keys (leaf first, like {@link StackTraceElement}s),
    *  capped at {@link #STACK_SAMPLE_DEPTH} and trimmed at the {@link #captureSamplingProfile} boundary so trees
    *  bottom out in benchmarked code, not in identical sampler/harness/JUnit frames. */
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

   /** A floor under {@link #computeCallerCutoffPercent}'s rank-based cutoff, so a corpus with very few distinct
    *  leaves can't resolve to ~0% and print single-sample noise. */
   private static final double MIN_CALLER_CUTOFF_PERCENT = 0.1;

   /**
    * Writes the reversed call tree as plain text: the top {@link #CUTOFF_LEAF_RANK} leaves (capped at exactly
    * that many even if a tie straddles the boundary), each followed by its callers recursively ranked the same
    * way. Each percentage is that node's samples over the grand total. Caller printing stops once a node falls
    * strictly below {@link #computeCallerCutoffPercent}'s data-driven threshold (a caller exactly at it still
    * prints).
    */
   private static void writeSamplingProfile(String name, ChainNode root, int profileIterations)
       throws IOException {
     int totalSamples = root.count;
     List<ChainNode> allLeaves = root.rankedChildren();
     double cutoffPercent = computeCallerCutoffPercent(allLeaves, totalSamples);
     List<ChainNode> leaves = allLeaves.subList(0, Math.min(CUTOFF_LEAF_RANK, allLeaves.size()));

     StringBuilder body = new StringBuilder();
     body.append("sourceHash: ").append(BuildConfig.SOURCE_HASH).append("\n");
     body.append(String.format(Locale.ROOT,
         "Sampling profile of %s on %s%n"
             + "capture depth: %d frames, sample interval: %dms, profile iterations: %d, "
             + "total samples: %d, distinct leaf methods: %d (top %d shown)%n"
             + "caller cutoff: %.2f%% (the %d%s most common leaf's most common caller's own "
             + "%%-of-total-samples, floored at %.1f%%)%n"
             + "Reversed call tree: leaves ranked by frequency, then each leaf's callers ranked "
             + "the same way beneath it. A caller's %% is its own share of all samples, not the "
             + "leaf's -- see this file's generating code for the exact semantics.%n%n",
         name, deviceName(), STACK_SAMPLE_DEPTH, SAMPLE_INTERVAL_MILLIS, profileIterations,
         totalSamples, allLeaves.size(), leaves.size(), cutoffPercent, CUTOFF_LEAF_RANK,
         ordinalSuffix(CUTOFF_LEAF_RANK), MIN_CALLER_CUTOFF_PERCENT));
     for (ChainNode leaf : leaves) {
       double pct = 100.0 * leaf.count / totalSamples;
       body.append(String.format(Locale.ROOT, "* %s (%.1f%%)%n", leaf.frame, pct));
       printCallers(body, leaf, totalSamples, cutoffPercent, 1, isLlkFrame(leaf.frame));
     }

     String fileName = deviceName() + "_" + name + "_sampling.txt";
     try (OutputStream w = PlatformTestStorageRegistry.getInstance().openOutputFile(fileName)) {
       w.write(body.toString().getBytes(StandardCharsets.UTF_8));
     }
     System.out.println("AndroidCorpusBenchmark sampling profile written to " + fileName);
   }

   /**
    * The depth cutoff for {@link #printCallers}: the {@link #CUTOFF_LEAF_RANK}-th most common leaf's most common
    * caller's percentage (or the leaf's own, if it has no callers), tracking where the data stops separating hot
    * chains from noise instead of a fixed depth. Falls back to {@link #MIN_CALLER_CUTOFF_PERCENT} with fewer
    * leaves or a smaller value.
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

   /** Recursively prints {@code node}'s callers most common first, stopping (for it and every remaining sibling)
    *  once a percentage drops below {@code cutoffPercent}. While the path from the leaf has no {@code
    *  com.tbohne.llkpattern.} frame yet ({@code llkSeenInPath} false) the cutoff is suspended, so every printed
    *  stack reaches this project's code (as {@code AllocationSamplingRunner.printCallers} does). */
   private static void printCallers(
       StringBuilder body, ChainNode node, int totalSamples, double cutoffPercent, int depth,
       boolean llkSeenInPath) {
     for (ChainNode caller : node.rankedChildren()) {
       double pct = 100.0 * caller.count / totalSamples;
       if (pct < cutoffPercent && llkSeenInPath) {
         break;
       }
       for (int i = 0; i < depth; i++) {
         body.append("   ");
       }
       body.append(String.format(Locale.ROOT, "* %s (%.1f%%)%n", caller.frame, pct));
       printCallers(
           body, caller, totalSamples, cutoffPercent, depth + 1,
           llkSeenInPath || isLlkFrame(caller.frame));
     }
   }

   private static final String LLKPATTERN_PACKAGE_PREFIX = "com.tbohne.llkpattern.";

   private static boolean isLlkFrame(String frame) {
     return frame != null && frame.startsWith(LLKPATTERN_PACKAGE_PREFIX);
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

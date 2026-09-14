package com.tbohne.llkpattern.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Captures an <b>allocation</b>-stack sampling profile (not CPU time -- see {@link
 * SamplingRunner} for that) of one or more {@link CorpusBenchmark} methods, via the JDK's built-in
 * Flight Recorder rather than JMH: JFR's {@code jdk.ObjectAllocationSample} event (JDK 16+) is a
 * low-overhead statistical allocation profiler -- each event already carries an extrapolated byte
 * {@code weight} for the stack that produced it, rather than firing once per actual allocation
 * (which would be far too expensive to record continuously) -- see documents/notes.md's
 * 2026-09-10 entry for the caveats around that extrapolated weight (low-sample-count leaves can be
 * noisy). No JMH machinery is involved here at all: JFR does its own background sampling on a
 * fixed rate, not something JMH's profiler API mediates, so this just drives {@link
 * CorpusBenchmark}'s {@code @Benchmark} methods directly in a plain loop (JMH's own trick for
 * driving a {@code @Benchmark} method outside a real JMH run: construct a {@link Blackhole} with
 * its documented magic-string constructor) while a {@link Recording} is active, then parses the
 * dumped {@code .jfr} file and re-aggregates the events into the same reversed-call-tree format
 * {@code AndroidCorpusBenchmark}'s CPU sampler uses (leaves ranked by frequency -- here, by
 * aggregate weight -- then each leaf's callers ranked the same way beneath it) -- see that class's
 * {@code writeSamplingProfile} for the shared design rationale; duplicated here rather than
 * shared across the two modules' incompatible source sets, matching this project's existing
 * precedent (see {@code AndroidGoldenRow}'s own class doc).
 *
 * <p>Invoked by the {@code jmhAllocSampling} Gradle task ({@code llkpattern/build.gradle}) --
 * opt-in, not chained after the routine {@code jmh}/{@code jmhSampling} tasks, since it's a
 * separate exploratory profiling pass rather than something paired 1:1 with every timing run. Run
 * via {@code ./gradlew :llkpattern:jmhAllocSampling}.
 *
 * <p>Args: {@code <machineName> <benchmarksDir> <iterations> <benchmarkName>...} -- unlike {@link
 * SamplingRunner}, there's no warmup/measurement *time* to configure: JFR's sampling rate is fixed
 * regardless of how long each pass takes, so the only knob is how many passes to run (enough to
 * accumulate a statistically useful number of allocation samples).
 */
public final class AllocationSamplingRunner {
  private AllocationSamplingRunner() {}

  /** Same rationale as {@code AndroidCorpusBenchmark.STACK_SAMPLE_DEPTH}: deep enough to reach
   *  past dispatch into whichever concrete construct is hot, with the reversed-tree format (not a
   *  flat chain list) meaning deeper capture doesn't cost readability. */
  private static final int STACK_SAMPLE_DEPTH = 10;
  private static final int CUTOFF_LEAF_RANK = 10;
  private static final double MIN_CALLER_CUTOFF_PERCENT = 0.5;

  // JMH's own documented magic string for constructing a Blackhole outside a real JMH run (see
  // Blackhole's class doc) -- this lets us call CorpusBenchmark's @Benchmark methods directly in a
  // plain loop without JMH's Runner/Options machinery, which this class has no use for since JFR
  // does its own independent background sampling.
  private static final String BLACKHOLE_MAGIC =
      "Today's password is swordfish. I understand instantiating Blackholes directly is"
          + " dangerous.";

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      throw new IllegalArgumentException(
          "Usage: AllocationSamplingRunner <machineName> <benchmarksDir> <iterations>"
              + " <benchmarkName>... (did the jmhAllocSampling Gradle task's args change shape?)");
    }
    String machineName = args[0];
    Path benchmarksDir = Path.of(args[1]);
    int iterations = Integer.parseInt(args[2]);
    for (int i = 3; i < args.length; i++) {
      captureOneBenchmark(machineName, benchmarksDir, iterations, args[i]);
    }
  }

  private static void captureOneBenchmark(
      String machineName, Path benchmarksDir, int iterations, String name) throws Exception {
    CorpusBenchmark benchmark = new CorpusBenchmark();
    benchmark.setUp();
    Blackhole bh = new Blackhole(BLACKHOLE_MAGIC);
    Runnable pass = benchmarkPass(benchmark, bh, name);

    // Untimed warmup, run before the recording starts -- there's no JMH warmup here, but the JIT
    // still needs to reach steady state before the samples we keep are representative, the same
    // reasoning as the timing benchmark's own warmup iterations.
    int warmupIterations = Math.max(50, iterations / 10);
    for (int i = 0; i < warmupIterations; i++) {
      pass.run();
    }

    Path jfrFile = Files.createTempFile("alloc-sampling-" + name, ".jfr");
    try {
      Recording recording = new Recording();
      recording.enable("jdk.ObjectAllocationSample");
      recording.start();
      try {
        for (int i = 0; i < iterations; i++) {
          pass.run();
        }
      } finally {
        recording.stop();
      }
      recording.dump(jfrFile);
      recording.close();

      ChainNode root = new ChainNode(null);
      long eventCount = 0;
      try (RecordingFile recordingFile = new RecordingFile(jfrFile)) {
        while (recordingFile.hasMoreEvents()) {
          RecordedEvent event = recordingFile.readEvent();
          if (!event.getEventType().getName().equals("jdk.ObjectAllocationSample")) {
            continue;
          }
          RecordedStackTrace stackTrace = event.getStackTrace();
          if (stackTrace == null) {
            continue;
          }
          List<String> leafToOuter = extractFrames(stackTrace.getFrames());
          if (leafToOuter.isEmpty()) {
            continue;
          }
          eventCount++;
          long weight = event.getLong("weight");
          root.count += weight;
          ChainNode node = root;
          for (String frameKey : leafToOuter) {
            node = node.child(frameKey);
            node.count += weight;
          }
        }
      }

      if (root.children.isEmpty()) {
        throw new IllegalStateException(
            "AllocationSamplingRunner collected zero jdk.ObjectAllocationSample events with a"
                + " stack trace for '" + name + "' -- iterations too few for the sampler's fixed"
                + " rate to fire, or did the event/field names change upstream (checked via `jfr"
                + " print --events jdk.ObjectAllocationSample` on JDK 25 while writing this)?");
      }
      writeSamplingProfile(machineName, benchmarksDir, name, root, iterations, eventCount);
    } finally {
      Files.deleteIfExists(jfrFile);
    }
  }

  private static Runnable benchmarkPass(CorpusBenchmark benchmark, Blackhole bh, String name) {
    switch (name) {
      case "llkCompile":
        return () -> benchmark.llkCompile(bh);
      case "llkMatch":
        return () -> benchmark.llkMatch(bh);
      case "regexCompile":
        return () -> benchmark.regexCompile(bh);
      case "regexMatch":
        return () -> benchmark.regexMatch(bh);
      default:
        throw new IllegalArgumentException(
            "Unknown benchmark name '" + name + "' -- did you mean one of llkCompile/llkMatch/"
                + "regexCompile/regexMatch (CorpusBenchmark's own @Benchmark methods)?");
    }
  }

  /** The innermost-to-outermost frames of one allocation sample's stack trace, as trie-insertion
   *  keys (leaf first, matching {@link RecordedStackTrace#getFrames()}'s own order), capped at
   *  {@link #STACK_SAMPLE_DEPTH} and trimmed at this class's own boundary (including the
   *  method-reference lambda classes {@link #benchmarkPass} hands back) so trees bottom out in
   *  benchmarked code rather than continuing into this harness's own loop/main frames. */
  private static List<String> extractFrames(List<RecordedFrame> frames) {
    String harnessClass = AllocationSamplingRunner.class.getName();
    List<String> keys = new ArrayList<>(STACK_SAMPLE_DEPTH);
    for (RecordedFrame f : frames) {
      if (keys.size() >= STACK_SAMPLE_DEPTH) {
        break;
      }
      String className = f.getMethod().getType().getName();
      if (className.equals(harnessClass) || className.startsWith(harnessClass + "$$Lambda")) {
        break;
      }
      keys.add(className + "." + f.getMethod().getName() + ":" + f.getLineNumber());
    }
    return keys;
  }

  /** One node of the reversed allocation-call tree: the root's children are leaf (innermost)
   *  frames, each of *their* children is a caller of that leaf, and so on outward. {@code count}
   *  is the summed JFR sample {@code weight} (extrapolated allocated bytes) attributed to this
   *  exact node's path from the root -- i.e. this is a weight tree, not a raw sample-count tree
   *  the way {@code AndroidCorpusBenchmark}'s CPU-sampling tree is, since allocation profiling's
   *  natural unit is bytes attributed, not how many discrete samples happened to land on a path. */
  private static final class ChainNode {
    final String frame; // null only for the synthetic root.
    long count;
    final Map<String, ChainNode> children = new HashMap<>();

    ChainNode(String frame) {
      this.frame = frame;
    }

    ChainNode child(String frameKey) {
      return children.computeIfAbsent(frameKey, ChainNode::new);
    }

    List<ChainNode> rankedChildren() {
      List<ChainNode> ranked = new ArrayList<>(children.values());
      ranked.sort((a, b) -> Long.compare(b.count, a.count));
      return ranked;
    }
  }

  private static void writeSamplingProfile(
      String machineName, Path benchmarksDir, String name, ChainNode root, int iterations,
      long eventCount)
      throws IOException {
    long totalWeight = root.count;
    List<ChainNode> allLeaves = root.rankedChildren();
    double cutoffPercent = computeCallerCutoffPercent(allLeaves, totalWeight);
    List<ChainNode> leaves = allLeaves.subList(0, Math.min(CUTOFF_LEAF_RANK, allLeaves.size()));

    StringBuilder body = new StringBuilder();
    body.append(String.format(Locale.ROOT,
        "Desktop allocation-sampling profile of CorpusBenchmark.%s on %s%n"
            + "Captured: %s, via JDK Flight Recorder's jdk.ObjectAllocationSample event, "
            + "auto-generated by the jmhAllocSampling Gradle task (llkpattern/build.gradle).%n"
            + "capture depth: %d frames, corpus passes: %d, allocation samples: %d, "
            + "total sampled weight: %d bytes (extrapolated -- see this file's generating code's "
            + "javadoc), distinct leaf methods: %d (top %d shown)%n"
            + "caller cutoff: %.2f%% (the %d%s most common leaf's most common caller's own "
            + "%%-of-total-weight, floored at %.1f%%)%n"
            + "Reversed call tree: leaves ranked by aggregate weight, then each leaf's callers "
            + "ranked the same way beneath it. A caller's %% is its own share of total sampled "
            + "weight, not the leaf's -- see this file's generating code for the exact "
            + "semantics.%n%n",
        name, machineName, LocalDate.now(), STACK_SAMPLE_DEPTH, iterations, eventCount,
        totalWeight, allLeaves.size(), leaves.size(), cutoffPercent, CUTOFF_LEAF_RANK,
        ordinalSuffix(CUTOFF_LEAF_RANK), MIN_CALLER_CUTOFF_PERCENT));
    for (ChainNode leaf : leaves) {
      double pct = 100.0 * leaf.count / totalWeight;
      body.append(String.format(Locale.ROOT, "* %s (%.1f%%)%n", leaf.frame, pct));
      printCallers(body, leaf, totalWeight, cutoffPercent, 1, isLlkFrame(leaf.frame));
    }

    Path outFile = benchmarksDir.resolve(machineName + "_" + name + "_alloc_sampling.txt");
    Files.write(outFile, body.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println("AllocationSamplingRunner: wrote " + outFile);
  }

  /** Same rank-based cutoff rule as {@code AndroidCorpusBenchmark.computeCallerCutoffPercent} --
   *  see that method's javadoc for the full reasoning. */
  private static double computeCallerCutoffPercent(List<ChainNode> leaves, long totalWeight) {
    int rankIndex = Math.min(CUTOFF_LEAF_RANK, leaves.size()) - 1;
    if (rankIndex < 0) {
      return MIN_CALLER_CUTOFF_PERCENT;
    }
    ChainNode cutoffLeaf = leaves.get(rankIndex);
    List<ChainNode> callers = cutoffLeaf.rankedChildren();
    ChainNode reference = callers.isEmpty() ? cutoffLeaf : callers.get(0);
    double pct = 100.0 * reference.count / totalWeight;
    return Math.max(pct, MIN_CALLER_CUTOFF_PERCENT);
  }

  /** {@code llkSeenInPath} is whether the path from the leaf down to (and including) {@code node}
   *  already contains a {@code com.tbohne.llkpattern.} frame. While it doesn't, the normal
   *  percent-of-total cutoff is suspended for {@code node}'s own callers -- guaranteeing every
   *  printed stack reaches into this project's own code, rather than bottoming out entirely in
   *  JDK/library allocation machinery (e.g. autoboxing, collection resizing) that the cutoff would
   *  otherwise have hidden the llkpattern frame beneath. Once an llkpattern frame has appeared,
   *  the ordinary cutoff resumes for frames further out. */
  private static void printCallers(
      StringBuilder body, ChainNode node, long totalWeight, double cutoffPercent, int depth,
      boolean llkSeenInPath) {
    for (ChainNode caller : node.rankedChildren()) {
      double pct = 100.0 * caller.count / totalWeight;
      if (pct < cutoffPercent && llkSeenInPath) {
        break;
      }
      for (int i = 0; i < depth; i++) {
        body.append("   ");
      }
      body.append(String.format(Locale.ROOT, "* %s (%.1f%%)%n", caller.frame, pct));
      printCallers(
          body, caller, totalWeight, cutoffPercent, depth + 1,
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
}

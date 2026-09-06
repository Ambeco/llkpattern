package com.tbohne.llkpattern.corpus;

import com.tbohne.llkpattern.Ll1Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a {@link GoldenRow}'s (pattern, flags, input, mode) tuple through either {@code
 * java.util.regex.Pattern}/{@code Matcher} or {@code Ll1Pattern}/{@code Matcher}, producing a
 * {@link MatchOutcome}. Shared by the corpus generator (which records the outcome into a golden
 * file) and the parameterized tests (which re-run it and compare against the recorded outcome).
 */
public final class MatchRunner {
  private MatchRunner() {}

  /** A new daemon thread per call, deliberately NOT reused across calls: {@code
   *  java.util.regex.Matcher} (and, if buggy, {@code Ll1Pattern}'s Matcher) offers no way to
   *  interrupt a match in progress, so a call that times out leaves its worker thread running
   *  forever in the background. Marking it daemon is what lets the JVM still exit despite that.
   *  A *shared* single-thread pool was tried first and rejected: once one task hangs forever, it
   *  permanently occupies that thread, and every subsequent call queued behind it would then
   *  report a spurious immediate timeout too -- silently poisoning every row after the first
   *  pathological one for the rest of the run. A cached pool (fresh thread per call, reused only
   *  once genuinely idle) avoids that: a hung task leaks exactly one thread, and every other call
   *  still gets a real timeoutMs budget of its own. */
  private static final ExecutorService TIMEOUT_POOL =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "MatchRunner-timeout-worker");
            t.setDaemon(true);
            return t;
          });

  /** Runs {@code task} with a wall-clock timeout, on its own thread (see {@link #TIMEOUT_POOL}).
   *  Returns {@link Optional#empty()} if it didn't finish in time. */
  public static Optional<MatchOutcome> runWithTimeout(
      java.util.function.Supplier<MatchOutcome> task, long timeoutMs) {
    Future<MatchOutcome> future = TIMEOUT_POOL.submit(task::get);
    try {
      return Optional.of(future.get(timeoutMs, TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      return Optional.empty();
    } catch (java.util.concurrent.ExecutionException e) {
      // The task itself is documented to catch RuntimeException internally (see runRegex/runLlk)
      // -- reaching here means something escaped that (e.g. an Error), which is worth surfacing
      // loudly rather than silently swallowing as "just another timeout".
      throw new RuntimeException(
          "Unexpected exception escaped MatchRunner task (this shouldn't happen -- "
              + "runRegex/runLlk are supposed to catch RuntimeException internally)",
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting for MatchRunner task", e);
    }
  }

  /**
   * Sanity check demanded by the project owner (2026-09-06), before investigating the
   * lone-surrogate-vs-{@code java.util.regex} divergence noted in remaining_work.md any further:
   * confirm {@code row.input} still is what the golden file says it is, right before either
   * engine ever sees it, rather than assuming it. Two checks:
   *
   * <ol>
   *   <li>{@code row.input} contains no U+FFFD (the Unicode replacement character) -- if some step
   *       upstream (file decoding, TSV field decoding) had silently "sanitized" an invalid/lone
   *       UTF-16 surrogate instead of preserving it verbatim, this is what that would look like.
   *   <li>{@code row.input} round-trips losslessly through {@code GoldenTsv}'s own
   *       encode/decode -- exercises the exact lone-surrogate escaping path ({@code
   *       GoldenTsv#encodeField}'s {@code \xHHHH} handling) against whatever string we were
   *       actually handed, independent of whatever the file on disk currently contains.
   * </ol>
   */
  private static void assertInputIntegrity(GoldenRow row) {
    if (row.input.indexOf('\uFFFD') >= 0) {
      throw new AssertionError(
          "Golden row's input for " + row.displayName() + " contains U+FFFD (the Unicode "
              + "replacement character) -- if the original input was a lone/unpaired UTF-16 "
              + "surrogate, something in the read/decode pipeline silently replaced it instead "
              + "of preserving it verbatim. Input code units: " + describeCodeUnits(row.input));
    }
    String roundTripped = GoldenTsv.decodeField(GoldenTsv.encodeField(row.input));
    if (!roundTripped.equals(row.input)) {
      throw new AssertionError(
          "Golden row's input for " + row.displayName() + " does not round-trip losslessly "
              + "through GoldenTsv.encodeField/decodeField -- a lone surrogate or other special "
              + "character may be getting corrupted. Before: " + describeCodeUnits(row.input)
              + " After: " + describeCodeUnits(roundTripped));
    }
  }

  private static String describeCodeUnits(String s) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < s.length(); i++) {
      if (i > 0) {
        sb.append(' ');
      }
      sb.append(String.format("U+%04X", (int) s.charAt(i)));
    }
    return sb.append(']').toString();
  }

  public static MatchOutcome runRegex(GoldenRow row) {
    assertInputIntegrity(row);
    java.util.regex.Pattern p;
    try {
      p = java.util.regex.Pattern.compile(row.pattern, row.flagBits());
    } catch (RuntimeException e) {
      return MatchOutcome.compileFailure(e);
    }
    try {
      java.util.regex.Matcher m = p.matcher(row.input);
      boolean found = attempt(row, m::matches, m::lookingAt, m::find);
      if (!found) {
        return MatchOutcome.noMatch();
      }
      List<String> groups = new ArrayList<>(m.groupCount() + 1);
      for (int i = 0; i <= m.groupCount(); i++) {
        groups.add(m.group(i));
      }
      return MatchOutcome.matched(groups);
    } catch (RuntimeException e) {
      return MatchOutcome.matchFailure(e);
    }
  }

  public static MatchOutcome runLlk(GoldenRow row) {
    assertInputIntegrity(row);
    Ll1Pattern p;
    try {
      p = Ll1Pattern.compile(row.pattern, row.flagBits());
    } catch (RuntimeException e) {
      return MatchOutcome.compileFailure(e);
    }
    try {
      com.tbohne.llkpattern.Matcher m = p.matcher(row.input);
      boolean found = attempt(row, m::matches, m::lookingAt, m::find);
      if (!found) {
        return MatchOutcome.noMatch();
      }
      List<String> groups = new ArrayList<>(m.groupCount() + 1);
      for (int i = 0; i <= m.groupCount(); i++) {
        groups.add(m.group(i));
      }
      return MatchOutcome.matched(groups);
    } catch (RuntimeException e) {
      return MatchOutcome.matchFailure(e);
    }
  }

  private interface BooleanSupplier {
    boolean get();
  }

  private static boolean attempt(
      GoldenRow row, BooleanSupplier matches, BooleanSupplier lookingAt, BooleanSupplier find) {
    switch (row.mode) {
      case MATCHES:
        return matches.get();
      case LOOKING_AT:
        return lookingAt.get();
      case FIND:
        return find.get();
      default:
        throw new IllegalArgumentException(
            "Unhandled GoldenRow.Mode " + row.mode + " -- did you add a new enum constant "
                + "without adding a case here?");
    }
  }
}

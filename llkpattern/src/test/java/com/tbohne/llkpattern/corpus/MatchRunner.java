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

  public static MatchOutcome runRegex(GoldenRow row) {
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

package com.tbohne.llkpattern.corpus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Base class for a scraped-corpus differential test: one JUnit4 {@link Parameterized} test class
 * per source (e.g. OpenJDK's regex test suite), each just naming a golden TSV file via {@link
 * #goldenFilePath()}. See documents/remaining_work.md's "Scraped-corpus differential test harness"
 * entry for the full design.
 *
 * <p>Each row re-runs only {@code Ll1Pattern} and compares it against the golden file's recorded
 * {@code llk*} columns -- {@code java.util.regex} is deliberately NOT re-run here (per the
 * project owner, 2026-09-06). Its recorded {@code regex*} columns exist purely as documentation
 * of what it did when the row was generated; re-running it on every test invocation would be pure
 * cost with no payoff (its behavior is fixed JDK behavior, not something this project can
 * regress) and reintroduces exactly the catastrophic-backtracking risk {@link CorpusGenerator}'s
 * per-row timeout/simplification exists to avoid at generation time -- a golden {@code input} was
 * only ever verified fast enough for both engines *once*, at generation time, not guaranteed fast
 * forever. llk itself isn't at the same risk in practice: a pattern pathological for backtracking
 * regex is exactly the kind of thing llk's LL(1) ambiguity check tends to reject at compile time
 * (an exception, not a hang) -- see documents/remaining_work.md for the (temporary, generation-
 * time-only) timeout mechanism this decision replaces for ongoing test runs.
 *
 * <p>A row failing here means either a real llk regression, or that the golden file's recorded
 * outcome needs regenerating (see {@link CorpusGenerator}) after a deliberate, intended engine
 * change.
 *
 * <p>The {@code status} and {@code regex*} columns are intentionally not part of the
 * parameterized data used for assertions -- {@code status} is triage metadata for humans, and
 * {@code regex*} is reference documentation, not a live test input.
 */
@RunWith(Parameterized.class)
public abstract class ScrapedCorpusTestBase {
  @Parameters(name = "{0}")
  public static List<GoldenRow> data() {
    throw new UnsupportedOperationException(
        "Subclasses must declare their own @Parameters static method returning "
            + "GoldenTsv.read(<their golden file path>) -- JUnit4's Parameterized runner looks "
            + "up @Parameters on the concrete class, so this base-class method is never actually "
            + "used, but exists to document the contract every subclass must follow.");
  }

  protected final GoldenRow row;

  protected ScrapedCorpusTestBase(GoldenRow row) {
    this.row = row;
  }

  protected static List<GoldenRow> readGolden(Path path) {
    return GoldenTsv.read(path);
  }

  /** Wall-clock budget for the disabled re-verification below -- see {@link
   *  CorpusGenerator#TIMEOUT_MS}'s javadoc for why a timeout is needed at all when actually
   *  re-running java.util.regex against a golden {@code input}. */
  private static final long REGEX_REVERIFY_TIMEOUT_MS = 1000;

  /**
   * Disabled by default (per the project owner, 2026-09-06): re-running {@code java.util.regex}
   * on every test invocation is pure cost with no regression-detection payoff (see the class
   * javadoc), so this doesn't run as part of the normal suite. Kept, rather than deleted, so a
   * human can re-enable it on demand (comment out {@code @Ignore}, or run it directly) to
   * double-check a row's {@code regex*} columns against the JDK actually installed, without
   * having to re-engineer the timeout-guarded re-run machinery from scratch. Uses {@link
   * MatchRunner#runWithTimeout} for the same reason {@link CorpusGenerator} does: a golden {@code
   * input} was only ever verified fast at generation time, not guaranteed fast forever.
   */
  @Ignore("Disabled by default -- java.util.regex behavior is fixed JDK behavior, not something "
      + "this project can regress; re-enable manually to double check a row against the "
      + "installed JDK. See this method's javadoc.")
  @Test
  public void regexMatchesGolden() {
    Optional<MatchOutcome> actual =
        MatchRunner.runWithTimeout(() -> MatchRunner.runRegex(row), REGEX_REVERIFY_TIMEOUT_MS);
    if (!actual.isPresent()) {
      fail(
          "java.util.regex timed out (>" + REGEX_REVERIFY_TIMEOUT_MS + "ms) re-verifying "
              + row.displayName() + " -- this golden row's input may have grown pathological "
              + "again, or this is a new JDK version's regex engine behaving differently.");
    }
    MatchOutcome golden =
        MatchOutcome.decode(
            row.regexCompileException, row.regexMatchException, row.regexMatchResult);
    assertEquals(
        "java.util.regex outcome changed vs. the golden file for " + row.displayName()
            + " -- either a real regression in how this row was scraped/encoded, or the JDK's "
            + "own regex behavior changed. Status was: " + row.status,
        golden,
        actual.get());
  }

  /** Wall-clock budget for llk's own re-verification below. Belt-and-suspenders: llk isn't
   *  expected to hang the way backtracking regex can (a pathological-for-backtracking pattern is
   *  exactly the shape LL(1) ambiguity detection tends to reject at compile time, per the project
   *  owner, 2026-09-06) -- but guarding it costs nothing and turns any future counterexample into
   *  a clean, fast test failure instead of a hung test run. */
  private static final long LLK_REVERIFY_TIMEOUT_MS = 2000;

  @Test
  public void llkMatchesGolden() {
    Optional<MatchOutcome> actual =
        MatchRunner.runWithTimeout(() -> MatchRunner.runLlk(row), LLK_REVERIFY_TIMEOUT_MS);
    if (!actual.isPresent()) {
      fail(
          "Ll1Pattern timed out (>" + LLK_REVERIFY_TIMEOUT_MS + "ms) matching " + row.displayName()
              + " -- llk isn't expected to hang the way backtracking regex can; this may be a "
              + "real new bug (an infinite loop in the compiled matcher graph, not just a "
              + "correctness divergence).");
    }
    MatchOutcome golden =
        MatchOutcome.decode(row.llkCompileException, row.llkMatchException, row.llkMatchResult);
    assertEquals(
        "Ll1Pattern outcome changed vs. the golden file for " + row.displayName()
            + " -- if this is an intended behavior change, regenerate the golden file "
            + "(CorpusGenerator) and re-review its status column rather than just accepting the "
            + "new value. Status was: " + row.status,
        golden,
        actual.get());
  }
}

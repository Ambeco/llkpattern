package com.tbohne.llkpattern;

import static com.tbohne.llkpattern.SupplementaryChars.A;
import static com.tbohne.llkpattern.SupplementaryChars.B;
import static com.tbohne.llkpattern.SupplementaryChars.C;
import static com.tbohne.llkpattern.SupplementaryChars.repeat;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for Ll1Pattern/Matcher's public API: matches(), lookingAt(), find(), regions, groups.
 *
 * <p>Uses SupplementaryChars' A/B/C (supplementary/astral code points) in place of the plain
 * ASCII 'a'/'b'/'c' this file originally used, throughout -- see SupplementaryPatternTextTest.
 * Every start()/end()/region() offset below is in {@code char} units, not code points (matching
 * {@code java.util.regex}), and is recalculated accordingly -- {@code A.length() == 2}, not 1.
 * {@code find_onEmptyMatchingPattern_stillMakesForwardProgress} and {@code
 * reset_clearsPriorMatchAndRegion} are left as plain ASCII: both are really about Matcher-internal
 * bookkeeping (zero-width-match forward progress; region bookkeeping surviving reset()) that plain
 * single-char positions already exercise just as well, without entangling char-vs-code-point
 * stepping into what each is actually testing.
 */
@RunWith(JUnit4.class)
public class MatcherApiTest {

  // --- matches() vs lookingAt(): the same compiled graph, different "did we use it all" check ---

  @Test
  public void matches_requiresConsumingWholeInput() {
    assertThat(Ll1Pattern.compile(A).matcher(A).matches(), is(true));
    assertThat(Ll1Pattern.compile(A).matcher(A + A).matches(), is(false));
  }

  @Test
  public void lookingAt_onlyRequiresAMatchingPrefix() {
    assertThat(Ll1Pattern.compile(A).matcher(A + A).lookingAt(), is(true));
    assertThat(Ll1Pattern.compile(A).matcher(B + A).lookingAt(), is(false));
  }

  // --- find(): unanchored search ---

  @Test
  public void find_locatesMatchNotAtTheStart() {
    Matcher m = Ll1Pattern.compile(B).matcher(A + A + B);
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(2 * A.length()));
    assertThat(m.end(), is(2 * A.length() + B.length()));
    assertThat(m.group(), is(B));
  }

  @Test
  public void find_noMatchAnywhere_returnsFalse() {
    assertThat(Ll1Pattern.compile("z").matcher(A + A + B).find(), is(false));
  }

  @Test
  public void find_repeatedCalls_advancePastPreviousMatch() {
    Matcher m = Ll1Pattern.compile(A).matcher(A + A + A);
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(0));
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(A.length()));
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(2 * A.length()));
    assertThat(m.find(), is(false));
  }

  @Test
  public void find_withExplicitStart_searchesFromThatIndex() {
    Matcher m = Ll1Pattern.compile(A).matcher(A + A + A);
    // A.length(), not the literal 1 the original ASCII version used -- searching from a
    // mid-code-point index isn't what this test is about; this starts exactly at the second A.
    assertThat(m.find(A.length()), is(true));
    assertThat(m.start(), is(A.length()));
  }

  @Test
  public void find_onEmptyMatchingPattern_stillMakesForwardProgress() {
    // "a*" can match zero characters anywhere -- find() must still advance past a zero-width
    // match rather than looping on the same position forever. Plain ASCII: this is about
    // Matcher's own zero-width forward-progress bookkeeping, not code-point width.
    Matcher m = Ll1Pattern.compile("a*").matcher("b");
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(0));
    assertThat(m.end(), is(0));
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(1));
  }

  // --- Regions ---

  @Test
  public void region_restrictsMatchingToThatSpan() {
    Matcher m = Ll1Pattern.compile(A).matcher("xx" + A + "xx");
    m.region(2, 2 + A.length());
    assertThat(m.matches(), is(true));
    assertThat(m.start(), is(2));
  }

  @Test
  public void region_findRespectsRegionEnd() {
    Matcher m = Ll1Pattern.compile(A).matcher(A + "x" + A);
    m.region(A.length(), A.length() + 1); // only the middle "x" is in-region
    assertThat(m.find(), is(false));
  }

  // --- reset() ---

  @Test
  public void reset_clearsPriorMatchAndRegion() {
    // Plain ASCII: this is about region bookkeeping surviving reset(), not pattern content.
    Matcher m = Ll1Pattern.compile("a").matcher("aax");
    m.region(1, 2);
    m.reset();
    assertThat(m.regionStart(), is(0));
    assertThat(m.regionEnd(), is(3));
  }

  @Test
  public void resetWithNewInput_matchesAgainstTheNewString() {
    Matcher m = Ll1Pattern.compile(A).matcher(A);
    assertThat(m.matches(), is(true));
    m.reset(B);
    assertThat(m.matches(), is(false));
  }

  @Test
  public void resetWithCharSequence_matchesAgainstItsCurrentTextAndSnapshotsIt() {
    Matcher m = Ll1Pattern.compile("ab+").matcher("zz");
    StringBuilder sb = new StringBuilder("xabbx");
    m.reset(sb);
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("abb"));
    sb.setLength(0);
    m.reset();
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(1));
  }

  @Test
  public void boundaryTypeSuffixIsRejected() {
    for (String p : new String[] {"\\b{g}", "a\\B{g}", "\\b{"}) {
      assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile(p));
    }
  }

  @Test
  public void danglingQuantifierIsRejected() {
    for (String p : new String[] {"*a", "a|+b", "(?i)?a", "a**", "{2}a"}) {
      assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile(p));
    }
  }

  // --- Groups ---

  @Test
  public void groupCount_countsOnlyRealCapturingGroups() {
    assertThat(
        Ll1Pattern.compile("(" + A + ")(?:" + B + ")(" + C + ")").matcher(A + B + C).groupCount(),
        is(2));
  }

  @Test
  public void group_beforeAnyMatchAttempt_throwsIllegalStateException() {
    Matcher m = Ll1Pattern.compile(A).matcher(A);
    assertThrows(IllegalStateException.class, m::group);
    assertThrows(IllegalStateException.class, m::start);
    assertThrows(IllegalStateException.class, m::end);
  }

  @Test
  public void group_withInvalidNumber_throwsIndexOutOfBoundsException() {
    Matcher m = Ll1Pattern.compile("(" + A + ")").matcher(A);
    m.matches();
    assertThrows(IndexOutOfBoundsException.class, () -> m.group(2));
  }

  @Test
  public void group_withUnknownName_throwsIllegalArgumentException() {
    // Group NAME stays ASCII -- GroupName -> [A-Za-z0-9] is an ASCII-only grammar production by
    // design (see PatternParser's own BNF comment), unrelated to the captured content's width.
    Matcher m = Ll1Pattern.compile("(?<x>" + A + ")").matcher(A);
    m.matches();
    assertThrows(IllegalArgumentException.class, () -> m.group("y"));
  }

  @Test
  public void unmatchedOptionalGroup_returnsNullNotEmptyString() {
    Matcher m = Ll1Pattern.compile("(" + A + ")?" + B).matcher(B);
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is((String) null));
    assertThat(m.start(1), is(-1));
    assertThat(m.end(1), is(-1));
  }

  // --- asPredicate() / static matches() ---

  @Test
  public void asPredicate_findsAnywhere() {
    assertThat(Ll1Pattern.compile(A + "+").asPredicate().test(repeat(A, 3)), is(true));
    assertThat(Ll1Pattern.compile(A + "+").asPredicate().test(repeat(A, 3) + B), is(true));
    assertThat(Ll1Pattern.compile(A + "+").asPredicate().test(B), is(false));
    assertThat(Ll1Pattern.compile(A + "+").asMatchPredicate().test(repeat(A, 3) + B), is(false));
  }

  @Test
  public void staticMatches_delegatesToMatcherMatches() {
    assertThat(Ll1Pattern.matches(A + "+", repeat(A, 3)), is(true));
  }

  // --- Per-attempt state (quantifiableCounts/captureGroups) must not leak between separate match
  // attempts -- see remaining_work.md's dated bug entry. A loop's iteration counter is normally
  // only reset to 0 when its own EndLoopMatcherConstruct exit fires; an attempt that instead fails
  // by exceeding `max` (LoopMatcherConstruct's own check) never reaches that reset, so without an
  // explicit per-attempt reset in Matcher#attemptMatch, a later attempt (a different find() scan
  // position, or a second matches()/lookingAt()/find() call on a reused Matcher) would read a
  // stale, nonzero counter and could spuriously satisfy a `min` check it should have failed.

  @Test
  public void find_boundedQuantifier_doesNotLeakLoopCountAcrossScanPositions() {
    // "a{2,3}" hard-fails (no backtracking) at every start position where a 4th 'a' follows the
    // first three, since nothing after the loop can tell "stop at 3" from "keep going" -- find()
    // must NOT let that failed attempt's loop counter leak into scanning the next position, which
    // used to spuriously "match" an empty string once the counter happened to already exceed min.
    Matcher m = Ll1Pattern.compile(A + "{2,3}").matcher(repeat(A, 4));
    assertThat(m.find(), is(true));
    assertThat(m.group(), is(repeat(A, 3)));
  }

  @Test
  public void find_optionalQuantifier_doesNotLeakCaptureStateAcrossScanPositions() {
    Matcher m = Ll1Pattern.compile(A + "?" + B).matcher(repeat(A, 4) + B);
    assertThat(m.find(), is(true));
    assertThat(m.group(), is(A + B));
  }

  @Test
  public void quantifiedCapturingGroup_doesNotSpuriouslyMatchAfterFailedEarlierPosition() {
    // Before the fix, a failed attempt at an earlier find() scan position could leave a nonzero
    // loop counter that let "(ab)+" spuriously satisfy its own min==1 check with an empty match,
    // even though "ab" never actually occurs in the input.
    Matcher m = Ll1Pattern.compile("(" + A + B + ")+").matcher(A + "iiiiw");
    assertThat(m.find(), is(false));
  }

  // --- Capturing group numbering must follow opening-paren order, not closing-paren order -- see
  // remaining_work.md's dated bug entry: a recursive-descent parser's nested groups always finish
  // parsing (and, before the fix, always finished claiming their index) before the enclosing
  // group's own call returns, which backwards-numbered every pattern with nested capturing groups.

  @Test
  public void nestedCapturingGroups_numberedInOpeningOrder() {
    Matcher m = Ll1Pattern.compile("(" + A + "(" + B + ")(" + C + "))").matcher(A + B + C);
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is(A + B + C)); // outermost group opened first
    assertThat(m.group(2), is(B));
    assertThat(m.group(3), is(C));
  }

  @Test
  public void deeplyNestedCapturingGroups_numberedInOpeningOrder() {
    Matcher m = Ll1Pattern.compile("((" + A + ")(" + B + "(" + C + ")))").matcher(A + B + C);
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is(A + B + C));
    assertThat(m.group(2), is(A));
    assertThat(m.group(3), is(B + C));
    assertThat(m.group(4), is(C));
  }
}

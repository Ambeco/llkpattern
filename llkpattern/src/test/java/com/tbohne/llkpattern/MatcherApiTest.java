package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for Ll1Pattern/Matcher's public API: matches(), lookingAt(), find(), regions, groups. */
@RunWith(JUnit4.class)
public class MatcherApiTest {

  // --- matches() vs lookingAt(): the same compiled graph, different "did we use it all" check ---

  @Test
  public void matches_requiresConsumingWholeInput() {
    assertThat(Ll1Pattern.compile("a").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("a").matcher("aa").matches(), is(false));
  }

  @Test
  public void lookingAt_onlyRequiresAMatchingPrefix() {
    assertThat(Ll1Pattern.compile("a").matcher("aa").lookingAt(), is(true));
    assertThat(Ll1Pattern.compile("a").matcher("ba").lookingAt(), is(false));
  }

  // --- find(): unanchored search ---

  @Test
  public void find_locatesMatchNotAtTheStart() {
    Matcher m = Ll1Pattern.compile("b").matcher("aab");
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(2));
    assertThat(m.end(), is(3));
    assertThat(m.group(), is("b"));
  }

  @Test
  public void find_noMatchAnywhere_returnsFalse() {
    assertThat(Ll1Pattern.compile("z").matcher("aab").find(), is(false));
  }

  @Test
  public void find_repeatedCalls_advancePastPreviousMatch() {
    Matcher m = Ll1Pattern.compile("a").matcher("aaa");
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(0));
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(1));
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(2));
    assertThat(m.find(), is(false));
  }

  @Test
  public void find_withExplicitStart_searchesFromThatIndex() {
    Matcher m = Ll1Pattern.compile("a").matcher("aaa");
    assertThat(m.find(1), is(true));
    assertThat(m.start(), is(1));
  }

  @Test
  public void find_onEmptyMatchingPattern_stillMakesForwardProgress() {
    // "a*" can match zero characters anywhere -- find() must still advance past a zero-width
    // match rather than looping on the same position forever.
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
    Matcher m = Ll1Pattern.compile("a").matcher("xaxax");
    m.region(3, 4);
    assertThat(m.matches(), is(true));
    assertThat(m.start(), is(3));
  }

  @Test
  public void region_findRespectsRegionEnd() {
    Matcher m = Ll1Pattern.compile("a").matcher("axa");
    m.region(1, 2); // only the middle "x" is in-region
    assertThat(m.find(), is(false));
  }

  // --- reset() ---

  @Test
  public void reset_clearsPriorMatchAndRegion() {
    Matcher m = Ll1Pattern.compile("a").matcher("aax");
    m.region(1, 2);
    m.reset();
    assertThat(m.regionStart(), is(0));
    assertThat(m.regionEnd(), is(3));
  }

  @Test
  public void resetWithNewInput_matchesAgainstTheNewString() {
    Matcher m = Ll1Pattern.compile("a").matcher("a");
    assertThat(m.matches(), is(true));
    m.reset("b");
    assertThat(m.matches(), is(false));
  }

  // --- Groups ---

  @Test
  public void groupCount_countsOnlyRealCapturingGroups() {
    assertThat(Ll1Pattern.compile("(a)(?:b)(c)").matcher("abc").groupCount(), is(2));
  }

  @Test
  public void group_beforeAnyMatchAttempt_throwsIllegalStateException() {
    Matcher m = Ll1Pattern.compile("a").matcher("a");
    assertThrows(IllegalStateException.class, m::group);
    assertThrows(IllegalStateException.class, m::start);
    assertThrows(IllegalStateException.class, m::end);
  }

  @Test
  public void group_withInvalidNumber_throwsIndexOutOfBoundsException() {
    Matcher m = Ll1Pattern.compile("(a)").matcher("a");
    m.matches();
    assertThrows(IndexOutOfBoundsException.class, () -> m.group(2));
  }

  @Test
  public void group_withUnknownName_throwsIllegalArgumentException() {
    Matcher m = Ll1Pattern.compile("(?<x>a)").matcher("a");
    m.matches();
    assertThrows(IllegalArgumentException.class, () -> m.group("y"));
  }

  @Test
  public void unmatchedOptionalGroup_returnsNullNotEmptyString() {
    Matcher m = Ll1Pattern.compile("(a)?b").matcher("b");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is((String) null));
    assertThat(m.start(1), is(-1));
    assertThat(m.end(1), is(-1));
  }

  // --- asPredicate() / static matches() ---

  @Test
  public void asPredicate_delegatesToMatches() {
    assertThat(Ll1Pattern.compile("a+").asPredicate().test("aaa"), is(true));
    assertThat(Ll1Pattern.compile("a+").asPredicate().test("aaab"), is(false));
  }

  @Test
  public void staticMatches_delegatesToMatcherMatches() {
    assertThat(Ll1Pattern.matches("a+", "aaa"), is(true));
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
    Matcher m = Ll1Pattern.compile("a{2,3}").matcher("aaaa");
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("aaa"));
  }

  @Test
  public void find_optionalQuantifier_doesNotLeakCaptureStateAcrossScanPositions() {
    Matcher m = Ll1Pattern.compile("a?b").matcher("aaaab");
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("ab"));
  }

  @Test
  public void quantifiedCapturingGroup_doesNotSpuriouslyMatchAfterFailedEarlierPosition() {
    // Before the fix, a failed attempt at an earlier find() scan position could leave a nonzero
    // loop counter that let "(ab)+" spuriously satisfy its own min==1 check with an empty match,
    // even though "ab" never actually occurs in the input.
    Matcher m = Ll1Pattern.compile("(ab)+").matcher("aiiiiw");
    assertThat(m.find(), is(false));
  }

  // --- Capturing group numbering must follow opening-paren order, not closing-paren order -- see
  // remaining_work.md's dated bug entry: a recursive-descent parser's nested groups always finish
  // parsing (and, before the fix, always finished claiming their index) before the enclosing
  // group's own call returns, which backwards-numbered every pattern with nested capturing groups.

  @Test
  public void nestedCapturingGroups_numberedInOpeningOrder() {
    Matcher m = Ll1Pattern.compile("(a(b)(c))").matcher("abc");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("abc")); // outermost group opened first
    assertThat(m.group(2), is("b"));
    assertThat(m.group(3), is("c"));
  }

  @Test
  public void deeplyNestedCapturingGroups_numberedInOpeningOrder() {
    Matcher m = Ll1Pattern.compile("((a)(b(c)))").matcher("abc");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("abc"));
    assertThat(m.group(2), is("a"));
    assertThat(m.group(3), is("bc"));
    assertThat(m.group(4), is("c"));
  }
}

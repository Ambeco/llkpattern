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
}

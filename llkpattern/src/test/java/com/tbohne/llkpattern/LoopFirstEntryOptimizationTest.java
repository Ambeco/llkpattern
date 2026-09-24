package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Regression coverage for {@code MatcherConstruct.LoopFirstEntryMatcherConstruct} (see its own doc
 * and {@code QuantifiableConstruct.buildLoopMatcher}): a single-alternative, non-capturing,
 * {@code min >= 1} loop's own externally-visible entry point skips its body head's own (provably
 * redundant, on that path only) {@code entrySet} check. Covers every eligibility axis (ungated
 * top-level loop, outer-gated union branch, {@code min > 1}, and the excluded
 * capturing/multi-alternative/{@code min == 0} cases that must keep their normal behavior)
 * end-to-end, since the optimization changes matcher-graph shape but no PatternConstruct-level AST
 * shape, so it isn't independently exercised by any single existing test's own focus.
 */
@RunWith(JUnit4.class)
public class LoopFirstEntryOptimizationTest {
  // --- Eligible case: single-alternative, non-capturing, min >= 1, ungated (top-level) ---

  @Test
  public void ungatedTopLevel_matchesAndMismatches() {
    Ll1Pattern p = Ll1Pattern.compile("a+");
    assertThat(p.matcher("aaa").matches(), is(true));
    assertThat(p.matcher("").matches(), is(false));
    assertThat(p.matcher("bbb").matches(), is(false));
    assertThat(p.matcher("bbb").lookingAt(), is(false));
  }

  @Test
  public void ungatedTopLevel_findScansPastMismatch() {
    Matcher m = Ll1Pattern.compile("a+").matcher("xxaaayy");
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(2));
    assertThat(m.end(), is(5));
    assertThat(m.group(), is("aaa"));
  }

  @Test
  public void ungatedTopLevel_minGreaterThanOne() {
    Ll1Pattern p = Ll1Pattern.compile("a{2,4}");
    assertThat(p.matcher("aaa").matches(), is(true));
    assertThat(p.matcher("a").matches(), is(false));
  }

  // --- Eligible case, but reached through an outer chain candidate's own gate (union branch) ---

  @Test
  public void outerGatedUnionBranch() {
    Ll1Pattern p = Ll1Pattern.compile("(a+|b)c");
    assertThat(p.matcher("aaac").matches(), is(true));
    assertThat(p.matcher("bc").matches(), is(true));
    assertThat(p.matcher("c").matches(), is(false));
  }

  // --- Excluded: capturing (must still work correctly, just not via the new node) ---

  @Test
  public void excluded_capturingSingleAlternative_lastIterationWins() {
    Matcher m = Ll1Pattern.compile("(a)+").matcher("aaa");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("a"));
  }

  // --- Excluded: multi-alternative body (still routes to the right branch) ---

  @Test
  public void excluded_multiAlternativeBody() {
    Ll1Pattern p = Ll1Pattern.compile("(a|b)+");
    assertThat(p.matcher("abba").matches(), is(true));
    assertThat(p.matcher("c").matches(), is(false));
  }

  // --- Excluded: min == 0 (this loop's own entry set folds in `next`'s too -- not redundant) ---

  @Test
  public void excluded_minZero_stillSkipsCorrectlyOnMismatch() {
    Ll1Pattern p = Ll1Pattern.compile("a*b");
    assertThat(p.matcher("aaab").matches(), is(true));
    assertThat(p.matcher("b").matches(), is(true));
    assertThat(p.matcher("c").matches(), is(false));
  }

  // --- hitEnd/requireEnd sanity: a genuine mismatch not at end of input shouldn't report hitEnd ---

  @Test
  public void mismatchNotAtEndOfInput_doesNotSetHitEnd() {
    Matcher m = Ll1Pattern.compile("a+").matcher("ab");
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(0));
    assertThat(m.end(), is(1));
    assertThat(m.hitEnd(), is(false));
  }

  // --- This project's own mandatory hand-checks after any dispatch/capture change (CLAUDE.md) ---

  @Test
  public void dispatchHandCheck_optionalNestedGroupsAgainstEmptyString() {
    assertThat(Ll1Pattern.compile("((a?b)c)?").matcher("").matches(), is(true));
  }

  @Test
  public void captureHandCheck_loopedGroupKeepsLastIteration() {
    Matcher m = Ll1Pattern.compile("(a+b)+").matcher("ababab");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("ab"));
  }
}

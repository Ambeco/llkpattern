package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * \b (word boundary) and \B (non-word-boundary) -- see design.md's "Boundary matching" section.
 * Covers both the general runtime check and the compile-time optimization/rejection paths for the
 * common case where one or both sides of the boundary are a literal/character-class with a
 * statically known word-ness.
 */
@RunWith(JUnit4.class)
public class WordBoundaryTest {
  // --- General case: neither side statically known (both ends of the match are variable) ---

  @Test
  public void wordBoundary_matchesAtStartOfWord() {
    assertThat(Ll1Pattern.compile("\\b\\w+").matcher("foo").matches(), is(true));
    assertThat(Ll1Pattern.compile(".\\b.").matcher("a ").matches(), is(true));
    assertThat(Ll1Pattern.compile(".\\b.").matcher("ab").matches(), is(false));
  }

  @Test
  public void wordBoundary_matchesAtEndOfInput() {
    assertThat(Ll1Pattern.compile("\\w+\\b").matcher("foo").matches(), is(true));
  }

  @Test
  public void wordBoundary_matchesAtStartOfInput() {
    assertThat(Ll1Pattern.compile("\\b\\w+").matcher("foo").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\b.").matcher(" ").matches(), is(false));
  }

  @Test
  public void nonWordBoundary_isComplement() {
    assertThat(Ll1Pattern.compile(".\\B.").matcher("ab").matches(), is(true));
    assertThat(Ll1Pattern.compile(".\\B.").matcher("a ").matches(), is(false));
  }

  // --- Optimized case: peek side statically known (e.g. \b immediately before/after a fixed
  // literal or character class), prior side unknown ---

  @Test
  public void wordBoundary_peekKnownWord_requiresNonWordPrior() {
    assertThat(Ll1Pattern.compile(".\\bfoo").matcher(" foo").matches(), is(true));
    assertThat(Ll1Pattern.compile(".\\bfoo").matcher("afoo").matches(), is(false));
  }

  @Test
  public void wordBoundary_peekKnownNonWord_requiresWordPrior() {
    assertThat(Ll1Pattern.compile("\\w\\b ").matcher("a ").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\w\\b ").matcher("  ").matches(), is(false));
  }

  // --- Optimized case: prior side statically known (literal/character class right before \b),
  // peek side unknown ---

  @Test
  public void wordBoundary_priorKnownWord_requiresNonWordPeek() {
    assertThat(Ll1Pattern.compile("foo\\b.").matcher("foo ").matches(), is(true));
    assertThat(Ll1Pattern.compile("foo\\b.").matcher("fooa").matches(), is(false));
  }

  @Test
  public void wordBoundary_priorKnownNonWord_requiresWordPeek() {
    assertThat(Ll1Pattern.compile(" \\b\\w").matcher(" a").matches(), is(true));
    assertThat(Ll1Pattern.compile(" \\b\\w").matcher("  ").matches(), is(false));
  }

  // --- Fully statically known: compile-time no-op or rejection ---

  @Test
  public void wordBoundary_bothSidesOppositeWordness_noOpsAtCompileTime() {
    // 'a' is always a word char, ' ' is always not -- \b between them always holds, so this
    // compiles down to a plain literal match, not a runtime boundary check.
    assertThat(Ll1Pattern.compile("a\\b ").matcher("a ").matches(), is(true));
  }

  @Test
  public void wordBoundary_bothSidesSameWordness_rejectedAtCompileTime() {
    org.junit.Assert.assertThrows(
        PatternSyntaxException.class, () -> Ll1Pattern.compile("a\\bb"));
  }

  @Test
  public void nonWordBoundary_bothSidesSameWordness_noOpsAtCompileTime() {
    assertThat(Ll1Pattern.compile("a\\Bb").matcher("ab").matches(), is(true));
  }

  @Test
  public void nonWordBoundary_bothSidesOppositeWordness_rejectedAtCompileTime() {
    org.junit.Assert.assertThrows(
        PatternSyntaxException.class, () -> Ll1Pattern.compile("a\\B "));
  }
}

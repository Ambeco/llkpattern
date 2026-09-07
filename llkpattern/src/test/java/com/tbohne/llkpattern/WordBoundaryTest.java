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

  // --- Edge-of-input: \b/\B at the very start or end of the pattern, where peek()/peekPrevious()
  // are one code point away from actually running off the end of the backing array. "." is used
  // as the neighboring construct specifically because it's neither statically-word nor
  // statically-non-word (see WordBoundaryConstruct.buildMatcher()'s Wordness.UNKNOWN case) -- that
  // forces the general runtime check, which is the only path that actually calls
  // Matcher#peekPrevious()/#peek() right at position 0 or regionEnd, instead of being folded away
  // at compile time. See Matcher#peek()/#peekPrevious() for the corresponding bounds checks
  // (pos < regionEnd / pos <= regionStart) this is meant to exercise.

  @Test
  public void wordBoundary_atStartOfPattern_peekPreviousDoesNotReadBeforeStartOfInput() {
    // \b is the pattern's very first construct -- peekPrevious() runs at pos == 0 == regionStart.
    assertThat(Ll1Pattern.compile("\\b.").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\b.").matcher(" ").matches(), is(false));
  }

  @Test
  public void wordBoundary_atEndOfPattern_peekDoesNotReadPastEndOfInput() {
    // \b is the pattern's very last construct -- peek() runs at pos == input.length() == regionEnd.
    assertThat(Ll1Pattern.compile(".\\b").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile(".\\b").matcher(" ").matches(), is(false));
  }

  @Test
  public void nonWordBoundary_atStartOfPattern_peekPreviousDoesNotReadBeforeStartOfInput() {
    assertThat(Ll1Pattern.compile("\\B.").matcher("a").matches(), is(false));
    assertThat(Ll1Pattern.compile("\\B.").matcher(" ").matches(), is(true));
  }

  @Test
  public void nonWordBoundary_atEndOfPattern_peekDoesNotReadPastEndOfInput() {
    assertThat(Ll1Pattern.compile(".\\B").matcher("a").matches(), is(false));
    assertThat(Ll1Pattern.compile(".\\B").matcher(" ").matches(), is(true));
  }

  @Test
  public void wordBoundary_onEmptyInput_bothPeekAndPeekPreviousAreAtTheSameEdge() {
    // pos == regionStart == regionEnd == 0: both peek() and peekPrevious() hit their bounds check
    // at once. Nothing to match either side of, so this can never satisfy \b -- the point here is
    // just that compiling/matching it doesn't throw.
    assertThat(Ll1Pattern.compile("\\b.?").matcher("").matches(), is(false));
  }

  // --- Direct Matcher#peek()/#peekPrevious() bounds checks, independent of \b/\B's own logic ---

  @Test
  public void peekPrevious_atStartOfInput_returnsSentinelInsteadOfReadingBeforeIndexZero() {
    Matcher m = Ll1Pattern.compile(".*").matcher("a");
    assertThat(m.pos, is(0));
    assertThat(m.peekPrevious(), is(-1));
  }

  @Test
  public void peekPrevious_onEmptyInput_returnsSentinel() {
    Matcher m = Ll1Pattern.compile(".*").matcher("");
    assertThat(m.peekPrevious(), is(-1));
  }

  @Test
  public void peek_atEndOfInput_returnsSentinelInsteadOfReadingPastTheEnd() {
    Matcher m = Ll1Pattern.compile(".*").matcher("a");
    m.pos = 1; // == input.length()
    assertThat(m.peek(), is(-1));
  }

  @Test
  public void peekPrevious_atEndOfInput_readsTheLastCharacter() {
    Matcher m = Ll1Pattern.compile(".*").matcher("a");
    m.pos = 1; // == input.length()
    assertThat(m.peekPrevious(), is((int) 'a'));
  }

  @Test
  public void peekPrevious_respectsRegionStart_notJustIndexZero() {
    // useTransparentBounds() is still a stub (see remaining_work.md), so region() is opaque:
    // peekPrevious() must treat regionStart as its own start-of-input, not fall through to real
    // index 0 and read a character outside the region.
    Matcher m = Ll1Pattern.compile(".*").matcher("ab");
    m.region(1, 2);
    assertThat(m.pos, is(1));
    assertThat(m.peekPrevious(), is(-1));
  }

  @Test
  public void peekPrevious_doesNotSplitASurrogatePair() {
    String supplementary = "😀"; // U+1F600, a single code point, 2 UTF-16 chars
    Matcher m = Ll1Pattern.compile(".*").matcher(supplementary);
    m.pos = supplementary.length();
    assertThat(m.peekPrevious(), is(0x1F600));
  }
}

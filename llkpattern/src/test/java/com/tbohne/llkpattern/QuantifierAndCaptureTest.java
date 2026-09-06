package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * End-to-end match() tests for quantifier (loop) and capture-group compilation. These call
 * match() directly (bypassing Ll1Pattern/Matcher's not-yet-implemented public API, see
 * remaining_work.md) with a manually right-sized Matcher -- see {@link #match}.
 */
@RunWith(JUnit4.class)
public class QuantifierAndCaptureTest {

  private static boolean match(String pattern, String input) {
    return match(pattern, input, null);
  }

  private static boolean match(String pattern, String input, @Nullable Matcher[] out) {
    PatternConstruct parsed = new PatternParser(pattern, 0).parse();
    MatcherConstruct compiled = parsed.compile(new PatternConstruct.EndConstruct(parsed.endIndex));
    Matcher m = new Matcher(new Ll1Pattern(pattern, 0, compiled), input);
    // Sized generously rather than exactly -- these tests are about compilation correctness, not
    // about the (separately tracked, remaining_work.md) sizing of these arrays from a real parse.
    m.quantifiableCounts = new int[8];
    m.captureGroups = new Matcher.Group[8];
    if (out != null) {
      out[0] = m;
    }
    return compiled.match(m, m.peek());
  }

  // --- Star (*): zero or more ---

  @Test
  public void star_zeroOccurrences_matches() {
    assertThat(match("a*", ""), is(true));
  }

  @Test
  public void star_manyOccurrences_matches() {
    assertThat(match("a*", "aaa"), is(true));
  }

  @Test
  public void star_followedByLiteral_choosesCorrectExit() {
    assertThat(match("a*b", "aaab"), is(true));
    assertThat(match("a*b", "b"), is(true));
  }

  // --- Plus (+): one or more ---

  @Test
  public void plus_zeroOccurrences_fails() {
    assertThat(match("a+", ""), is(false));
  }

  @Test
  public void plus_oneOrMoreOccurrences_matches() {
    assertThat(match("a+", "a"), is(true));
    assertThat(match("a+", "aaa"), is(true));
  }

  // --- Optional (?): zero or one ---

  @Test
  public void optional_zeroOccurrences_matches() {
    assertThat(match("a?", ""), is(true));
  }

  @Test
  public void optional_oneOccurrence_matches() {
    assertThat(match("a?", "a"), is(true));
  }

  // --- Bounded ({n,m}) ---

  @Test
  public void bounded_belowMin_fails() {
    assertThat(match("a{2,3}", "a"), is(false));
  }

  @Test
  public void bounded_withinRange_matches() {
    assertThat(match("a{2,3}", "aa"), is(true));
    assertThat(match("a{2,3}", "aaa"), is(true));
  }

  @Test
  public void bounded_aboveMax_fails() {
    // Also guards against the {n,m} parser bug (fixed alongside this) where the second number
    // silently overwrote the first instead of setting max, making every bound effectively {m,m}.
    assertThat(match("a{2,3}", "aaaa"), is(false));
  }

  // --- Alternation inside a loop ---

  @Test
  public void loopOfAlternation_matchesEitherBranchRepeatedly() {
    // Non-capturing (?:...) -- a plain (a|b)* is *also* a capturing group by default, which hits
    // the deliberately-deferred "capturing and quantified at once" case tested separately below.
    assertThat(match("(?:a|b)*c", "abbac"), is(true));
    assertThat(match("(?:a|b)*c", "c"), is(true));
  }

  @Test
  public void loopOfAlternation_ambiguousBranches_stillRejectedAtCompileTime() {
    assertThrows(java.util.regex.PatternSyntaxException.class, () -> match("(?:ab|ac)*d", "d"));
  }

  // --- Capturing groups (non-quantified) ---

  @Test
  public void capturingGroup_recordsMatchedSubstring() {
    Matcher[] out = new Matcher[1];
    assertThat(match("(a)", "a", out), is(true));
    assertThat(out[0].captureGroups[0].result, is("a"));
  }

  @Test
  public void capturingGroup_recordsWhicheverAlternationBranchMatched() {
    Matcher[] out = new Matcher[1];
    assertThat(match("(a|b)c", "bc", out), is(true));
    assertThat(out[0].captureGroups[0].result, is("b"));
  }

  @Test
  public void capturingGroup_inMiddleOfSequence_recordsOnlyItsOwnSubstring() {
    Matcher[] out = new Matcher[1];
    assertThat(match("a(b)c", "abc", out), is(true));
    assertThat(out[0].captureGroups[0].result, is("b"));
  }

  @Test
  public void nonCapturingGroup_matchesWithoutRecordingAnything() {
    assertThat(match("(?:a)b", "ab"), is(true));
  }

  // --- Explicitly deferred: capturing AND quantified at once (e.g. "(a)*") ---

  @Test
  public void capturingAndQuantifiedGroup_recordsLastIterationOnly() {
    // Real regex semantics: (a)* captures whichever iteration matched last, not the first or a
    // concatenation of all of them.
    Matcher[] out = new Matcher[1];
    assertThat(match("(a)*", "aaa", out), is(true));
    assertThat(out[0].captureGroups[0].result, is("a"));
  }

  @Test
  public void capturingAndQuantifiedGroup_zeroIterations_leavesCaptureUnset() {
    Matcher[] out = new Matcher[1];
    assertThat(match("(a)*", "", out), is(true));
    assertThat(out[0].captureGroups[0], nullValue());
  }

  @Test
  public void capturingAndQuantifiedGroup_ofAlternation_recordsLastMatchedBranch() {
    Matcher[] out = new Matcher[1];
    assertThat(match("(a|b)*", "abba", out), is(true));
    assertThat(out[0].captureGroups[0].result, is("a"));
  }

  @Test
  public void capturingAndQuantifiedGroup_followedByLiteral_stillChoosesCorrectExit() {
    assertThat(match("(a)*b", "aaab"), is(true));
    assertThat(match("(a)*b", "b"), is(true));
  }
}

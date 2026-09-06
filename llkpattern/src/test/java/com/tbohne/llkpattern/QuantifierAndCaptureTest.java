package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** End-to-end matches()/group() tests for quantifier (loop) and capture-group compilation. */
@RunWith(JUnit4.class)
public class QuantifierAndCaptureTest {

  private static boolean matches(String pattern, String input) {
    return Ll1Pattern.compile(pattern).matcher(input).matches();
  }

  // --- Star (*): zero or more ---

  @Test
  public void star_zeroOccurrences_matches() {
    assertThat(matches("a*", ""), is(true));
  }

  @Test
  public void star_manyOccurrences_matches() {
    assertThat(matches("a*", "aaa"), is(true));
  }

  @Test
  public void star_followedByLiteral_choosesCorrectExit() {
    assertThat(matches("a*b", "aaab"), is(true));
    assertThat(matches("a*b", "b"), is(true));
  }

  // --- Plus (+): one or more ---

  @Test
  public void plus_zeroOccurrences_fails() {
    assertThat(matches("a+", ""), is(false));
  }

  @Test
  public void plus_oneOrMoreOccurrences_matches() {
    assertThat(matches("a+", "a"), is(true));
    assertThat(matches("a+", "aaa"), is(true));
  }

  // --- Optional (?): zero or one ---

  @Test
  public void optional_zeroOccurrences_matches() {
    assertThat(matches("a?", ""), is(true));
  }

  @Test
  public void optional_oneOccurrence_matches() {
    assertThat(matches("a?", "a"), is(true));
  }

  // --- Bounded ({n,m}) ---

  @Test
  public void bounded_belowMin_fails() {
    assertThat(matches("a{2,3}", "a"), is(false));
  }

  @Test
  public void bounded_withinRange_matches() {
    assertThat(matches("a{2,3}", "aa"), is(true));
    assertThat(matches("a{2,3}", "aaa"), is(true));
  }

  @Test
  public void bounded_aboveMax_fails() {
    // Also guards against the {n,m} parser bug (fixed alongside this) where the second number
    // silently overwrote the first instead of setting max, making every bound effectively {m,m}.
    assertThat(matches("a{2,3}", "aaaa"), is(false));
  }

  // --- Alternation inside a loop ---

  @Test
  public void loopOfAlternation_matchesEitherBranchRepeatedly() {
    // Non-capturing (?:...) -- a plain (a|b)* is *also* a capturing group by default, which hits
    // the "capturing and quantified at once" case tested separately below.
    assertThat(matches("(?:a|b)*c", "abbac"), is(true));
    assertThat(matches("(?:a|b)*c", "c"), is(true));
  }

  @Test
  public void loopOfAlternation_ambiguousBranches_stillRejectedAtCompileTime() {
    assertThrows(java.util.regex.PatternSyntaxException.class, () -> Ll1Pattern.compile("(?:ab|ac)*d"));
  }

  // --- Capturing groups (non-quantified) ---

  @Test
  public void capturingGroup_recordsMatchedSubstring() {
    Matcher m = Ll1Pattern.compile("(a)").matcher("a");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("a"));
  }

  @Test
  public void capturingGroup_recordsWhicheverAlternationBranchMatched() {
    Matcher m = Ll1Pattern.compile("(a|b)c").matcher("bc");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("b"));
  }

  @Test
  public void capturingGroup_inMiddleOfSequence_recordsOnlyItsOwnSubstring() {
    Matcher m = Ll1Pattern.compile("a(b)c").matcher("abc");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("b"));
  }

  @Test
  public void nonCapturingGroup_matchesWithoutRecordingAnything() {
    Matcher m = Ll1Pattern.compile("(?:a)b").matcher("ab");
    assertThat(m.matches(), is(true));
    assertThat(m.groupCount(), is(0));
  }

  @Test
  public void namedCapturingGroup_accessibleByName() {
    Matcher m = Ll1Pattern.compile("(?<letter>a)").matcher("a");
    assertThat(m.matches(), is(true));
    assertThat(m.group("letter"), is("a"));
    assertThat(m.start("letter"), is(0));
    assertThat(m.end("letter"), is(1));
  }

  // --- Capturing AND quantified at once (e.g. "(a)*") ---

  @Test
  public void capturingAndQuantifiedGroup_recordsLastIterationOnly() {
    // Real regex semantics: (a)* captures whichever iteration matched last, not the first or a
    // concatenation of all of them.
    Matcher m = Ll1Pattern.compile("(a)*").matcher("aaa");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("a"));
  }

  @Test
  public void capturingAndQuantifiedGroup_zeroIterations_leavesCaptureUnset() {
    Matcher m = Ll1Pattern.compile("(a)*").matcher("");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), nullValue());
  }

  @Test
  public void capturingAndQuantifiedGroup_ofAlternation_recordsLastMatchedBranch() {
    Matcher m = Ll1Pattern.compile("(a|b)*").matcher("abba");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("a"));
  }

  @Test
  public void capturingAndQuantifiedGroup_followedByLiteral_stillChoosesCorrectExit() {
    assertThat(matches("(a)*b", "aaab"), is(true));
    assertThat(matches("(a)*b", "b"), is(true));
  }
}

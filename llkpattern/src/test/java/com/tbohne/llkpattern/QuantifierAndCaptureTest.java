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

  // --- A quantified/loop construct immediately followed by a COMPOSITE (non-leaf) construct --
  // a capturing group, a non-capturing group, or a multi-literal sequence, rather than a bare
  // literal/character class -- see remaining_work.md's dated bug entry: the loop's own "keep
  // looping vs. exit" dispatch used to misroute the exit path back into the loop body whenever
  // `next` was one of these, since only leaf constructs re-keyed their entryMap's values onto
  // themselves for the loop's exit-identity check to see.

  @Test
  public void quantifiedGroup_followedByCapturingGroup_zeroIterations_choosesCorrectExit() {
    Matcher m = Ll1Pattern.compile("(a)(b)*(z)").matcher("az");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("a"));
    assertThat(m.group(2), nullValue());
    assertThat(m.group(3), is("z"));
  }

  @Test
  public void quantifiedGroup_followedByCapturingGroup_someIterations_choosesCorrectExit() {
    Matcher m = Ll1Pattern.compile("(a)(b)*(z)").matcher("abbbz");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("a"));
    assertThat(m.group(2), is("b"));
    assertThat(m.group(3), is("z"));
  }

  @Test
  public void quantifiedGroup_followedByNonCapturingGroup_choosesCorrectExit() {
    assertThat(matches("(b)*(?:zz)", "zz"), is(true));
    assertThat(matches("(b)*(?:zz)", "bbbzz"), is(true));
  }

  @Test
  public void quantifiedGroup_followedByMultiLiteralSequence_choosesCorrectExit() {
    assertThat(matches("(b)*cd", "cd"), is(true));
    assertThat(matches("(b)*cd", "bbcd"), is(true));
  }

  // --- Dot (.) quantified -- see remaining_work.md's dated bug entry: PatternParser used to check
  // for a quantifier suffix on "." before advancing past the "." itself, so `peek` was still '.'
  // at that point and the check always failed -- "." was silently never actually quantifiable;
  // ".*z" parsed as an unquantified "." followed by the literal text "*z". Since "." matches
  // essentially everything (see NamedCharClass.RegexCharacterClass.DOT: all but '\n'), a
  // quantified "." followed by an ordinary literal is itself always ambiguous under this engine's
  // LL(1) restriction (exactly like "[a-z]+z" -- see the last test below) -- these tests instead
  // use "." as the pattern's own tail (matches() over the whole string, nothing after the loop to
  // conflict with) to isolate "does the quantifier actually apply to the dot" from that unrelated
  // ambiguity.

  @Test
  public void dotStar_matchesZeroOrMoreOfAnyCharacter() {
    assertThat(matches("a.*", "a"), is(true));
    assertThat(matches("a.*", "abbb"), is(true));
    // Would have (wrongly) required the literal text "*" (as a 1-character match) before this fix.
    assertThat(matches("a.*", "a*"), is(true));
    assertThat(matches("a*", "a*"), is(false)); // sanity check: "a*" alone does NOT match "a*"
  }

  @Test
  public void dotPlus_requiresAtLeastOneCharacter() {
    assertThat(matches("a.+", "a"), is(false));
    assertThat(matches("a.+", "ab"), is(true));
  }

  @Test
  public void dotQuestion_matchesZeroOrOneCharacter() {
    assertThat(matches("a.?", "a"), is(true));
    assertThat(matches("a.?", "ab"), is(true));
    assertThat(matches("a.?", "abb"), is(false));
  }

  @Test
  public void dotBraceQuantifier_honorsExplicitBounds() {
    assertThat(matches("a.{2}", "abb"), is(true));
    assertThat(matches("a.{2}", "ab"), is(false));
  }

  @Test
  public void dotQuantifier_ambiguousWithFollowingLiteral_rejectedAtCompileTime() {
    // "." (which matches 'z' too) competing with a following literal 'z' for the same next
    // character is exactly as ambiguous as "[a-z]+z" -- both must be compile-time errors, not a
    // silently-wrong match.
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile(".*z"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile(".+z"));
  }
}

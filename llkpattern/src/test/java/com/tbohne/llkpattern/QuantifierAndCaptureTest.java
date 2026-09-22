package com.tbohne.llkpattern;

import static com.tbohne.llkpattern.SupplementaryChars.A;
import static com.tbohne.llkpattern.SupplementaryChars.B;
import static com.tbohne.llkpattern.SupplementaryChars.C;
import static com.tbohne.llkpattern.SupplementaryChars.D;
import static com.tbohne.llkpattern.SupplementaryChars.Z;
import static com.tbohne.llkpattern.SupplementaryChars.repeat;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * End-to-end matches()/group() tests for quantifier (loop) and capture-group compilation.
 *
 * <p>Uses SupplementaryChars' A-Z (supplementary/astral code points) in place of the plain ASCII
 * letters this file originally used -- see SupplementaryPatternTextTest for why exercising the
 * parser's own lookahead across a multi-code-unit character matters here specifically (quantifier
 * suffixes, capture-group boundaries, loop entry/exit dispatch).
 */
@RunWith(JUnit4.class)
public class QuantifierAndCaptureTest {

  private static boolean matches(String pattern, String input) {
    return Ll1Pattern.compile(pattern).matcher(input).matches();
  }

  // --- Star (*): zero or more ---

  @Test
  public void star_zeroOccurrences_matches() {
    assertThat(matches(A + "*", ""), is(true));
  }

  @Test
  public void star_manyOccurrences_matches() {
    assertThat(matches(A + "*", repeat(A, 3)), is(true));
  }

  @Test
  public void star_followedByLiteral_choosesCorrectExit() {
    assertThat(matches(A + "*" + B, repeat(A, 3) + B), is(true));
    assertThat(matches(A + "*" + B, B), is(true));
  }

  // --- Plus (+): one or more ---

  @Test
  public void plus_zeroOccurrences_fails() {
    assertThat(matches(A + "+", ""), is(false));
  }

  @Test
  public void plus_oneOrMoreOccurrences_matches() {
    assertThat(matches(A + "+", A), is(true));
    assertThat(matches(A + "+", repeat(A, 3)), is(true));
  }

  // --- Optional (?): zero or one ---

  @Test
  public void optional_zeroOccurrences_matches() {
    assertThat(matches(A + "?", ""), is(true));
  }

  @Test
  public void optional_oneOccurrence_matches() {
    assertThat(matches(A + "?", A), is(true));
  }

  // --- Bounded ({n,m}) ---

  @Test
  public void bounded_belowMin_fails() {
    assertThat(matches(A + "{2,3}", A), is(false));
  }

  @Test
  public void bounded_withinRange_matches() {
    assertThat(matches(A + "{2,3}", repeat(A, 2)), is(true));
    assertThat(matches(A + "{2,3}", repeat(A, 3)), is(true));
  }

  @Test
  public void bounded_aboveMax_fails() {
    // Also guards against the {n,m} parser bug (fixed alongside this) where the second number
    // silently overwrote the first instead of setting max, making every bound effectively {m,m}.
    assertThat(matches(A + "{2,3}", repeat(A, 4)), is(false));
  }

  // --- Alternation inside a loop ---

  @Test
  public void loopOfAlternation_matchesEitherBranchRepeatedly() {
    // Non-capturing (?:...) -- a plain (a|b)* is *also* a capturing group by default, which hits
    // the "capturing and quantified at once" case tested separately below.
    assertThat(matches("(?:" + A + "|" + B + ")*" + C, A + B + B + A + C), is(true));
    assertThat(matches("(?:" + A + "|" + B + ")*" + C, C), is(true));
  }

  @Test
  public void loopOfAlternation_ambiguousBranches_stillRejectedAtCompileTime() {
    assertThrows(
        java.util.regex.PatternSyntaxException.class,
        () -> Ll1Pattern.compile("(?:" + A + B + "|" + A + C + ")*" + D));
  }

  // --- Capturing groups (non-quantified) ---

  @Test
  public void capturingGroup_recordsMatchedSubstring() {
    Matcher m = Ll1Pattern.compile("(" + A + ")").matcher(A);
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is(A));
  }

  @Test
  public void capturingGroup_recordsWhicheverAlternationBranchMatched() {
    Matcher m = Ll1Pattern.compile("(" + A + "|" + B + ")" + C).matcher(B + C);
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is(B));
  }

  @Test
  public void capturingGroup_inMiddleOfSequence_recordsOnlyItsOwnSubstring() {
    Matcher m = Ll1Pattern.compile(A + "(" + B + ")" + C).matcher(A + B + C);
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is(B));
  }

  @Test
  public void nonCapturingGroup_matchesWithoutRecordingAnything() {
    Matcher m = Ll1Pattern.compile("(?:" + A + ")" + B).matcher(A + B);
    assertThat(m.matches(), is(true));
    assertThat(m.groupCount(), is(0));
  }

  @Test
  public void namedCapturingGroup_accessibleByName() {
    Matcher m = Ll1Pattern.compile("(?<letter>" + A + ")").matcher(A);
    assertThat(m.matches(), is(true));
    assertThat(m.group("letter"), is(A));
    assertThat(m.start("letter"), is(0));
    assertThat(m.end("letter"), is(A.length()));
  }

  // --- Capturing AND quantified at once (e.g. "(a)*") ---

  @Test
  public void capturingAndQuantifiedGroup_recordsLastIterationOnly() {
    // Real regex semantics: (a)* captures whichever iteration matched last, not the first or a
    // concatenation of all of them.
    Matcher m = Ll1Pattern.compile("(" + A + ")*").matcher(repeat(A, 3));
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is(A));
  }

  @Test
  public void capturingAndQuantifiedGroup_zeroIterations_leavesCaptureUnset() {
    Matcher m = Ll1Pattern.compile("(" + A + ")*").matcher("");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), nullValue());
  }

  @Test
  public void capturingAndQuantifiedGroup_ofAlternation_recordsLastMatchedBranch() {
    Matcher m = Ll1Pattern.compile("(" + A + "|" + B + ")*").matcher(A + B + B + A);
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is(A));
  }

  @Test
  public void capturingAndQuantifiedGroup_followedByLiteral_stillChoosesCorrectExit() {
    assertThat(matches("(" + A + ")*" + B, repeat(A, 3) + B), is(true));
    assertThat(matches("(" + A + ")*" + B, B), is(true));
  }

  // --- A quantified/loop construct immediately followed by a COMPOSITE (non-leaf) construct --
  // a capturing group, a non-capturing group, or a multi-literal sequence, rather than a bare
  // literal/character class -- see remaining_work.md's dated bug entry: the loop's own "keep
  // looping vs. exit" dispatch used to misroute the exit path back into the loop body whenever
  // `next` was one of these, since only leaf constructs re-keyed their entryMap's values onto
  // themselves for the loop's exit-identity check to see.

  @Test
  public void quantifiedGroup_followedByCapturingGroup_zeroIterations_choosesCorrectExit() {
    Matcher m = Ll1Pattern.compile("(" + A + ")(" + B + ")*(" + Z + ")").matcher(A + Z);
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is(A));
    assertThat(m.group(2), nullValue());
    assertThat(m.group(3), is(Z));
  }

  @Test
  public void quantifiedGroup_followedByCapturingGroup_someIterations_choosesCorrectExit() {
    Matcher m = Ll1Pattern.compile("(" + A + ")(" + B + ")*(" + Z + ")").matcher(A + B + B + B + Z);
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is(A));
    assertThat(m.group(2), is(B));
    assertThat(m.group(3), is(Z));
  }

  @Test
  public void quantifiedGroup_followedByNonCapturingGroup_choosesCorrectExit() {
    assertThat(matches("(" + B + ")*(?:" + Z + Z + ")", Z + Z), is(true));
    assertThat(matches("(" + B + ")*(?:" + Z + Z + ")", repeat(B, 3) + Z + Z), is(true));
  }

  @Test
  public void quantifiedGroup_followedByMultiLiteralSequence_choosesCorrectExit() {
    assertThat(matches("(" + B + ")*" + C + D, C + D), is(true));
    assertThat(matches("(" + B + ")*" + C + D, B + B + C + D), is(true));
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
    assertThat(matches(A + ".*", A), is(true));
    assertThat(matches(A + ".*", A + "bbb"), is(true));
    // Would have (wrongly) required the literal text "*" (as a 1-character match) before this fix.
    assertThat(matches(A + ".*", A + "*"), is(true));
    assertThat(matches(A + "*", A + "*"), is(false)); // sanity check: "a*" alone does NOT match "a*"
  }

  @Test
  public void dotPlus_requiresAtLeastOneCharacter() {
    assertThat(matches(A + ".+", A), is(false));
    assertThat(matches(A + ".+", A + B), is(true));
  }

  @Test
  public void dotQuestion_matchesZeroOrOneCharacter() {
    assertThat(matches(A + ".?", A), is(true));
    assertThat(matches(A + ".?", A + B), is(true));
    assertThat(matches(A + ".?", A + B + B), is(false));
  }

  @Test
  public void dotBraceQuantifier_honorsExplicitBounds() {
    assertThat(matches(A + ".{2}", A + "bb"), is(true));
    assertThat(matches(A + ".{2}", A + "b"), is(false));
  }

  @Test
  public void dotQuantifier_ambiguousWithFollowingLiteral_rejectedAtCompileTime() {
    // "." (which matches Z too) competing with a following literal Z for the same next character
    // is exactly as ambiguous as "[a-z]+z" -- both must be compile-time errors, not a silently-
    // wrong match.
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile(".*" + Z));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile(".+" + Z));
  }

  // --- Three specific ambiguity shapes the project owner asked to confirm are covered: a plain
  // prefix union, an optional single-character literal against what follows it, and an optional
  // character class against what follows it -- each exercises entry-point overlap detection at a
  // different level (a union's own branch merge; a quantifier's min==0 "skip me, fall through to
  // next" merge over a single code point; the same merge but over a real multi-entry CodePointMap).

  @Test
  public void union_branchIsPrefixOfAnotherBranch_rejectedAtCompileTime() {
    // "a" and "ab" both start with 'a' -- same overlap as "ab|ac" (see
    // PatternParserTest#compile_ambiguousAlternation_throwsPatternSyntaxException), but here one
    // branch is a strict prefix of the other rather than just sharing a first character.
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile(A + "|" + A + B));
  }

  @Test
  public void optionalLiteral_ambiguousWithFollowingSameLiteral_rejectedAtCompileTime() {
    // "a?" can match zero characters, in which case what follows must determine the next branch on
    // its own -- but "a?"'s own entry set (just 'a', a single-codepoint LiteralString) and the
    // following "a"'s entry set both claim 'a', so skipping "a?" is indistinguishable from matching
    // it. This is QuantifiableConstruct.buildLoopEntryMap's min==0 body-vs-next merge, not a union's
    // branch-vs-branch merge (see the two tests above/below for those).
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile(A + "?" + A));
  }

  @Test
  public void optionalCharacterClass_ambiguousWithFollowingMemberLiteral_rejectedAtCompileTime() {
    // Same min==0 body-vs-next merge as "a?a" above, but "[ab]" is a real multi-entry CodePointMap
    // (not a single code point or an else-value "everything" map like "."/".*z" above) -- this is
    // the one that actually needs a genuine CodePointMap range intersection, not just a single
    // code point or else-value comparison, to detect that 'a' is claimed by both sides.
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("[" + A + B + "]?" + A));
  }

  // --- Nested quantified/loop constructs -- see remaining_work.md's former "Core implementation"
  // entry and design.md's "Entry-point computation vs. matcher compilation" section for the bug
  // this used to hit: a loop whose body itself contains another loop, where the inner loop's
  // "exit toward the outer loop" branch had no characters mapped to it at all. ---

  @Test
  public void loopContainingAnotherLoop_matchesSingleIteration() {
    // The minimal repro from remaining_work.md: a single iteration of the outer "+" ("a" with the
    // optional "b" absent) should trivially succeed.
    assertThat(matches("(" + A + "(" + B + ")?)+", A), is(true));
  }

  @Test
  public void loopContainingAnotherLoop_matchesMultipleIterations() {
    assertThat(matches("(" + A + "(" + B + ")?)+", A + A + B + A + B), is(true));
    assertThat(matches("(" + A + "(" + B + ")?)+", repeat(A, 3)), is(true));
  }

  @Test
  public void loopContainingAnotherLoop_nonCapturing_stillMatches() {
    assertThat(matches("(?:" + A + B + "?)+", A), is(true));
    assertThat(matches("(?:" + A + B + "?)+", A + B + A + B + A), is(true));
  }

  @Test
  public void nestedQuantifiedLoop_wholeBodyNullable_rejectedAtCompileTime() {
    // "(a?)+": the outer loop's only body part can match zero characters, so its own entry point
    // would require itself to already be known -- a genuine cycle, and also an infinite-loop
    // hazard in its own right (an iteration that consumes nothing). Must be a compile-time error,
    // not a stack overflow or a silently-wrong dispatch.
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(" + A + "?)+"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?:" + A + "?)+"));
  }

  // --- A quantified/optional construct whose sole body is itself a capturing group -- see
  // remaining_work.md's former "Crash: a quantified group whose sole body is a capturing group"
  // entry: the nested group's own CaptureEndMarker used to be built (during entry-point
  // computation) against the outer loop construct itself, whose `.matcher` isn't set until the
  // whole loop finishes compiling -- throwing a NullPointerException at match time the first time
  // that nested marker's `buildMatcher()` ran. Fixed by giving the loop body a stable
  // (QuantifiableConstruct.loopBodyTarget) marker shared between entry-point computation and
  // matcher compilation. ---

  @Test
  public void quantifiedGroup_soleBodyIsCapturingGroup_matches() {
    assertThat(matches("((" + A + "))*", repeat(A, 2)), is(true));
    assertThat(matches("(?:(" + A + "))*", repeat(A, 2)), is(true));
    assertThat(matches("((" + A + ")|" + B + ")*", A + B), is(true));
    assertThat(matches("((" + A + "))?", A), is(true));
    assertThat(matches("((" + A + ")){0,2}", repeat(A, 2)), is(true));
  }

  @Test
  public void quantifiedGroup_soleBodyIsCapturingGroup_capturesLastIteration() {
    Matcher m = Ll1Pattern.compile("((" + A + "))*").matcher(repeat(A, 3));
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is(A));
  }
}

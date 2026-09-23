package com.tbohne.llkpattern;

import static com.tbohne.llkpattern.SupplementaryChars.A;
import static com.tbohne.llkpattern.SupplementaryChars.B;
import static com.tbohne.llkpattern.SupplementaryChars.C;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * \A, \z, \Z (always the true start/end of input, MULTILINE has no effect), and ^/$ (equivalent
 * to \A/\Z by default; match at line boundaries under MULTILINE) -- see design.md's "Boundary
 * matching" section. \b/\B are covered separately in WordBoundaryTest; \G is covered separately
 * in PreviousMatchEndTest (it isn't a position-based boundary at all, see that class's doc).
 *
 * <p>{@code ABC} (a 3-code-point supplementary literal, replacing the plain ASCII "abc" this file
 * originally used) is 6 {@code char} units long, not 3 -- every {@code start()}/{@code end()}
 * offset assertion below accounts for that, since those offsets are always in {@code char} units
 * (matching {@code java.util.regex}), never code points. {@code lineBegin_multiline_
 * doesNotMatchMidTerminator} is left as plain ASCII 'a'/'b' -- it's testing "\r\n" mid-terminator
 * detection specifically, not the literal marker's own width.
 */
@RunWith(JUnit4.class)
public class LineAndInputBoundaryTest {
  private static final String ABC = A + B + C;

  // --- \A: always the true start of input ---

  @Test
  public void inputBegin_matchesOnlyAtStart() {
    assertThat(Ll1Pattern.compile("\\A" + ABC).matcher(ABC).matches(), is(true));
    assertThat(Ll1Pattern.compile(".\\A" + ABC).matcher("x" + ABC).matches(), is(false));
  }

  @Test
  public void inputBegin_multilineHasNoEffect() {
    Ll1Pattern p = Ll1Pattern.compile("\\A" + ABC, Ll1Pattern.MULTILINE);
    Matcher m = p.matcher("x\n" + ABC);
    assertThat(m.find(), is(false));
  }

  // --- \z: always the true end of input ---

  @Test
  public void inputEnd_matchesOnlyAtEnd() {
    assertThat(Ll1Pattern.compile(ABC + "\\z").matcher(ABC).matches(), is(true));
    assertThat(Ll1Pattern.compile(ABC + "\\z.").matcher(ABC + "x").matches(), is(false));
  }

  @Test
  public void inputEnd_doesNotMatchBeforeTrailingNewline() {
    assertThat(Ll1Pattern.compile(ABC + "\\z").matcher(ABC + "\n").matches(), is(false));
  }

  @Test
  public void inputEnd_multilineHasNoEffect() {
    Ll1Pattern p = Ll1Pattern.compile(ABC + "\\z", Ll1Pattern.MULTILINE);
    assertThat(p.matcher(ABC + "\ndef").find(), is(false));
  }

  // --- \Z: end of input, or immediately before a trailing line terminator. Note this is a
  // zero-width assertion that does NOT consume the terminator -- matches() (which requires the
  // *whole region* consumed, terminator included) correctly fails whenever there's a trailing
  // terminator to account for; only find()/lookingAt() (a prefix match) see the exemption. Real
  // java.util.regex behaves identically here -- verified directly against it while writing this.

  @Test
  public void inputEndExceptTerminator_matchesAtTrueEnd() {
    assertThat(Ll1Pattern.compile(ABC + "\\Z").matcher(ABC).matches(), is(true));
  }

  @Test
  public void inputEndExceptTerminator_findsBeforeTrailingNewlineButDoesNotConsumeIt() {
    Matcher m = Ll1Pattern.compile(ABC + "\\Z").matcher(ABC + "\n");
    assertThat(m.find(), is(true));
    assertThat(m.end(), is(ABC.length()));
    assertThat(Ll1Pattern.compile(ABC + "\\Z").matcher(ABC + "\n").matches(), is(false));
  }

  @Test
  public void inputEndExceptTerminator_findsBeforeTrailingCrLfButDoesNotConsumeIt() {
    Matcher m = Ll1Pattern.compile(ABC + "\\Z").matcher(ABC + "\r\n");
    assertThat(m.find(), is(true));
    assertThat(m.end(), is(ABC.length()));
  }

  @Test
  public void inputEndExceptTerminator_doesNotMatchBeforeAnInteriorNewline() {
    assertThat(Ll1Pattern.compile(ABC + "\\Z").matcher(ABC + "\ndef").find(), is(false));
  }

  @Test
  public void inputEndExceptTerminator_doesNotMatchBeforeTwoTrailingNewlines() {
    // Only the single, final terminator is exempted -- not an arbitrary run of them.
    assertThat(Ll1Pattern.compile(ABC + "\\Z").matcher(ABC + "\n\n").find(), is(false));
  }

  // --- ^/$ without MULTILINE: equivalent to \A/\Z ---

  @Test
  public void lineBegin_withoutMultiline_behavesLikeInputBegin() {
    assertThat(Ll1Pattern.compile("^" + ABC).matcher(ABC).matches(), is(true));
    assertThat(Ll1Pattern.compile("^" + ABC).matcher("x\n" + ABC).find(), is(false));
  }

  @Test
  public void lineEnd_withoutMultiline_behavesLikeInputEndExceptTerminator() {
    assertThat(Ll1Pattern.compile(ABC + "$").matcher(ABC).matches(), is(true));
    Matcher m = Ll1Pattern.compile(ABC + "$").matcher(ABC + "\n");
    assertThat(m.find(), is(true));
    assertThat(m.end(), is(ABC.length()));
    assertThat(Ll1Pattern.compile(ABC + "$").matcher(ABC + "\ndef").find(), is(false));
  }

  // --- ^/$ with MULTILINE: match at every line boundary ---

  @Test
  public void lineBegin_multiline_matchesAfterEveryNewline() {
    Ll1Pattern p = Ll1Pattern.compile("^" + ABC, Ll1Pattern.MULTILINE);
    Matcher m = p.matcher("x\n" + ABC + "\n" + ABC);
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(2)); // right after "x\n", unaffected by ABC's own length
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(2 + ABC.length() + 1)); // right after "x\n" + ABC + "\n"
  }

  @Test
  public void lineBegin_multiline_doesNotMatchMidTerminator() {
    // Right after the '\r' of a "\r\n" pair is mid-terminator, not a line begin. Plain ASCII --
    // this is testing "\r\n" detection specifically, not the literal marker's own width.
    Ll1Pattern p = Ll1Pattern.compile("^", Ll1Pattern.MULTILINE);
    Matcher m = p.matcher("a\r\nb");
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(0));
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(3)); // after the full "\r\n", not after just "\r"
  }

  @Test
  public void lineEnd_multiline_matchesBeforeEveryNewline() {
    Ll1Pattern p = Ll1Pattern.compile(ABC + "$", Ll1Pattern.MULTILINE);
    Matcher m = p.matcher(ABC + "\n" + ABC);
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(0));
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(ABC.length() + 1)); // right after ABC + "\n"
  }

  @Test
  public void lineEnd_multiline_alsoMatchesTrueEndOfInput() {
    assertThat(Ll1Pattern.compile(ABC + "$", Ll1Pattern.MULTILINE).matcher(ABC).matches(), is(true));
  }

  // --- UNIX_LINES: only "\n" counts as a line terminator ---

  @Test
  public void unixLines_crIsNotALineTerminator() {
    Ll1Pattern p = Ll1Pattern.compile(ABC + "\\Z", Ll1Pattern.UNIX_LINES);
    assertThat(p.matcher(ABC + "\r").find(), is(false));
    assertThat(p.matcher(ABC + "\n").find(), is(true));
  }

  // --- A loop followed by a zero-width assertion, then a character the loop also accepts, is a
  // genuine ambiguity (this engine never backtracks, so once the loop has consumed a character
  // it can't un-consume it to let the assertion hold instead) and must be rejected at compile
  // time, exactly like the boundary-free "a*a" case -- see remaining_work.md's former "A loop
  // followed by a zero-width assertion..." entry. ---

  @Test
  public void loopFollowedByZeroWidthAssertionThenAcceptedChar_rejectedAtCompileTime() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a*^a"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a*(^a)"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a*\\ba"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a*\\Aa"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a?^a"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a*(^|x)a"));
  }

  @Test
  public void loopFollowedByZeroWidthAssertionThenDifferentChar_stillCompiles() {
    // No real overlap (the loop's own body accepts 'a', but what's actually reachable past the
    // assertion is 'b') -- must not be rejected.
    assertThat(Ll1Pattern.compile("a*^b").matcher("b").matches(), is(true));
    assertThat(Ll1Pattern.compile("a*\\bb").matcher("ab").matches(), is(false));
  }

  // --- A greedy loop followed only by \B, or a MULTILINE ^/$, whose truth value can genuinely
  // depend on how many iterations the loop just consumed, is ALSO a genuine LL(1) ambiguity, same
  // family as the boundary-free "a*a" case above -- see remaining_work.md's former "\B-near-
  // regionEnd match-result divergence"/"residual reluctant-loop-before-\B" entries (root-caused to
  // this gap, not a regionEnd-specific bug). \b and a non-MULTILINE $ never admit a real interior
  // exit character this way (see WordBoundaryConstruct/LineBoundaryConstruct's own
  // admittedInteriorExitPeekSet docs), so they must keep compiling; possessive and reluctant loops
  // are exempt too (see QuantifiableConstruct#possessive/#buildLoopMatcher) since a possessive loop
  // never backtracks in java.util.regex either, and a reluctant loop is instead fixed at match time
  // -- see ReluctantQuantifierDifferentialTest/KnownDivergenceTest.

  @Test
  public void greedyLoopFollowedByPositionDependentAssertionThenPureEnd_rejectedAtCompileTime() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a+\\B"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("\\w+\\B\\w"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?m)\\n+^"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?m)\\n+$"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("[a!]+\\B"));
  }

  @Test
  public void greedyLoopFollowedByAssertionThatNeverAdmitsAnInteriorExit_stillCompiles() {
    // \b and a non-MULTILINE $ can never hold right after a body iteration that could also
    // continue, so these must NOT be rejected.
    assertThat(Ll1Pattern.compile("a+\\b").matcher("aa").find(), is(true));
    assertThat(Ll1Pattern.compile("(?m)a+$").matcher("aa\n").find(), is(true));
    assertThat(Ll1Pattern.compile("a+$").matcher("aa").find(), is(true));
  }

  @Test
  public void possessiveAndReluctantLoopsAreExemptFromTheAssertionAmbiguityCheck() {
    // Possessive already agrees with java.util.regex's own possessive (neither backtracks), and a
    // reluctant loop's early exit is instead proven safe/unsafe at match time -- see
    // MatcherConstruct#exitAssertionChain.
    assertThat(Ll1Pattern.compile("a++\\B").matcher("aa").find(), is(false));
    Matcher reluctant = Ll1Pattern.compile("a+?\\B").matcher("aab");
    assertThat(reluctant.find(), is(true));
    assertThat(reluctant.group(), is("a"));
  }

  @Test
  public void boundaryInOrdinaryAlternation_stillCompilesAndMatches() {
    // A zero-width assertion as a plain union branch (not a loop's own tail) never commits any
    // input before its own runtime check can veto it, so it's fine as a low-priority catch-all
    // there -- only a loop's own ambiguity check needs the stricter treatment above.
    assertThat(Ll1Pattern.compile("(^a|b)c").matcher("bc").matches(), is(true));
    assertThat(Ll1Pattern.compile("(^a|b)c").matcher("ac").matches(), is(true));
  }
}

package com.tbohne.llkpattern;

import static com.tbohne.llkpattern.SupplementaryChars.A;
import static com.tbohne.llkpattern.SupplementaryChars.B;
import static com.tbohne.llkpattern.SupplementaryChars.C;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@code \1}-{@code \9} (numbered backreference) and {@code \k<name>} (named backreference) --
 * see design.md's "Backreferences" section for the compile-time entry-set design being tested
 * here.
 *
 * <p>Tests whose literal content is just a group marker (not tied to \w/case-folding semantics)
 * use SupplementaryChars' A/B/C in place of the plain ASCII 'a'/'b'/'c' they originally used --
 * see SupplementaryPatternTextTest. The \w+-based tests (repeated-word matching, case
 * insensitivity) are left as ASCII -- they hinge on \w's own (ASCII-by-default) word-character
 * classification and case-folding, not the parser's literal lookahead.
 */
@RunWith(JUnit4.class)
public class BackReferenceTest {
  // --- Basic matching ---

  @Test
  public void numberedBackReference_matchesRepeatedText() {
    Ll1Pattern p = Ll1Pattern.compile("(\\w+)\\s+\\1");
    assertThat(p.matcher("hello hello").matches(), is(true));
    assertThat(p.matcher("hello world").matches(), is(false));
  }

  @Test
  public void namedBackReference_matchesRepeatedText() {
    Ll1Pattern p = Ll1Pattern.compile("(?<word>\\w+)\\s+\\k<word>");
    assertThat(p.matcher("hello hello").matches(), is(true));
    assertThat(p.matcher("hello world").matches(), is(false));
  }

  @Test
  public void backReference_isCaseInsensitiveUnderFlag() {
    Ll1Pattern p = Ll1Pattern.compile("(?i)(\\w+)\\s+\\1");
    assertThat(p.matcher("Hello hello").matches(), is(true));
  }

  @Test
  public void backReference_toEmptyCapture_matchesEmpty() {
    // "(a?)" can capture "" -- \1 referencing that empty capture is zero-width and always
    // succeeds -- but see backReference_toPossiblyEmptyGroup_rejectedAsAmbiguous below for why a
    // POSSIBLY-empty group ambiguous with a sibling branch is rejected at compile time instead:
    // "(a?)" here always resolves to exactly "" or "a", never ambiguous with 'b'.
    Ll1Pattern p = Ll1Pattern.compile("(" + A + "?)" + B + "\\1");
    assertThat(p.matcher(A + B).matches(), is(false)); // \1=A, but nothing left to match it
    assertThat(p.matcher(B).matches(), is(true)); // \1=""
  }

  @Test
  public void backReference_toUnparticipatedGroup_neverMatches() {
    // "(a)?" is optional -- when it matches zero times, group 1 never participates (its
    // BeginCapture never fires, unlike an empty-but-participating "(a?)"). Per java.util.regex
    // semantics, an unparticipated group's backreference fails outright rather than matching the
    // empty string.
    Ll1Pattern p = Ll1Pattern.compile("(" + A + ")?" + B + "\\1");
    assertThat(p.matcher(B + "x").matches(), is(false)); // group 1 didn't participate: \1 can't match
    assertThat(p.matcher(A + B + A).matches(), is(true)); // group 1 participated: \1 matches A
  }

  // --- Compile-time ambiguity detection (design.md's "Backreferences" section) ---

  @Test
  public void backReference_ambiguousWithLoopTail_rejectedAtCompileTime() {
    // (a+)\1: the loop's own "keep matching 'a'" branch and \1's entry set ({a}, since group 1
    // always starts with 'a') can both claim 'a' -- ambiguous, same as any other LL(1) violation.
    try {
      Ll1Pattern.compile("(" + A + "+)\\1");
      fail("expected PatternSyntaxException for ambiguous backreference");
    } catch (PatternSyntaxException expected) {
      // expected
    }
  }

  @Test
  public void backReference_toPossiblyEmptyGroup_isLowestPriorityCatchAll() {
    // "(a*)" can match zero-width, so \1's first-char-set is unknown, falling back to the
    // catch-all entry set (see design.md's "Backreferences" section) -- exactly like
    // BackReference's original, pre-firstCharSet() stub. As a sole alternation sibling next to a
    // branch with a specific, disjoint entry set ('b'), that's not itself ambiguous: 'b' claims
    // its own character and \1 is only ever reached otherwise.
    Ll1Pattern p = Ll1Pattern.compile("(" + A + "*)" + B + "(?:\\1|" + C + ")");
    assertThat(p.matcher(A + A + B + C).matches(), is(true)); // C branch taken
    assertThat(p.matcher(A + A + B + A + A).matches(), is(true)); // \1=AA, the catch-all branch taken
  }

  @Test
  public void twoPossiblyEmptyBackReferences_bothCatchAll_rejectedAsAmbiguous() {
    // Two different possibly-empty groups' backreferences, as sibling alternation branches, both
    // fall back to a catch-all entry set -- exactly the "two candidates both allow any character"
    // ambiguity mergeEntryPoints already rejects for any other construct type.
    try {
      Ll1Pattern.compile("(" + A + "*)(" + B + "*)" + C + "(?:\\1|\\2)");
      fail("expected PatternSyntaxException for two catch-all backreference branches");
    } catch (PatternSyntaxException expected) {
      // expected
    }
  }

  @Test
  public void backReference_disjointFromSiblingBranch_compilesAndDispatchesExactly() {
    // (a)(?:\1|b): \1's entry set is exactly {a} (group 1 always captures "a"), disjoint from
    // 'b' -- should compile and dispatch to the correct branch based on the next character,
    // rather than always preferring \1 (see design.md's rejected "catch-all" alternative).
    Ll1Pattern p = Ll1Pattern.compile("(" + A + ")(?:\\1|" + B + ")");
    assertThat(p.matcher(A + A).matches(), is(true));
    assertThat(p.matcher(A + B).matches(), is(true));
    assertThat(p.matcher(A + C).matches(), is(false));
  }

  // --- Forward references / undefined groups rejected at parse time ---

  @Test
  public void forwardNumberedReference_rejectedAtParseTime() {
    try {
      Ll1Pattern.compile("\\1(" + A + ")");
      fail("expected PatternSyntaxException for forward reference");
    } catch (PatternSyntaxException expected) {
      // expected
    }
  }

  @Test
  public void undefinedNumberedReference_rejectedAtParseTime() {
    try {
      Ll1Pattern.compile("(" + A + ")\\2");
      fail("expected PatternSyntaxException for undefined group reference");
    } catch (PatternSyntaxException expected) {
      // expected
    }
  }

  @Test
  public void forwardNamedReference_rejectedAtParseTime() {
    try {
      Ll1Pattern.compile("\\k<word>(?<word>" + A + ")");
      fail("expected PatternSyntaxException for forward named reference");
    } catch (PatternSyntaxException expected) {
      // expected
    }
  }

  @Test
  public void undefinedNamedReference_rejectedAtParseTime() {
    try {
      Ll1Pattern.compile("(?<word>" + A + ")\\k<other>");
      fail("expected PatternSyntaxException for undefined named group reference");
    } catch (PatternSyntaxException expected) {
      assertThat(expected.getMessage(), containsString("other"));
    }
  }

  @Test
  public void malformedNamedReference_rejectedAtParseTime() {
    try {
      Ll1Pattern.compile("(?<word>" + A + ")\\k<word");
      fail("expected PatternSyntaxException for a \\k<name> missing its closing '>'");
    } catch (PatternSyntaxException expected) {
      // expected
    }
  }
}

package com.tbohne.llkpattern;

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
    Ll1Pattern p = Ll1Pattern.compile("(a?)b\\1");
    assertThat(p.matcher("ab").matches(), is(false)); // \1="a", but nothing left to match it
    assertThat(p.matcher("b").matches(), is(true)); // \1=""
  }

  @Test
  public void backReference_toUnparticipatedGroup_neverMatches() {
    // "(a)?" is optional -- when it matches zero times, group 1 never participates (its
    // BeginCapture never fires, unlike an empty-but-participating "(a?)"). Per java.util.regex
    // semantics, an unparticipated group's backreference fails outright rather than matching the
    // empty string.
    Ll1Pattern p = Ll1Pattern.compile("(a)?b\\1");
    assertThat(p.matcher("bx").matches(), is(false)); // group 1 didn't participate: \1 can't match
    assertThat(p.matcher("aba").matches(), is(true)); // group 1 participated: \1 matches "a"
  }

  // --- Compile-time ambiguity detection (design.md's "Backreferences" section) ---

  @Test
  public void backReference_ambiguousWithLoopTail_rejectedAtCompileTime() {
    // (a+)\1: the loop's own "keep matching 'a'" branch and \1's entry set ({a}, since group 1
    // always starts with 'a') can both claim 'a' -- ambiguous, same as any other LL(1) violation.
    try {
      Ll1Pattern.compile("(a+)\\1");
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
    Ll1Pattern p = Ll1Pattern.compile("(a*)b(?:\\1|c)");
    assertThat(p.matcher("aabc").matches(), is(true)); // 'c' branch taken
    assertThat(p.matcher("aabaa").matches(), is(true)); // \1="aa", the catch-all branch taken
  }

  @Test
  public void twoPossiblyEmptyBackReferences_bothCatchAll_rejectedAsAmbiguous() {
    // Two different possibly-empty groups' backreferences, as sibling alternation branches, both
    // fall back to a catch-all entry set -- exactly the "two candidates both allow any character"
    // ambiguity compileAndMergeCandidates already rejects for any other construct type.
    try {
      Ll1Pattern.compile("(a*)(b*)c(?:\\1|\\2)");
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
    Ll1Pattern p = Ll1Pattern.compile("(a)(?:\\1|b)");
    assertThat(p.matcher("aa").matches(), is(true));
    assertThat(p.matcher("ab").matches(), is(true));
    assertThat(p.matcher("ac").matches(), is(false));
  }

  // --- Forward references / undefined groups rejected at parse time ---

  @Test
  public void forwardNumberedReference_rejectedAtParseTime() {
    try {
      Ll1Pattern.compile("\\1(a)");
      fail("expected PatternSyntaxException for forward reference");
    } catch (PatternSyntaxException expected) {
      // expected
    }
  }

  @Test
  public void undefinedNumberedReference_rejectedAtParseTime() {
    try {
      Ll1Pattern.compile("(a)\\2");
      fail("expected PatternSyntaxException for undefined group reference");
    } catch (PatternSyntaxException expected) {
      // expected
    }
  }

  @Test
  public void forwardNamedReference_rejectedAtParseTime() {
    try {
      Ll1Pattern.compile("\\k<word>(?<word>a)");
      fail("expected PatternSyntaxException for forward named reference");
    } catch (PatternSyntaxException expected) {
      // expected
    }
  }

  @Test
  public void undefinedNamedReference_rejectedAtParseTime() {
    try {
      Ll1Pattern.compile("(?<word>a)\\k<other>");
      fail("expected PatternSyntaxException for undefined named group reference");
    } catch (PatternSyntaxException expected) {
      assertThat(expected.getMessage(), containsString("other"));
    }
  }

  @Test
  public void malformedNamedReference_rejectedAtParseTime() {
    try {
      Ll1Pattern.compile("(?<word>a)\\k<word");
      fail("expected PatternSyntaxException for a \\k<name> missing its closing '>'");
    } catch (PatternSyntaxException expected) {
      // expected
    }
  }
}

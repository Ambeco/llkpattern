package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests that a supplementary (astral) code point in a pattern's own SOURCE TEXT parses correctly
 * regardless of what immediately precedes it -- as opposed to {@link SurrogateMatchingTest}, which
 * covers supplementary code points in the INPUT being matched.
 *
 * <p>This targets the bug fixed 2026-09-14 (see remaining_work.md's former "HIGHEST PRIORITY"
 * entry, and {@code PatternParser#peek}'s own field doc): {@code PatternParser}'s {@code
 * advance}/{@code advanceCodePoint} used to set {@code peek} via {@code pattern.charAt(index)},
 * reading only a lone surrogate half whenever a supplementary character sat at {@code index} --
 * {@code peek}'s very field type ({@code char}) couldn't hold a real supplementary code point at
 * all. Every ordinary literal-character path happened to re-read via {@code
 * pattern.codePointAt(index)} directly rather than trusting the stale {@code peek} value, so most
 * of these cases were already correct by accident; the tests below exercise exactly the positions
 * (right after a fixed ASCII token -- a group's ")", a quantifier's own suffix chars, "|", "[",
 * "\\") where that accident could plausibly have NOT held, plus the one place the bug was
 * genuinely observable: {@code parseComplexEscape}'s invalid-escape-name lookup/error message,
 * which used {@code peek}'s numeric value directly rather than just comparing it to ASCII tokens.
 */
@RunWith(JUnit4.class)
public class SupplementaryPatternTextTest {
  // U+10000 (LINEAR B SYLLABLE B008 A) -- same code point SurrogateMatchingTest uses.
  private static final String SUPPLEMENTARY = "𐀀";

  private static void assertMatches(String pattern, String input) {
    assertThat(pattern + " vs " + input, Ll1Pattern.compile(pattern).matcher(input).matches(), is(true));
  }

  @Test
  public void literalAfterGroup_parsesCorrectly() {
    assertMatches("(a)" + SUPPLEMENTARY, "a" + SUPPLEMENTARY);
  }

  @Test
  public void literalAfterAlternation_parsesCorrectly() {
    assertMatches("a|" + SUPPLEMENTARY, SUPPLEMENTARY);
  }

  @Test
  public void literalAfterCharacterClass_parsesCorrectly() {
    assertMatches("[a]" + SUPPLEMENTARY, "a" + SUPPLEMENTARY);
  }

  @Test
  public void literalInsideCharacterClass_parsesCorrectly() {
    assertMatches("[" + SUPPLEMENTARY + "]", SUPPLEMENTARY);
  }

  @Test
  public void quantifierAfterSupplementaryLiteral_appliesToTheWholeCodePoint() {
    // "+" must apply to the whole U+10000 code point, not just its low surrogate half.
    assertMatches(SUPPLEMENTARY + "+", SUPPLEMENTARY + SUPPLEMENTARY + SUPPLEMENTARY);
  }

  @Test
  public void literalAfterSupplementaryLiteral_bothCodePointsMatchIndependently() {
    // Two consecutive supplementary code points, not a single 4-char literal run misread as
    // something else.
    assertMatches(SUPPLEMENTARY + SUPPLEMENTARY, SUPPLEMENTARY + SUPPLEMENTARY);
  }

  @Test
  public void literalAfterQuantifiedGroup_parsesCorrectly() {
    assertMatches("(a)*" + SUPPLEMENTARY, SUPPLEMENTARY);
  }

  @Test
  public void commentsMode_supplementaryCharInsideComment_isSkipped() {
    // (?x) COMMENTS mode: a "#"-to-end-of-line comment containing a supplementary character must
    // still be skipped as a whole, not have its low surrogate half leak out as a stray literal.
    assertMatches("(?x) a # comment with " + SUPPLEMENTARY + " in it\n b ", "ab");
  }

  @Test
  public void invalidEscapeOfSupplementaryChar_reportsTheRealCodePoint() {
    // "\" followed directly by a supplementary character isn't a recognized escape form (same as
    // "\" followed by an arbitrary unrecognized letter) -- but the exception message must name the
    // actual U+10000 code point, not a lone (and, on its own, meaningless) surrogate half.
    try {
      Ll1Pattern.compile("\\" + SUPPLEMENTARY);
      fail("expected PatternSyntaxException for an unrecognized escape");
    } catch (PatternSyntaxException expected) {
      assertThat(expected.getMessage(), containsString("U+10000"));
    }
  }
}

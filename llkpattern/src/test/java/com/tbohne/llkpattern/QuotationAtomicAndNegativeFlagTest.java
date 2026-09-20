package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@code \Q...\E} quotation, atomic groups {@code (?>X)}, and negative-only flag groups. */
@RunWith(JUnit4.class)
public class QuotationAtomicAndNegativeFlagTest {
  private static void assertMatches(String pattern, String input, boolean expected) {
    assertMatches(pattern, 0, input, expected);
  }

  private static void assertMatches(String pattern, int flags, String input, boolean expected) {
    assertThat(
        pattern + " vs " + input,
        Ll1Pattern.compile(pattern, flags).matcher(input).matches(),
        is(expected));
    // Cross-check against the reference engine.
    assertThat(
        "java.util.regex " + pattern + " vs " + input,
        java.util.regex.Pattern.compile(pattern, flags).matcher(input).matches(),
        is(expected));
  }

  @Test
  public void quotationMatchesMetacharactersLiterally() {
    assertMatches("\\Qa.b*c\\E", "a.b*c", true);
    assertMatches("\\Qa.b*c\\E", "aXbbc", false);
    assertMatches("x\\Q(a|b)[c]\\Ey", "x(a|b)[c]y", true);
    assertMatches("\\Q\\\\E", "\\", true);
  }

  @Test
  public void quotationEdgeCases() {
    assertMatches("\\Q\\Ea", "a", true);
    assertMatches("a\\Qbc", "abc", true); // unterminated: quotes to end of pattern
    assertMatches("\\Qa\\E\\Qb\\E", "ab", true);
    assertMatches("\\Q1\\E", "1", true);
    assertMatches("\\Q䑄𐀀\\E", "䑄𐀀", true);
  }

  @Test
  public void quantifierAppliesToLastQuotedCharacter() {
    assertMatches("\\Qab\\E+", "abbb", true);
    assertMatches("\\Qab\\E+", "abab", false);
  }

  @Test
  public void quotationInsideBracketAndGroup() {
    assertMatches("[\\Q^-]\\E]", "-", true);
    assertMatches("[\\Q^-]\\E]", "a", false);
    assertMatches("(\\Q.\\E)b", "b", false);
    assertMatches("(\\Q.\\E)b", ".b", true);
  }

  @Test
  public void quotationUnderCommentsFlagKeepsWhitespaceAndHash() {
    assertMatches("\\Qa b#c\\E", java.util.regex.Pattern.COMMENTS, "a b#c", true);
  }

  @Test
  public void atomicGroupIsNonCapturing() {
    assertMatches("(?>ab)c", "abc", true);
    assertMatches("(?>a|b)+c", "abbac", true);
    Matcher m = Ll1Pattern.compile("(?>a)(b)").matcher("ab");
    assertThat(m.matches(), is(true));
    assertThat(m.groupCount(), is(1));
    assertThat(m.group(1), is("b"));
  }

  @Test
  public void negativeOnlyFlagGroups() {
    int i = java.util.regex.Pattern.CASE_INSENSITIVE;
    assertMatches("(?-i)a", i, "a", true);
    assertMatches("(?-i)a", i, "A", false);
    assertMatches("(?-i:a)b", i, "aB", true);
    assertMatches("(?-i:a)b", i, "AB", false);
    assertMatches("(?i)a(?-i)b", "Ab", true);
    assertMatches("(?i)a(?-i)b", "AB", false);
    assertMatches("(?i-s)a", "A", true);
  }
}

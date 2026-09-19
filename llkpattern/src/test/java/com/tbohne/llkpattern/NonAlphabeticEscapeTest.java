package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Gaps found by the OpenJDK corpus triage (2026-09-19): a backslash before any non-alphabetic
 * character, single-letter {@code \pL}, {@code \p{IsXxx}} for the POSIX classes, and the ordering
 * check on a bracket range whose maximum is an escape.
 */
@RunWith(JUnit4.class)
public class NonAlphabeticEscapeTest {
  private static void assertMatches(String pattern, String input, boolean expected) {
    assertThat(pattern + " vs " + input, Ll1Pattern.compile(pattern).matcher(input).matches(), is(expected));
  }

  private static void assertRejected(String pattern) {
    try {
      Ll1Pattern.compile(pattern);
      fail("expected PatternSyntaxException for " + pattern);
    } catch (PatternSyntaxException expected) {
      // fine
    }
  }

  @Test
  public void backslashBeforeNonAlphabeticCharacterQuotesIt() {
    assertMatches("a\\-b", "a-b", true);
    assertMatches("a\\,b", "a,b", true);
    assertMatches("a\\ b", "a b", true);
    assertMatches("\\<\\_", "<_", true);
    assertMatches("a\\䑄", "a䑄", true);
    assertMatches("a\\𐀀", "a𐀀", true);
  }

  @Test
  public void backslashBeforeNonAlphabeticCharacterInBracket() {
    assertMatches("[\\-a]", "-", true);
    assertMatches("[\\,a]", ",", true);
    assertMatches("[\\䑄a]", "䑄", true);
    assertMatches("[\\-a]", "b", false);
  }

  @Test
  public void backslashBeforeAsciiLetterIsStillAnError() {
    assertRejected("\\i");
    assertRejected("\\y");
    assertRejected("[\\y]");
  }

  @Test
  public void escapedRangeMaximum() {
    assertMatches("[a-\\䑄]", "b", true);
    assertMatches("[a-\\䑄]", "䑄", true);
    assertMatches("[a-\\䑄]", "䑅", false);
    assertMatches("[ -\\?]", "!", true);
  }

  @Test
  public void rangeMaximumOrdering() {
    assertMatches("[a-a]", "a", true);
    assertMatches("[a-\\x61]", "a", true);
    assertRejected("[b-a]");
    assertRejected("[b-\\x61]");
    assertRejected("[a-\\,]");
  }

  @Test
  public void singleLetterPropertyEscape() {
    assertMatches("\\pL", "x", true);
    assertMatches("\\pL", "1", false);
    assertMatches("\\PL", "1", true);
    assertMatches("[\\pL\\d]+", "a1", true);
    assertMatches("\\pLb", "ab", true);
  }

  @Test
  public void singleLetterPropertyEscapeErrors() {
    assertRejected("\\p");
    assertRejected("\\p1");
    assertRejected("\\pZZ+(");
  }

  @Test
  public void isPrefixOnPosixClassesIsAlwaysFullUnicode() {
    assertMatches("\\p{IsASCII}", "a", true);
    assertMatches("\\p{IsASCII}", "é", false);
    assertMatches("\\P{IsASCII}", "é", true);
    assertMatches("\\p{IsLower}", "é", true);
    assertMatches("\\p{Lower}", "é", false);
    assertMatches("\\p{IsUpper}", "É", true);
    assertMatches("\\p{IsAlpha}", "é", true);
    assertMatches("\\p{IsPunct}", "¡", true);
    assertMatches("\\p{IsXDigit}", "１", true);
    assertRejected("\\p{Isascii}");
  }
}

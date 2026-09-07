package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Single-character escape coverage: literal backslash, octal, hex/unicode, and the named
 * control-character escapes. Each test compiles a pattern using the escape and actually calls
 * matches()/find() against real input -- see notes.md's standing lesson that "parses without
 * throwing" alone has repeatedly hidden real bugs in this codebase.
 */
@RunWith(JUnit4.class)
public class EscapeTest {
  @Test
  public void backslash_matchesLiteralBackslash() {
    assertThat(Ll1Pattern.compile("a\\\\b").matcher("a\\b").matches(), is(true));
    assertThat(Ll1Pattern.compile("a\\\\b").matcher("ab").matches(), is(false));
  }

  @Test
  public void octal_oneDigit() {
    assertThat(Ll1Pattern.compile("\\07").matcher("\u0007").matches(), is(true));
  }

  @Test
  public void octal_twoDigits() {
    assertThat(Ll1Pattern.compile("\\075").matcher("=").matches(), is(true)); // 075 octal = '='
  }

  @Test
  public void octal_threeDigits() {
    assertThat(Ll1Pattern.compile("\\0101").matcher("A").matches(), is(true)); // 0101 octal = 'A'
  }

  @Test
  public void octal_missingDigitAfterZero_throws() {
    org.junit.Assert.assertThrows(
        PatternSyntaxException.class, () -> Ll1Pattern.compile("\\0"));
  }

  @Test
  public void hex_twoDigit() {
    assertThat(Ll1Pattern.compile("\\x41").matcher("A").matches(), is(true));
  }

  @Test
  public void unicode_fourDigit() {
    assertThat(Ll1Pattern.compile("\\u0041").matcher("A").matches(), is(true));
  }

  @Test
  public void hex_braced_singleDigit() {
    assertThat(Ll1Pattern.compile("\\x{41}").matcher("A").matches(), is(true));
  }

  @Test
  public void hex_braced_supplementaryCodePoint() {
    // U+1F4A9 PILE OF POO, outside the BMP -- exercises the full-codepoint (not just char) path.
    Ll1Pattern p = Ll1Pattern.compile("\\x{1F4A9}");
    assertThat(p.matcher(new String(Character.toChars(0x1F4A9))).matches(), is(true));
  }

  @Test
  public void tab_escape() {
    assertThat(Ll1Pattern.compile("a\\tb").matcher("a\tb").matches(), is(true));
  }

  @Test
  public void newline_escape() {
    assertThat(Ll1Pattern.compile("a\\nb").matcher("a\nb").matches(), is(true));
  }

  @Test
  public void carriageReturn_escape() {
    assertThat(Ll1Pattern.compile("a\\rb").matcher("a\rb").matches(), is(true));
  }

  @Test
  public void formFeed_escape() {
    assertThat(Ll1Pattern.compile("a\\fb").matcher("a\fb").matches(), is(true));
  }

  @Test
  public void alert_escape() {
    assertThat(Ll1Pattern.compile("\\a").matcher("\u0007").matches(), is(true));
  }

  @Test
  public void escapeChar_escape() {
    assertThat(Ll1Pattern.compile("\\e").matcher("\u001B").matches(), is(true));
  }

  @Test
  public void control_escape_letterForm() {
    // \cX -> control code (X's position in the alphabet); \cA is U+0001.
    assertThat(Ll1Pattern.compile("\\cA").matcher("\u0001").matches(), is(true));
  }

  @Test
  public void control_escape_questionMarkIsDelete() {
    assertThat(Ll1Pattern.compile("\\c?").matcher("\u007F").matches(), is(true));
  }

  @Test
  public void control_escape_invalidLetter_throws() {
    org.junit.Assert.assertThrows(
        PatternSyntaxException.class, () -> Ll1Pattern.compile("\\c1"));
  }
}

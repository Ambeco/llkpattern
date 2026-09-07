package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Predefined character classes ('.', \d/\D, \h/\H, \s/\S, \v/\V, \w/\W) and \R. Real
 * matches()/find() calls per pattern/negation pair -- see notes.md.
 */
@RunWith(JUnit4.class)
public class PredefinedClassTest {
  @Test
  public void dot_matchesOrdinaryCharacter() {
    assertThat(Ll1Pattern.compile(".").matcher("x").matches(), is(true));
  }

  @Test
  public void dot_doesNotMatchNewlineByDefault() {
    assertThat(Ll1Pattern.compile(".").matcher("\n").matches(), is(false));
  }

  @Test
  public void digit_matchesDigit() {
    assertThat(Ll1Pattern.compile("\\d").matcher("5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\d").matcher("a").matches(), is(false));
  }

  @Test
  public void nonDigit_isComplement() {
    assertThat(Ll1Pattern.compile("\\D").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\D").matcher("5").matches(), is(false));
  }

  @Test
  public void horizontalWhitespace_matchesTab() {
    assertThat(Ll1Pattern.compile("\\h").matcher("\t").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\h").matcher("a").matches(), is(false));
  }

  @Test
  public void nonHorizontalWhitespace_isComplement() {
    assertThat(Ll1Pattern.compile("\\H").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\H").matcher("\t").matches(), is(false));
  }

  @Test
  public void whitespace_matchesSpaceAndNewline() {
    assertThat(Ll1Pattern.compile("\\s").matcher(" ").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\s").matcher("\n").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\s").matcher("a").matches(), is(false));
  }

  @Test
  public void nonWhitespace_isComplement() {
    assertThat(Ll1Pattern.compile("\\S").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\S").matcher(" ").matches(), is(false));
  }

  @Test
  public void verticalWhitespace_matchesNewline() {
    assertThat(Ll1Pattern.compile("\\v").matcher("\n").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\v").matcher("a").matches(), is(false));
  }

  @Test
  public void nonVerticalWhitespace_isComplement() {
    assertThat(Ll1Pattern.compile("\\V").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\V").matcher("\n").matches(), is(false));
  }

  @Test
  public void wordChar_matchesLetterDigitUnderscore() {
    assertThat(Ll1Pattern.compile("\\w").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\w").matcher("5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\w").matcher("_").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\w").matcher(" ").matches(), is(false));
  }

  @Test
  public void nonWordChar_isComplement() {
    assertThat(Ll1Pattern.compile("\\W").matcher(" ").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\W").matcher("a").matches(), is(false));
  }

  @Test
  public void linebreak_matchesNewlineAndCrlf() {
    Ll1Pattern p = Ll1Pattern.compile("\\R");
    assertThat(p.matcher("\n").matches(), is(true));
    assertThat(p.matcher("\r").matches(), is(true));
  }
}

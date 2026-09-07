package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * \A, \z, \Z (always the true start/end of input, MULTILINE has no effect), and ^/$ (equivalent
 * to \A/\Z by default; match at line boundaries under MULTILINE) -- see design.md's "Boundary
 * matching" section. \b/\B are covered separately in WordBoundaryTest; \G is covered separately
 * in PreviousMatchEndTest (it isn't a position-based boundary at all, see that class's doc).
 */
@RunWith(JUnit4.class)
public class LineAndInputBoundaryTest {
  // --- \A: always the true start of input ---

  @Test
  public void inputBegin_matchesOnlyAtStart() {
    assertThat(Ll1Pattern.compile("\\Aabc").matcher("abc").matches(), is(true));
    assertThat(Ll1Pattern.compile(".\\Aabc").matcher("xabc").matches(), is(false));
  }

  @Test
  public void inputBegin_multilineHasNoEffect() {
    Ll1Pattern p = Ll1Pattern.compile("\\Aabc", Ll1Pattern.MULTILINE);
    Matcher m = p.matcher("x\nabc");
    assertThat(m.find(), is(false));
  }

  // --- \z: always the true end of input ---

  @Test
  public void inputEnd_matchesOnlyAtEnd() {
    assertThat(Ll1Pattern.compile("abc\\z").matcher("abc").matches(), is(true));
    assertThat(Ll1Pattern.compile("abc\\z.").matcher("abcx").matches(), is(false));
  }

  @Test
  public void inputEnd_doesNotMatchBeforeTrailingNewline() {
    assertThat(Ll1Pattern.compile("abc\\z").matcher("abc\n").matches(), is(false));
  }

  @Test
  public void inputEnd_multilineHasNoEffect() {
    Ll1Pattern p = Ll1Pattern.compile("abc\\z", Ll1Pattern.MULTILINE);
    assertThat(p.matcher("abc\ndef").find(), is(false));
  }

  // --- \Z: end of input, or immediately before a trailing line terminator. Note this is a
  // zero-width assertion that does NOT consume the terminator -- matches() (which requires the
  // *whole region* consumed, terminator included) correctly fails whenever there's a trailing
  // terminator to account for; only find()/lookingAt() (a prefix match) see the exemption. Real
  // java.util.regex behaves identically here -- verified directly against it while writing this.

  @Test
  public void inputEndExceptTerminator_matchesAtTrueEnd() {
    assertThat(Ll1Pattern.compile("abc\\Z").matcher("abc").matches(), is(true));
  }

  @Test
  public void inputEndExceptTerminator_findsBeforeTrailingNewlineButDoesNotConsumeIt() {
    Matcher m = Ll1Pattern.compile("abc\\Z").matcher("abc\n");
    assertThat(m.find(), is(true));
    assertThat(m.end(), is(3));
    assertThat(Ll1Pattern.compile("abc\\Z").matcher("abc\n").matches(), is(false));
  }

  @Test
  public void inputEndExceptTerminator_findsBeforeTrailingCrLfButDoesNotConsumeIt() {
    Matcher m = Ll1Pattern.compile("abc\\Z").matcher("abc\r\n");
    assertThat(m.find(), is(true));
    assertThat(m.end(), is(3));
  }

  @Test
  public void inputEndExceptTerminator_doesNotMatchBeforeAnInteriorNewline() {
    assertThat(Ll1Pattern.compile("abc\\Z").matcher("abc\ndef").find(), is(false));
  }

  @Test
  public void inputEndExceptTerminator_doesNotMatchBeforeTwoTrailingNewlines() {
    // Only the single, final terminator is exempted -- not an arbitrary run of them.
    assertThat(Ll1Pattern.compile("abc\\Z").matcher("abc\n\n").find(), is(false));
  }

  // --- ^/$ without MULTILINE: equivalent to \A/\Z ---

  @Test
  public void lineBegin_withoutMultiline_behavesLikeInputBegin() {
    assertThat(Ll1Pattern.compile("^abc").matcher("abc").matches(), is(true));
    assertThat(Ll1Pattern.compile("^abc").matcher("x\nabc").find(), is(false));
  }

  @Test
  public void lineEnd_withoutMultiline_behavesLikeInputEndExceptTerminator() {
    assertThat(Ll1Pattern.compile("abc$").matcher("abc").matches(), is(true));
    Matcher m = Ll1Pattern.compile("abc$").matcher("abc\n");
    assertThat(m.find(), is(true));
    assertThat(m.end(), is(3));
    assertThat(Ll1Pattern.compile("abc$").matcher("abc\ndef").find(), is(false));
  }

  // --- ^/$ with MULTILINE: match at every line boundary ---

  @Test
  public void lineBegin_multiline_matchesAfterEveryNewline() {
    Ll1Pattern p = Ll1Pattern.compile("^abc", Ll1Pattern.MULTILINE);
    Matcher m = p.matcher("x\nabc\nabc");
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(2));
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(6));
  }

  @Test
  public void lineBegin_multiline_doesNotMatchMidTerminator() {
    // Right after the '\r' of a "\r\n" pair is mid-terminator, not a line begin.
    Ll1Pattern p = Ll1Pattern.compile("^", Ll1Pattern.MULTILINE);
    Matcher m = p.matcher("a\r\nb");
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(0));
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(3)); // after the full "\r\n", not after just "\r"
  }

  @Test
  public void lineEnd_multiline_matchesBeforeEveryNewline() {
    Ll1Pattern p = Ll1Pattern.compile("abc$", Ll1Pattern.MULTILINE);
    Matcher m = p.matcher("abc\nabc");
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(0));
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(4));
  }

  @Test
  public void lineEnd_multiline_alsoMatchesTrueEndOfInput() {
    assertThat(Ll1Pattern.compile("abc$", Ll1Pattern.MULTILINE).matcher("abc").matches(), is(true));
  }

  // --- UNIX_LINES: only "\n" counts as a line terminator ---

  @Test
  public void unixLines_crIsNotALineTerminator() {
    Ll1Pattern p = Ll1Pattern.compile("abc\\Z", Ll1Pattern.UNIX_LINES);
    assertThat(p.matcher("abc\r").find(), is(false));
    assertThat(p.matcher("abc\n").find(), is(true));
  }
}

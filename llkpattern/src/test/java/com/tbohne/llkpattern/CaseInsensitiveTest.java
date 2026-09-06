package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * CASE_INSENSITIVE/UNICODE_CASE and inline flag toggle coverage. Both were entirely broken until
 * 2026-09-06 -- see remaining_work.md's "URGENT: CASE_INSENSITIVE/UNICODE_CASE and inline flag
 * toggles" entry for the history. Scope note (also in remaining_work.md): this covers flags passed
 * to {@code Ll1Pattern.compile(pattern, flags)}; an inline {@code (?i)} now compiles instead of
 * throwing, but does NOT yet actually toggle case-sensitivity for only part of a pattern -- see
 * that entry for why, and the follow-up item this leaves behind.
 */
@RunWith(JUnit4.class)
public class CaseInsensitiveTest {
  @Test
  public void literal_caseInsensitive_matchesEitherCase() {
    Ll1Pattern p = Ll1Pattern.compile("abc", Ll1Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("ABC").matches(), is(true));
    assertThat(p.matcher("aBc").matches(), is(true));
    assertThat(p.matcher("abc").matches(), is(true));
  }

  @Test
  public void literal_default_isCaseSensitive() {
    Ll1Pattern p = Ll1Pattern.compile("abc");
    assertThat(p.matcher("ABC").matches(), is(false));
  }

  @Test
  public void charClass_lowercaseRange_caseInsensitive_matchesUppercaseInput() {
    Ll1Pattern p = Ll1Pattern.compile("[a-z]+", Ll1Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("ABC").matches(), is(true));
  }

  @Test
  public void charClass_uppercaseRange_caseInsensitive_matchesLowercaseInput() {
    Ll1Pattern p = Ll1Pattern.compile("[A-Z]+", Ll1Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("abc").matches(), is(true));
  }

  @Test
  public void charClass_default_isCaseSensitive() {
    Ll1Pattern p = Ll1Pattern.compile("[a-z]+");
    assertThat(p.matcher("ABC").matches(), is(false));
  }

  @Test
  public void asciiOnly_doesNotFoldNonAsciiLetters() {
    // Kelvin sign U+212A uppercases to itself under simple ASCII folding but is the uppercase form
    // of 'k' under full Unicode case folding -- exactly the case CASE_INSENSITIVE alone (without
    // UNICODE_CASE) is documented to NOT handle.
    Ll1Pattern p = Ll1Pattern.compile("k", Ll1Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("K").matches(), is(false));
  }

  @Test
  public void unicodeCase_foldsBeyondAscii() {
    Ll1Pattern p =
        Ll1Pattern.compile("k", Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CASE);
    assertThat(p.matcher("K").matches(), is(true));
  }

  @Test
  public void find_isCaseInsensitiveAcrossTheWholeMatch() {
    Ll1Pattern p = Ll1Pattern.compile("hello", Ll1Pattern.CASE_INSENSITIVE);
    Matcher m = p.matcher("say HELLO there");
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("HELLO"));
  }

  // --- inline flag toggles: used to throw on ANY flag letter, not just case ones ---

  @Test
  public void inlineFlag_wholePatternForm_compilesInsteadOfThrowing() {
    Ll1Pattern.compile("(?i)abc"); // used to throw PatternSyntaxException unconditionally
  }

  @Test
  public void inlineFlag_nonCaseFlag_alsoCompiles() {
    Ll1Pattern.compile("(?m)^abc$"); // same bug affected every flag letter, not just 'i'
  }

  @Test
  public void inlineFlag_groupForm_compiles() {
    Ll1Pattern.compile("(?i:abc)");
  }

  @Test
  public void inlineFlag_disableForm_compiles() {
    Ll1Pattern.compile("(?i-m:abc)");
  }

  // --- DOTALL: found broken (always-on, regardless of the flag) while fixing the above ---

  @Test
  public void dot_default_doesNotMatchNewline() {
    assertThat(Ll1Pattern.compile("a.b").matcher("a\nb").find(), is(false));
  }

  @Test
  public void dot_default_matchesOtherCharacters() {
    assertThat(Ll1Pattern.compile("a.b").matcher("axb").find(), is(true));
  }

  @Test
  public void dot_dotall_matchesNewline() {
    assertThat(Ll1Pattern.compile("a.b", Ll1Pattern.DOTALL).matcher("a\nb").find(), is(true));
  }

  // --- a flags-only group ((?s)/(?x)/etc, no ":") must not be miscounted as capturing group 0 ---

  @Test
  public void bareFlagsGroup_doesNotCorruptCaptureGroupNumbering() {
    // Used to throw ArrayIndexOutOfBoundsException at MATCH time: the bare "(?s)" form defaulted
    // to captureConstructIndex 0 (meaning "real capturing group 0") instead of -1 (non-capturing),
    // corrupting capture-group bookkeeping for the rest of the pattern -- a real group elsewhere
    // got miscounted, sizing Matcher#captureGroups too small. This only checks that compiling no
    // longer corrupts that count; it deliberately does NOT exercise matching through/after "(?s)"
    // itself, which is separately still broken -- see remaining_work.md's "a bare/empty
    // inline-flag group breaks the surrounding sequence" entry, found while fixing this one.
    Ll1Pattern p = Ll1Pattern.compile("(?s)(a)(b)");
    assertThat(p.matcher("").groupCount(), is(2));
  }

  @Test
  public void inlineFlag_repeatedLetter_stillRejected() {
    // The fix must not turn "already set" detection off entirely -- a genuine repeat like "(?ii)"
    // should still be a PatternSyntaxException, just not on the FIRST letter.
    org.junit.Assert.assertThrows(
        PatternSyntaxException.class, () -> Ll1Pattern.compile("(?ii)abc"));
  }
}

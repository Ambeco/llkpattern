package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unicode general categories (\p{Lu} etc, NamedCharClass's Source.Category entries) and binary
 * properties (\p{IsAlphabetic} etc, Source.UProperty entries), \P{...} negation, and
 * Unicode-qualified names used inside a character class. Scripts (\p{IsScript}) and blocks
 * (\p{InBlock}) are NOT implemented -- see remaining_work.md's noted gap (confirmed below: no
 * NamedCharClass enum entry uses Source.Block or Source.Script at all) -- so those are tested for
 * their actual (throwing) behavior, not skipped.
 */
@RunWith(JUnit4.class)
public class UnicodeClassTest {
  // --- General categories (two-letter category codes) ---

  @Test
  public void category_Lu_uppercaseLetter() {
    assertThat(Ll1Pattern.compile("\\p{Lu}").matcher("A").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Lu}").matcher("a").matches(), is(false));
  }

  @Test
  public void category_Ll_lowercaseLetter() {
    assertThat(Ll1Pattern.compile("\\p{Ll}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Ll}").matcher("A").matches(), is(false));
  }

  @Test
  public void category_Sc_currencySymbol() {
    assertThat(Ll1Pattern.compile("\\p{Sc}").matcher("$").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Sc}").matcher("a").matches(), is(false));
  }

  @Test
  public void category_Nd_decimalDigitNumber() {
    assertThat(Ll1Pattern.compile("\\p{Nd}").matcher("5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Nd}").matcher("a").matches(), is(false));
  }

  @Test
  public void category_L_topLevelLetterUnion() {
    // L is the union of Lu/Ll/Lt/Lm/Lo -- confirms the "no-prefix" top-level category also works.
    assertThat(Ll1Pattern.compile("\\p{L}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{L}").matcher("A").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{L}").matcher("5").matches(), is(false));
  }

  @Test
  public void category_withGeneralCategoryPrefix() {
    assertThat(Ll1Pattern.compile("\\p{general_category=Lu}").matcher("A").matches(), is(true));
  }

  // --- Binary properties (Source.UProperty, IsXxx form) ---

  @Test
  public void property_isAlphabetic() {
    assertThat(Ll1Pattern.compile("\\p{IsAlphabetic}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{IsAlphabetic}").matcher("5").matches(), is(false));
  }

  @Test
  public void property_isUppercase() {
    assertThat(Ll1Pattern.compile("\\p{IsUppercase}").matcher("A").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{IsUppercase}").matcher("a").matches(), is(false));
  }

  @Test
  public void property_isWhiteSpace() {
    assertThat(Ll1Pattern.compile("\\p{IsWhite_Space}").matcher(" ").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{IsWhite_Space}").matcher("a").matches(), is(false));
    // Bug fix (2026-09-07): NamedCharClass.White_Space used to delegate to javaWhitespace
    // (Character.isWhitespace), which deliberately excludes NO-BREAK SPACE (U+00A0) as
    // "non-breaking" -- but the real Unicode White_Space property does include it. Verified
    // against real java.util.regex before fixing (see White_Space's own doc for the rest of the
    // affected code points).
    assertThat(Ll1Pattern.compile("\\p{IsWhite_Space}").matcher("\u00a0").matches(), is(true));
    // U+180E (MONGOLIAN VOWEL SEPARATOR) is sometimes mistaken for whitespace -- it was removed
    // from the Unicode White_Space property in Unicode 6.3, and real java.util.regex agrees it's
    // not one.
    assertThat(Ll1Pattern.compile("\\p{IsWhite_Space}").matcher("\u180e").matches(), is(false));
  }

  @Test
  public void property_isHexDigit() {
    assertThat(Ll1Pattern.compile("\\p{IsHex_Digit}").matcher("f").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{IsHex_Digit}").matcher("g").matches(), is(false));
  }

  // --- \P{...} negation ---

  @Test
  public void negatedCategory_Lu() {
    assertThat(Ll1Pattern.compile("\\P{Lu}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\P{Lu}").matcher("A").matches(), is(false));
  }

  @Test
  public void negatedProperty_isAlphabetic() {
    assertThat(Ll1Pattern.compile("\\P{IsAlphabetic}").matcher("5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\P{IsAlphabetic}").matcher("a").matches(), is(false));
  }

  // --- Unicode-qualified names inside a character class ---

  @Test
  public void unicodeClassInsideCharClass_union() {
    Ll1Pattern p = Ll1Pattern.compile("[\\p{Lu}\\p{Nd}]");
    assertThat(p.matcher("A").matches(), is(true));
    assertThat(p.matcher("5").matches(), is(true));
    assertThat(p.matcher("a").matches(), is(false));
  }

  @Test
  public void unicodeClassInsideCharClass_intersectionWithNegatedOperand() {
    // Letters that are NOT uppercase -- i.e. lowercase/titlecase/etc letters.
    Ll1Pattern p = Ll1Pattern.compile("[\\p{L}&&[^\\p{Lu}]]");
    assertThat(p.matcher("a").matches(), is(true));
    assertThat(p.matcher("A").matches(), is(false));
    assertThat(p.matcher("5").matches(), is(false));
  }

  // --- Scripts/blocks: confirmed NOT implemented (no Source.Script/Source.Block enum entries
  // exist in NamedCharClass.java at all) -- see remaining_work.md. Document the actual (throwing)
  // behavior rather than skip or assert something false.

  @Test
  public void script_isNotImplemented_throwsUnknownClass() {
    org.junit.Assert.assertThrows(
        PatternSyntaxException.class, () -> Ll1Pattern.compile("\\p{IsGreek}"));
  }

  @Test
  public void script_equalsForm_isNotImplemented_throwsUnknownClass() {
    org.junit.Assert.assertThrows(
        PatternSyntaxException.class, () -> Ll1Pattern.compile("\\p{script=Greek}"));
  }

  @Test
  public void block_isNotImplemented_throwsUnknownClass() {
    org.junit.Assert.assertThrows(
        PatternSyntaxException.class, () -> Ll1Pattern.compile("\\p{InGreek}"));
  }

  @Test
  public void block_equalsForm_isNotImplemented_throwsUnknownClass() {
    org.junit.Assert.assertThrows(
        PatternSyntaxException.class, () -> Ll1Pattern.compile("\\p{block=Greek}"));
  }

  @Test
  public void unknownClassName_throwsWithTheNameActuallyTyped() {
    // "PosixDigit" is not any name real java.util.regex or this project's own syntax accepts (it
    // used to be an internal-only NamedCharClass identifier here, before NamedCharClass.Digit was
    // made to answer to both the `none` and `is` prefixes directly -- see its doc). Kept as a
    // regression test that an unrecognized name is simply rejected, and that the exception message
    // echoes what was actually typed rather than any internal name translation.
    PatternSyntaxException e =
        org.junit.Assert.assertThrows(
            PatternSyntaxException.class, () -> Ll1Pattern.compile("\\p{PosixDigit}"));
    assertThat(e.getMessage().contains("PosixDigit"), is(true));
  }
}

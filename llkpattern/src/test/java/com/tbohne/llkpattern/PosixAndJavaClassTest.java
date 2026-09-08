package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * All 13 POSIX classes (NamedCharClass.java's Source.POSIX entries) and every
 * java.lang.Character-method-backed class (Source.Java entries), enumerated directly from
 * NamedCharClass.java rather than from memory/docs per remaining_work.md's instruction. Each test
 * exercises real matches() against a member and a non-member.
 */
@RunWith(JUnit4.class)
public class PosixAndJavaClassTest {
  // --- POSIX (exactly 13: Lower, Upper, ASCII, Alpha, Digit, Alnum, Punct, Graph, Print, Blank,
  // Cntrl, XDigit, Space) ---

  /**
   * Every POSIX class except ASCII itself is ASCII-only by default and widens to full-Unicode
   * only under UNICODE_CHARACTER_CLASS (verified against real java.util.regex for all 12 -- see
   * individual test methods below for the exact non-ASCII exemplar chosen per class). This helper
   * asserts that shape for one non-ASCII member: doesn't match by default, does match under the
   * flag.
   */
  private static void assertAsciiByDefaultWidensUnderUnicodeCharacterClass(
      String posixName, String nonAsciiMember) {
    assertThat(
        Ll1Pattern.compile("\\p{" + posixName + "}").matcher(nonAsciiMember).matches(), is(false));
    assertThat(
        Ll1Pattern.compile("\\p{" + posixName + "}", Ll1Pattern.UNICODE_CHARACTER_CLASS)
            .matcher(nonAsciiMember)
            .matches(),
        is(true));
  }

  @Test
  public void posix_lower() {
    assertThat(Ll1Pattern.compile("\\p{Lower}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Lower}").matcher("A").matches(), is(false));
    // LATIN SMALL LETTER A WITH GRAVE (U+00E0): lowercase but not ASCII.
    assertAsciiByDefaultWidensUnderUnicodeCharacterClass("Lower", "\u00e0");
  }

  @Test
  public void posix_upper() {
    assertThat(Ll1Pattern.compile("\\p{Upper}").matcher("A").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Upper}").matcher("a").matches(), is(false));
    // LATIN CAPITAL LETTER A WITH GRAVE (U+00C0): uppercase but not ASCII.
    assertAsciiByDefaultWidensUnderUnicodeCharacterClass("Upper", "\u00c0");
  }

  @Test
  public void posix_ascii() {
    // Unlike every other POSIX class, \p{ASCII} never widens under UNICODE_CHARACTER_CLASS --
    // it's defined as the ASCII range itself, so there's nothing for the flag to widen it to.
    // Verified against real java.util.regex.
    assertThat(Ll1Pattern.compile("\\p{ASCII}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{ASCII}").matcher("\u00e9").matches(), is(false));
    assertThat(
        Ll1Pattern.compile("\\p{ASCII}", Ll1Pattern.UNICODE_CHARACTER_CLASS)
            .matcher("\u00e9")
            .matches(),
        is(false));
  }

  @Test
  public void posix_alpha() {
    assertThat(Ll1Pattern.compile("\\p{Alpha}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Alpha}").matcher("5").matches(), is(false));
    assertAsciiByDefaultWidensUnderUnicodeCharacterClass("Alpha", "\u00e0");
  }

  @Test
  public void posix_digit() {
    // Digit is the one NamedCharClass reachable under two different prefixes (see its own doc):
    // bare "\p{Digit}" (prefix `none`) is ASCII-default and only widens to full-Unicode under
    // UNICODE_CHARACTER_CLASS, while "\p{IsDigit}" (prefix `is`, see java_isDigit_alwaysUnicode
    // below) is always full-Unicode regardless of the flag.
    assertThat(Ll1Pattern.compile("\\p{Digit}").matcher("5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Digit}").matcher("a").matches(), is(false));
    // ARABIC-INDIC DIGIT FIVE (U+0665): not ASCII, so only matches under UNICODE_CHARACTER_CLASS
    // -- verified against real java.util.regex, which draws exactly this same line.
    assertThat(Ll1Pattern.compile("\\p{Digit}").matcher("\u0665").matches(), is(false));
    assertThat(
        Ll1Pattern.compile("\\p{Digit}", Ll1Pattern.UNICODE_CHARACTER_CLASS)
            .matcher("\u0665")
            .matches(),
        is(true));
  }

  @Test
  public void java_isDigit_alwaysUnicode() {
    // Unlike bare \p{Digit} above, \p{IsDigit} never honors UNICODE_CHARACTER_CLASS -- verified
    // against real java.util.regex.
    assertThat(Ll1Pattern.compile("\\p{IsDigit}").matcher("\u0665").matches(), is(true));
  }

  @Test
  public void posix_alnum() {
    assertThat(Ll1Pattern.compile("\\p{Alnum}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Alnum}").matcher("5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Alnum}").matcher("_").matches(), is(false));
    // Bug fix (2026-09-07): Alnum used to be built via a single-RangeSet constructor call
    // (ascii == unicode), so it always matched the full-Unicode alphanumeric set regardless of
    // UNICODE_CHARACTER_CLASS -- found by adding this assertion. Verified against real
    // java.util.regex before fixing.
    assertAsciiByDefaultWidensUnderUnicodeCharacterClass("Alnum", "\u0665");
  }

  @Test
  public void posix_punct() {
    assertThat(Ll1Pattern.compile("\\p{Punct}").matcher("!").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Punct}").matcher("a").matches(), is(false));
    // INVERTED EXCLAMATION MARK (U+00A1): punctuation but not ASCII.
    assertAsciiByDefaultWidensUnderUnicodeCharacterClass("Punct", "\u00a1");
  }

  @Test
  public void posix_graph() {
    assertThat(Ll1Pattern.compile("\\p{Graph}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Graph}").matcher(" ").matches(), is(false));
    assertAsciiByDefaultWidensUnderUnicodeCharacterClass("Graph", "\u00e0");
  }

  @Test
  public void posix_print() {
    assertThat(Ll1Pattern.compile("\\p{Print}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Print}").matcher("\u0001").matches(), is(false));
    assertAsciiByDefaultWidensUnderUnicodeCharacterClass("Print", "\u00e0");
  }

  @Test
  public void posix_blank() {
    assertThat(Ll1Pattern.compile("\\p{Blank}").matcher(" ").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Blank}").matcher("\n").matches(), is(false));
    // NO-BREAK SPACE (U+00A0): blank but not ASCII.
    assertAsciiByDefaultWidensUnderUnicodeCharacterClass("Blank", "\u00a0");
  }

  @Test
  public void posix_cntrl() {
    assertThat(Ll1Pattern.compile("\\p{Cntrl}").matcher("\u0001").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Cntrl}").matcher("a").matches(), is(false));
    // NEXT LINE (U+0085): a control character but not ASCII.
    assertAsciiByDefaultWidensUnderUnicodeCharacterClass("Cntrl", "\u0085");
  }

  @Test
  public void posix_xdigit() {
    assertThat(Ll1Pattern.compile("\\p{XDigit}").matcher("f").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{XDigit}").matcher("g").matches(), is(false));
    // Bug fix (2026-09-07): XDigit used to be built via a single-RangeSet constructor call
    // (ascii == unicode, always the fixed a-f/A-F/0-9-plus-fullwidth set), so
    // UNICODE_CHARACTER_CLASS was ignored entirely. Separately, that fixed set was also
    // incomplete: real java.util.regex's widened XDigit is `Character.digit(cp, 16) != -1`, which
    // accepts *any* Unicode decimal digit (numeric value 0-9, always below radix 16), not just the
    // literal Unicode Hex_Digit property -- verified against real java.util.regex with DEVANAGARI
    // DIGIT ZERO (U+0966, decimal value 0, not itself a Hex_Digit-property character).
    assertAsciiByDefaultWidensUnderUnicodeCharacterClass("XDigit", "\u0966");
  }

  @Test
  public void posix_space() {
    assertThat(Ll1Pattern.compile("\\p{Space}").matcher(" ").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Space}").matcher("a").matches(), is(false));
    assertAsciiByDefaultWidensUnderUnicodeCharacterClass("Space", "\u00a0");
  }

  // --- java.lang.Character-method-backed classes (Source.Java entries) ---

  /**
   * Unlike the POSIX classes above, every "java"-prefixed class is a direct java.lang.Character
   * method result -- always the same regardless of UNICODE_CHARACTER_CLASS (verified against real
   * java.util.regex for each class below via its own non-ASCII member; this helper just avoids
   * repeating the two assertLl1Pattern-with-and-without-the-flag calls at each call site).
   */
  private static void assertJavaPrefixIgnoresUnicodeCharacterClassFlag(
      String javaClassName, String member) {
    assertThat(Ll1Pattern.compile("\\p{" + javaClassName + "}").matcher(member).matches(), is(true));
    assertThat(
        Ll1Pattern.compile("\\p{" + javaClassName + "}", Ll1Pattern.UNICODE_CHARACTER_CLASS)
            .matcher(member)
            .matches(),
        is(true));
  }

  @Test
  public void java_javaLowerCase() {
    assertThat(Ll1Pattern.compile("\\p{javaLowerCase}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaLowerCase}").matcher("A").matches(), is(false));
    // LATIN SMALL LETTER A WITH GRAVE (U+00E0): not ASCII, but the flag makes no difference here.
    assertJavaPrefixIgnoresUnicodeCharacterClassFlag("javaLowerCase", "\u00e0");
  }

  @Test
  public void java_javaUpperCase() {
    assertThat(Ll1Pattern.compile("\\p{javaUpperCase}").matcher("A").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaUpperCase}").matcher("a").matches(), is(false));
    assertJavaPrefixIgnoresUnicodeCharacterClassFlag("javaUpperCase", "\u00c0");
  }

  @Test
  public void java_javaDigit() {
    assertThat(Ll1Pattern.compile("\\p{javaDigit}").matcher("5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaDigit}").matcher("a").matches(), is(false));
    // Unlike bare POSIX \p{Digit} (see posix_digit() above), \p{javaDigit} is always full-Unicode.
    assertJavaPrefixIgnoresUnicodeCharacterClassFlag("javaDigit", "\u0665");
  }

  @Test
  public void java_javaLetter() {
    assertThat(Ll1Pattern.compile("\\p{javaLetter}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaLetter}").matcher("5").matches(), is(false));
  }

  @Test
  public void java_javaLetterOrDigit() {
    assertThat(Ll1Pattern.compile("\\p{javaLetterOrDigit}").matcher("5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaLetterOrDigit}").matcher("_").matches(), is(false));
  }

  @Test
  public void java_javaAlphabetic() {
    assertThat(Ll1Pattern.compile("\\p{javaAlphabetic}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaAlphabetic}").matcher("5").matches(), is(false));
    assertJavaPrefixIgnoresUnicodeCharacterClassFlag("javaAlphabetic", "\u00e0");
  }

  @Test
  public void java_javaSpaceChar() {
    assertThat(Ll1Pattern.compile("\\p{javaSpaceChar}").matcher(" ").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaSpaceChar}").matcher("a").matches(), is(false));
    // NO-BREAK SPACE (U+00A0): not ASCII, but the flag makes no difference here.
    assertJavaPrefixIgnoresUnicodeCharacterClassFlag("javaSpaceChar", "\u00a0");
  }

  @Test
  public void java_javaWhitespace() {
    assertThat(Ll1Pattern.compile("\\p{javaWhitespace}").matcher("\t").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaWhitespace}").matcher("a").matches(), is(false));
    // LINE SEPARATOR (U+2028): not ASCII, but the flag makes no difference here.
    assertJavaPrefixIgnoresUnicodeCharacterClassFlag("javaWhitespace", "\u2028");
  }

  @Test
  public void java_javaISOControl() {
    assertThat(Ll1Pattern.compile("\\p{javaISOControl}").matcher("\u0001").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaISOControl}").matcher("a").matches(), is(false));
    // NEXT LINE (U+0085): not ASCII, but the flag makes no difference here.
    assertJavaPrefixIgnoresUnicodeCharacterClassFlag("javaISOControl", "\u0085");
  }

  @Test
  public void java_javaMirrored() {
    assertThat(Ll1Pattern.compile("\\p{javaMirrored}").matcher("(").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaMirrored}").matcher("a").matches(), is(false));
    // MATHEMATICAL LEFT ANGLE BRACKET (U+27E8): not ASCII, but the flag makes no difference here.
    assertJavaPrefixIgnoresUnicodeCharacterClassFlag("javaMirrored", "\u27e8");
  }

  @Test
  public void java_javaDefined() {
    assertThat(Ll1Pattern.compile("\\p{javaDefined}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaDefined}").matcher("\uFFFF").matches(), is(false));
  }

  @Test
  public void java_javaValidCodePoint() {
    assertThat(Ll1Pattern.compile("\\p{javaValidCodePoint}").matcher("a").matches(), is(true));
  }

  @Test
  public void java_javaBmpCodePoint() {
    assertThat(Ll1Pattern.compile("\\p{javaBmpCodePoint}").matcher("a").matches(), is(true));
  }

  @Test
  public void java_javaSupplementaryCodePoint() {
    Ll1Pattern p = Ll1Pattern.compile("\\p{javaSupplementaryCodePoint}");
    assertThat(p.matcher(new String(Character.toChars(0x1F4A9))).matches(), is(true));
    assertThat(p.matcher("a").matches(), is(false));
  }

  @Test
  public void java_javaTitleCase() {
    // U+01C5 LATIN CAPITAL LETTER D WITH SMALL LETTER Z WITH CARON is a titlecase letter.
    assertThat(Ll1Pattern.compile("\\p{javaTitleCase}").matcher("\u01C5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaTitleCase}").matcher("a").matches(), is(false));
    assertJavaPrefixIgnoresUnicodeCharacterClassFlag("javaTitleCase", "\u01C5");
  }

  @Test
  public void java_javaIdeographic() {
    assertThat(Ll1Pattern.compile("\\p{javaIdeographic}").matcher("\u4E2D").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaIdeographic}").matcher("a").matches(), is(false));
    assertJavaPrefixIgnoresUnicodeCharacterClassFlag("javaIdeographic", "\u4E2D");
  }

  @Test
  public void java_javaJavaIdentifierStart() {
    assertThat(Ll1Pattern.compile("\\p{javaJavaIdentifierStart}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaJavaIdentifierStart}").matcher("5").matches(), is(false));
  }

  @Test
  public void java_javaJavaIdentifierPart() {
    assertThat(Ll1Pattern.compile("\\p{javaJavaIdentifierPart}").matcher("5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaJavaIdentifierPart}").matcher(" ").matches(), is(false));
  }

  @Test
  public void java_javaUnicodeIdentifierStart() {
    assertThat(
        Ll1Pattern.compile("\\p{javaUnicodeIdentifierStart}").matcher("a").matches(), is(true));
    assertThat(
        Ll1Pattern.compile("\\p{javaUnicodeIdentifierStart}").matcher("5").matches(), is(false));
  }

  @Test
  public void java_javaUnicodeIdentifierPart() {
    assertThat(
        Ll1Pattern.compile("\\p{javaUnicodeIdentifierPart}").matcher("5").matches(), is(true));
    assertThat(
        Ll1Pattern.compile("\\p{javaUnicodeIdentifierPart}").matcher(" ").matches(), is(false));
  }

  @Test
  public void java_javaIdentifierIgnorable() {
    assertThat(
        Ll1Pattern.compile("\\p{javaIdentifierIgnorable}").matcher("\u0000").matches(), is(true));
    assertThat(
        Ll1Pattern.compile("\\p{javaIdentifierIgnorable}").matcher("a").matches(), is(false));
  }
}

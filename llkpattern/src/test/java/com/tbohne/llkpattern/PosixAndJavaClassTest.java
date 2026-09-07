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

  @Test
  public void posix_lower() {
    assertThat(Ll1Pattern.compile("\\p{Lower}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Lower}").matcher("A").matches(), is(false));
  }

  @Test
  public void posix_upper() {
    assertThat(Ll1Pattern.compile("\\p{Upper}").matcher("A").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Upper}").matcher("a").matches(), is(false));
  }

  @Test
  public void posix_ascii() {
    assertThat(Ll1Pattern.compile("\\p{ASCII}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{ASCII}").matcher("\u00e9").matches(), is(false));
  }

  @Test
  public void posix_alpha() {
    assertThat(Ll1Pattern.compile("\\p{Alpha}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Alpha}").matcher("5").matches(), is(false));
  }

  @Test
  public void posix_digit() {
    // See NamedCharClass.PosixDigit's doc: bare "\p{Digit}" can't literally be the enum constant
    // "Digit" (already claimed by \p{IsDigit}, which is always full-Unicode) -- PatternParser
    // translates the name to "PosixDigit" instead, which defaults to ASCII only.
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
  public void posix_digit_internalNameNotExposed() {
    // "PosixDigit" is only our internal NamedCharClass identifier for bare \p{Digit} (see
    // posix_digit() above) -- it must not itself be accepted as pattern syntax. Verified real
    // java.util.regex also rejects it ("Unknown character property name {PosixDigit}").
    try {
      Ll1Pattern.compile("\\p{PosixDigit}");
      org.junit.Assert.fail("expected PatternSyntaxException");
    } catch (PatternSyntaxException expected) {
      assertThat(expected.getMessage().contains("PosixDigit"), is(true));
    }
  }

  @Test
  public void posix_alnum() {
    assertThat(Ll1Pattern.compile("\\p{Alnum}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Alnum}").matcher("5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Alnum}").matcher("_").matches(), is(false));
  }

  @Test
  public void posix_punct() {
    assertThat(Ll1Pattern.compile("\\p{Punct}").matcher("!").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Punct}").matcher("a").matches(), is(false));
  }

  @Test
  public void posix_graph() {
    assertThat(Ll1Pattern.compile("\\p{Graph}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Graph}").matcher(" ").matches(), is(false));
  }

  @Test
  public void posix_print() {
    assertThat(Ll1Pattern.compile("\\p{Print}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Print}").matcher("\u0001").matches(), is(false));
  }

  @Test
  public void posix_blank() {
    assertThat(Ll1Pattern.compile("\\p{Blank}").matcher(" ").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Blank}").matcher("\n").matches(), is(false));
  }

  @Test
  public void posix_cntrl() {
    assertThat(Ll1Pattern.compile("\\p{Cntrl}").matcher("\u0001").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Cntrl}").matcher("a").matches(), is(false));
  }

  @Test
  public void posix_xdigit() {
    assertThat(Ll1Pattern.compile("\\p{XDigit}").matcher("f").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{XDigit}").matcher("g").matches(), is(false));
  }

  @Test
  public void posix_space() {
    assertThat(Ll1Pattern.compile("\\p{Space}").matcher(" ").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{Space}").matcher("a").matches(), is(false));
  }

  // --- java.lang.Character-method-backed classes (Source.Java entries) ---

  @Test
  public void java_javaLowerCase() {
    assertThat(Ll1Pattern.compile("\\p{javaLowerCase}").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaLowerCase}").matcher("A").matches(), is(false));
  }

  @Test
  public void java_javaUpperCase() {
    assertThat(Ll1Pattern.compile("\\p{javaUpperCase}").matcher("A").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaUpperCase}").matcher("a").matches(), is(false));
  }

  @Test
  public void java_javaDigit() {
    assertThat(Ll1Pattern.compile("\\p{javaDigit}").matcher("5").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaDigit}").matcher("a").matches(), is(false));
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
  }

  @Test
  public void java_javaSpaceChar() {
    assertThat(Ll1Pattern.compile("\\p{javaSpaceChar}").matcher(" ").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaSpaceChar}").matcher("a").matches(), is(false));
  }

  @Test
  public void java_javaWhitespace() {
    assertThat(Ll1Pattern.compile("\\p{javaWhitespace}").matcher("\t").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaWhitespace}").matcher("a").matches(), is(false));
  }

  @Test
  public void java_javaISOControl() {
    assertThat(Ll1Pattern.compile("\\p{javaISOControl}").matcher("\u0001").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaISOControl}").matcher("a").matches(), is(false));
  }

  @Test
  public void java_javaMirrored() {
    assertThat(Ll1Pattern.compile("\\p{javaMirrored}").matcher("(").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaMirrored}").matcher("a").matches(), is(false));
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
  }

  @Test
  public void java_javaIdeographic() {
    assertThat(Ll1Pattern.compile("\\p{javaIdeographic}").matcher("\u4E2D").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\p{javaIdeographic}").matcher("a").matches(), is(false));
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

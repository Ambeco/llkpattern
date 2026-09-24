package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@code \X} (extended grapheme cluster, UAX #29) -- consumes one whole cluster per {@link
 * GraphemeCluster#nextBoundary}, a direct port of JDK 27's {@code
 * jdk.internal.util.regex.Grapheme#nextBoundary}. See design.md's own section for why {@code
 * \b{g}} (grapheme boundary) isn't implemented alongside it. Differential cases below are checked
 * directly against {@code java.util.regex} so a divergence from the JDK 27 baseline this project
 * pins to (see README's "Unicode data is currently pinned to JDK 27's tables") shows up as a test
 * failure rather than silently drifting, though the *installed* JDK running this suite may have
 * older/newer Unicode data -- see notes.md/remaining_work.md for the general caveat every other
 * Unicode-table-dependent test already carries.
 */
@RunWith(JUnit4.class)
public class GraphemeClusterTest {
  private static void assertSameAsJavaRegex(String pattern, String input) {
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(pattern).matcher(input);
    Matcher llk = Ll1Pattern.compile(pattern).matcher(input);
    boolean jdkMatches = jdk.matches();
    boolean llkMatches = llk.matches();
    assertThat("matches() for /" + pattern + "/ vs \"" + input + "\"", llkMatches, is(jdkMatches));
    if (jdkMatches) {
      assertThat("group() for /" + pattern + "/ vs \"" + input + "\"", llk.group(), is(jdk.group()));
    }
  }

  // --- Basic single-cluster consumption ---

  @Test
  public void singleAsciiCharacter() {
    assertThat(Ll1Pattern.compile("\\X").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\X").matcher("").matches(), is(false));
    assertThat(Ll1Pattern.compile("\\X").matcher("ab").matches(), is(false));
  }

  @Test
  public void baseCharacterPlusCombiningMarks() {
    // "e" + COMBINING ACUTE ACCENT (U+0301) + COMBINING GRAVE ACCENT (U+0300) is one cluster.
    String input = "e\u0301\u0300";
    assertThat(Ll1Pattern.compile("\\X").matcher(input).matches(), is(true));
    assertSameAsJavaRegex("\\X", input);
  }

  @Test
  public void crLfIsOneCluster() {
    assertThat(Ll1Pattern.compile("\\X").matcher("\r\n").matches(), is(true));
    assertSameAsJavaRegex("\\X", "\r\n");
    // But CR alone, or LF alone, is its own cluster -- a following control char starts a new one.
    assertThat(Ll1Pattern.compile("\\X\\X").matcher("\r\r").matches(), is(true));
  }

  @Test
  public void supplementaryCodePointIsOneCluster() {
    String input = SupplementaryChars.A; // a single non-BMP code point, two chars.
    assertThat(Ll1Pattern.compile("\\X").matcher(input).matches(), is(true));
    assertSameAsJavaRegex("\\X", input);
  }

  @Test
  public void hangulSyllableSequence() {
    // L V T jamo compose into one cluster, same as the precomposed LVT syllable.
    String jamo = "\u1100\u1161\u11A8"; // L V T
    assertThat(Ll1Pattern.compile("\\X").matcher(jamo).matches(), is(true));
    assertSameAsJavaRegex("\\X", jamo);
    String precomposed = "\uAC01"; // LVT syllable (U+AC01)
    assertThat(Ll1Pattern.compile("\\X").matcher(precomposed).matches(), is(true));
    assertSameAsJavaRegex("\\X", precomposed);
  }

  @Test
  public void regionalIndicatorPairIsOneFlagCluster() {
    String usFlag = "\uD83C\uDDFA\uD83C\uDDF8"; // U+1F1FA U+1F1F8 (US flag)
    assertThat(Ll1Pattern.compile("\\X").matcher(usFlag).matches(), is(true));
    assertSameAsJavaRegex("\\X", usFlag);
    // Two flags in a row is two clusters, not one.
    String twoFlags = usFlag + "\uD83C\uDDEC\uD83C\uDDE7"; // + GB flag
    assertThat(Ll1Pattern.compile("\\X\\X").matcher(twoFlags).matches(), is(true));
    assertThat(Ll1Pattern.compile("\\X").matcher(twoFlags).matches(), is(false));
    assertSameAsJavaRegex("\\X\\X", twoFlags);
  }

  @Test
  public void zwjEmojiSequenceIsOneCluster() {
    // Family emoji: MAN + ZWJ + WOMAN + ZWJ + GIRL, all one cluster (GB11).
    String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
    assertThat(Ll1Pattern.compile("\\X").matcher(family).matches(), is(true));
    assertSameAsJavaRegex("\\X", family);
  }

  @Test
  public void indicConjunct() {
    // Devanagari K + VIRAMA (linker) + SSA: one conjunct cluster (GB9c).
    String conjunct = "\u0915\u094D\u0937";
    assertThat(Ll1Pattern.compile("\\X").matcher(conjunct).matches(), is(true));
    assertSameAsJavaRegex("\\X", conjunct);
  }

  @Test
  public void loneSurrogateIsItsOwnCluster() {
    String loneHigh = "\uD800a";
    assertThat(Ll1Pattern.compile("\\X\\X").matcher(loneHigh).matches(), is(true));
    assertSameAsJavaRegex("\\X\\X", loneHigh);
  }

  // --- find()/lookingAt(), and quantifiers ---

  @Test
  public void findScansClusterByCluster() {
    Matcher m = Ll1Pattern.compile("\\X").matcher("a" + "e\u0301" + "b");
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("a"));
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("e\u0301"));
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("b"));
    assertThat(m.find(), is(false));
  }

  @Test
  public void quantified() {
    assertThat(Ll1Pattern.compile("\\X+").matcher("e\u0301e\u0300e").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\X{2}").matcher("e\u0301" + "a").matches(), is(true));
    assertThat(Ll1Pattern.compile("\\X*").matcher("").matches(), is(true));
  }

  @Test
  public void hitEnd_setWhenNoInputLeft() {
    Matcher m = Ll1Pattern.compile("\\X").matcher("");
    assertThat(m.lookingAt(), is(false));
    assertThat(m.hitEnd(), is(true));
  }

  // --- Ambiguity: \X claims every code point explicitly, so it can never coexist as a sibling ---

  @Test
  public void ambiguousAsUnionBranch() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a|\\X"));
  }

  @Test
  public void ambiguousAsLoopBodyBeforeTrailingLiteral() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("\\X*a"));
  }

  // --- Not usable where a single code point is required ---

  @Test
  public void rejectedInLookbehind() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a(?<=\\X)"));
  }

  @Test
  public void notARepeatOfNothing() {
    // \X is a real atom, so a quantifier on it is fine, but a quantifier with nothing before it
    // still isn't.
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("*\\X"));
  }
}

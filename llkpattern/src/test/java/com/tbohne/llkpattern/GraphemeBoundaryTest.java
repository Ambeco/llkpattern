package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@code \b{g}} (grapheme boundary) -- see design.md's "Extended grapheme clusters" section for
 * why this needed its own bounded-backward-scan treatment (GB9c/GB11/GB12-13) rather than the
 * one-code-point {@code peekPrevious()} model {@code \b}/{@code \B} use. {@code \B{g}} is NOT
 * special syntax (matching java.util.regex -- see {@code MatcherApiTest#boundaryTypeSuffixIsRejected}).
 */
@RunWith(JUnit4.class)
public class GraphemeBoundaryTest {
  private static void assertSameAsJavaRegex(String pattern, String input) {
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(pattern).matcher(input);
    Matcher llk = Ll1Pattern.compile(pattern).matcher(input);
    assertThat("find() count for /" + pattern + "/ vs \"" + input + "\"",
        countMatches(llk), is(countMatches(jdk)));
  }

  private static int countMatches(Matcher m) {
    int n = 0;
    while (m.find()) n++;
    return n;
  }

  private static int countMatches(java.util.regex.Matcher m) {
    int n = 0;
    while (m.find()) n++;
    return n;
  }

  // --- Ordinary pairwise cases (GB3-GB9b) ---

  @Test
  public void holdsBetweenOrdinaryLetters() {
    assertThat(Ll1Pattern.compile("a\\b{g}b").matcher("ab").matches(), is(true));
  }

  @Test
  public void doesNotHoldWithinCombiningMarkCluster() {
    assertThat(Ll1Pattern.compile("e\\b{g}\u0301").matcher("e\u0301").matches(), is(false));
    assertThat(Ll1Pattern.compile("e\u0301\\b{g}x").matcher("e\u0301x").matches(), is(true));
  }

  @Test
  public void doesNotHoldWithinCrLf() {
    assertThat(Ll1Pattern.compile("\r\\b{g}\n").matcher("\r\n").matches(), is(false));
    assertThat(Ll1Pattern.compile("\r\r\\b{g}\r").matcher("\r\r").matches(), is(false));
  }

  @Test
  public void holdsAtTrueStartAndEnd() {
    Matcher m = Ll1Pattern.compile("\\b{g}").matcher("");
    assertThat(m.lookingAt(), is(true));
    assertThat(m.hitEnd(), is(true));
    assertThat(m.requireEnd(), is(true));
  }

  // --- GB12/13: regional indicator parity -- the worked example from design.md: the SAME adjacent
  // (RI, RI) pair is a boundary between two flags but not within one. ---

  @Test
  public void regionalIndicatorParity() {
    String usFlag = "\uD83C\uDDFA\uD83C\uDDF8"; // US flag: RI RI
    String gbFlag = "\uD83C\uDDEC\uD83C\uDDE7"; // GB flag: RI RI
    String twoFlags = usFlag + gbFlag; // RI RI RI RI
    // No boundary within a single flag (between its two RIs; "." consumes exactly the first RI --
    // \X would consume the WHOLE flag, leaving no mid-flag position left to check).
    assertThat(Ll1Pattern.compile(".\\b{g}.").matcher(usFlag).matches(), is(false));
    // A boundary DOES exist between the two flags (after an even number of RIs: 2).
    assertThat(Ll1Pattern.compile("\\X\\b{g}\\X").matcher(twoFlags).matches(), is(true));
    assertSameAsJavaRegex("\\b{g}", twoFlags);
  }

  // --- GB11: ZWJ emoji sequences ---

  @Test
  public void zwjEmojiSequenceHasNoInteriorBoundary() {
    // MAN + ZWJ + WOMAN: one cluster, no grapheme boundary between ZWJ and WOMAN.
    String manZwjWoman = "\uD83D\uDC68\u200D\uD83D\uDC69";
    assertThat(Ll1Pattern.compile("\\X").matcher(manZwjWoman).matches(), is(true));
    assertSameAsJavaRegex("\\b{g}", manZwjWoman);
  }

  @Test
  public void zwjNotAfterPictographicDoesBreak() {
    // ZWJ directly after a plain letter (not a pictograph) -- GB11 doesn't apply, ordinary GB9
    // rule (x ZWJ) still suppresses the break BEFORE the ZWJ, but nothing suppresses the break
    // AFTER it into a following pictograph the way GB11 would.
    String aZwjEmoji = "a\u200D\uD83D\uDC68";
    assertSameAsJavaRegex("\\b{g}", aZwjEmoji);
  }

  // --- GB9c: Indic conjunct break ---

  @Test
  public void indicConjunctHasNoInteriorBoundary() {
    // Devanagari K + VIRAMA (linker) + SSA -- one conjunct cluster.
    String conjunct = "\u0915\u094D\u0937";
    assertThat(Ll1Pattern.compile("\\X").matcher(conjunct).matches(), is(true));
    assertSameAsJavaRegex("\\b{g}", conjunct);
  }

  @Test
  public void consonantWithoutLinkerDoesBreak() {
    // Consonant + Extend (no Linker) + Consonant: GB9c doesn't apply (no Linker in the chain), so
    // the ordinary pairwise rule (Extend x Other) breaks normally.
    String noLinker = "\u0915\u094B\u0915"; // KA + vowel sign O (Extend, not Linker) + KA
    assertSameAsJavaRegex("\\b{g}", noLinker);
  }

  // --- Differential matrix over find() ---

  @Test
  public void differentialAgainstJavaRegex() {
    String[] inputs = {
      "abc",
      "e\u0301e\u0300",
      "\r\n\r\n",
      "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDEC\uD83C\uDDE7",
      "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
      "\u0915\u094D\u0937a",
      "\uAC00\u1161\u11A8", // precomposed-adjacent Hangul jamo
      "\uD800a", // lone high surrogate
    };
    for (String input : inputs) {
      assertSameAsJavaRegex("\\b{g}", input);
    }
  }

  // --- Loop ambiguity: conservative over-rejection, same tradeoff \X itself already accepts ---

  @Test
  public void ambiguousAfterLoopWithKnownLastChar() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a+\\b{g}b"));
  }

  @Test
  public void reluctantLoopHasNoCompileTimeAmbiguityCheck() {
    // Reluctant loops are exempt from the greedy-only checkAssertions ambiguity check -- resolved
    // at match time instead, same as \B/lookbehind.
    assertThat(Ll1Pattern.compile("a+?\\b{g}b").matcher("ab").matches(), is(true));
  }

  // --- Transparent/opaque bounds ---

  @Test
  public void opaqueBounds_regionEdgeIsABoundary() {
    Matcher m = Ll1Pattern.compile("\\b{g}").matcher("e\u0301e\u0300");
    m.region(1, 3); // splits the first cluster (e + combining acute) in the middle
    assertThat(m.lookingAt(), is(true)); // region start is always treated as a boundary
  }

  @Test
  public void transparentBounds_seesOutsideRegion() {
    Matcher m = Ll1Pattern.compile("\\b{g}").matcher("e\u0301x");
    m.region(1, 3).useTransparentBounds(true);
    // Position 1 is mid-cluster (between 'e' and the combining mark) even though it's the region
    // start -- transparent bounds let \b{g} see the real 'e' just outside the region.
    assertThat(m.lookingAt(), is(false));
  }
}

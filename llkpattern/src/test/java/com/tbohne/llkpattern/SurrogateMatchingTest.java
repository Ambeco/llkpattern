package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests that {@code Ll1Pattern} treats a valid UTF-16 surrogate pair as one atomic Unicode code
 * point during matching, the same way {@code java.util.regex} does, while still matching a
 * genuinely lone (unpaired) surrogate as its own character.
 *
 * <p>This is NOT a case where llk deliberately deviates from {@code java.util.regex} -- it was a
 * real bug (see {@link Matcher#find(int)}, and {@code PatternConstruct.ComplexCharacter#validRanges()})
 * fixed 2026-09-06. Verified directly against the installed JDK before fixing, since two different
 * AI assistants disagreed about the exact rule (one claimed a {@code x-brace} code-point escape
 * range behaves differently from a 16-bit escape range, a disjunction, or {@code p Cs} here; the
 * other cited JDK-8149446, a "Won't Fix" bug about matching into a valid pair, as still-current
 * behavior).
 * Neither was right: on the installed JDK, all four forms below behave identically. The rule is
 * simply "a valid surrogate pair is one code point and is never split; a lone surrogate is matched
 * on its own", with no escape-form-specific exception. See remaining_work.md's surrogate-matching
 * entry for the fuller writeup, including the exact repro commands used to verify this against a
 * real JDK.
 */
@RunWith(JUnit4.class)
public class SurrogateMatchingTest {
  // U+10000 (LINEAR B SYLLABLE B008 A), the exact code point from JDK-8149446's own repro.
  private static final String VALID_PAIR = "𐀀";
  private static final String LONE_LOW_SURROGATE = "\uDC00";
  private static final String LONE_HIGH_SURROGATE = "\uD800";

  private static void assertFind(String pattern, String input, boolean expected) {
    assertThat(
        pattern + " vs " + describe(input),
        Ll1Pattern.compile(pattern).matcher(input).find(),
        is(expected));
  }

  private static String describe(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      sb.append(String.format("U+%04X ", (int) s.charAt(i)));
    }
    return sb.toString().trim();
  }

  // --- [\uDC00-\uDFFF]-style 16-bit escape range ---

  @Test
  public void u16Range_matchesLoneLowSurrogate() {
    assertFind("[\\udc00-\\udfff]", LONE_LOW_SURROGATE, true);
  }

  @Test
  public void u16Range_doesNotSplitAValidPair() {
    assertFind("[\\udc00-\\udfff]", VALID_PAIR, false);
  }

  // --- [\x{dc00}-\x{dfff}]-style code-point escape range: same rule, not a special case ---

  @Test
  public void xBraceRange_matchesLoneLowSurrogate() {
    assertFind("[\\x{dc00}-\\x{dfff}]", LONE_LOW_SURROGATE, true);
  }

  @Test
  public void xBraceRange_doesNotSplitAValidPair() {
    assertFind("[\\x{dc00}-\\x{dfff}]", VALID_PAIR, false);
  }

  // --- disjunction (individual chars, not a contiguous range): same rule again ---

  @Test
  public void disjunction_matchesLoneLowSurrogate() {
    assertFind("[\\udc00\\udc01\\udc02]", LONE_LOW_SURROGATE, true);
  }

  @Test
  public void disjunction_doesNotSplitAValidPair() {
    assertFind("[\\udc00\\udc01\\udc02]", VALID_PAIR, false);
  }

  // --- \p{Cs} (the Unicode "Surrogate" general category): same rule again ---

  @Test
  public void unicodeCsCategory_matchesLoneLowSurrogate() {
    assertFind("\\p{Cs}", LONE_LOW_SURROGATE, true);
  }

  @Test
  public void unicodeCsCategory_doesNotSplitAValidPair() {
    assertFind("\\p{Cs}", VALID_PAIR, false);
  }

  // --- a lone HIGH surrogate is symmetric with the lone-low cases above ---

  @Test
  public void u16Range_matchesLoneHighSurrogate() {
    assertFind("[\\ud800-\\udbff]", LONE_HIGH_SURROGATE, true);
  }

  @Test
  public void u16Range_doesNotMatchHighHalfOfAValidPair() {
    assertFind("[\\ud800-\\udbff]", VALID_PAIR, false);
  }

  // --- out-of-order surrogates (low then high) never form a pair, so both halves are "lone" ---

  @Test
  public void outOfOrderSurrogates_bothHalvesMatchAsLone() {
    String outOfOrder = LONE_LOW_SURROGATE + LONE_HIGH_SURROGATE; // \uDC00\uD800 -- not a pair
    assertFind("[\\udc00-\\udfff]", outOfOrder, true);
    assertFind("[\\ud800-\\udbff]", outOfOrder, true);
  }

  // --- find() must not report a match starting mid-pair even when a longer match exists nearby ---

  @Test
  public void find_skipsCandidateStartsInsideAValidPair() {
    // "x" + validPair + "y": a match can only start at the 'x', the pair as a whole, or the 'y' --
    // never at index 1 (the pair's low half on its own).
    String input = "x" + VALID_PAIR + "y";
    assertThat(Ll1Pattern.compile("[\\udc00-\\udfff]").matcher(input).find(), is(false));
  }

  // --- PLAIN LITERAL TEXT (not a character class/escape range) on one side, a code-unit-only
  // mismatch on the other. Every case above uses a bracket class or \p{Cs} as the discriminating
  // construct -- deliberately scoped to just the surrogate range, so it could never accidentally
  // pass by comparing individual UTF-16 chars instead of real code points (a lone surrogate and a
  // valid pair's high half are different char VALUES too, e.g. \uDC00 vs \uD800, so a char-level
  // comparator would already reject those correctly). A plain literal supplementary character is
  // the case that could actually hide a char-vs-code-point bug: PatternParser reads it as ONE
  // ComplexCharacter/LiteralString with the real code point value, but if matching ever compared
  // char-by-char instead of by code point, a literal pattern's *first UTF-16 char* (\uD800, the
  // valid pair's own high surrogate) would spuriously equal a lone \uD800 in the input, or vice
  // versa -- exactly what these cases below would catch and the ones above structurally can't.
  // Verified directly against the installed JDK first (see this file's own class doc for why that
  // verification step matters here) -- results confirmed identical to java.util.regex for every
  // case below.

  @Test
  public void literalSupplementaryPattern_doesNotMatchLoneHighSurrogateInput() {
    assertFind(VALID_PAIR, LONE_HIGH_SURROGATE, false);
  }

  @Test
  public void literalSupplementaryPattern_doesNotMatchLoneLowSurrogateInput() {
    assertFind(VALID_PAIR, LONE_LOW_SURROGATE, false);
  }

  @Test
  public void loneHighSurrogatePattern_doesNotMatchIntoAValidPairInput() {
    // Must not find a "match" of the pair's own high half sitting inside it -- the pair is one
    // atomic code point, never split, even just for find()'s own candidate-start scanning.
    assertFind(LONE_HIGH_SURROGATE, VALID_PAIR, false);
  }

  @Test
  public void loneLowSurrogatePattern_doesNotMatchIntoAValidPairInput() {
    assertFind(LONE_LOW_SURROGATE, VALID_PAIR, false);
  }

  @Test
  public void literalSupplementaryPattern_matchesEquivalentSupplementaryInput() {
    // Sanity check alongside the mismatches above: matching code points on both sides still match.
    assertFind(VALID_PAIR, VALID_PAIR, true);
  }

  @Test
  public void literalSupplementaryPatternFollowedByLiteral_mismatchedInputStillRejected() {
    // Same mismatch as literalSupplementaryPattern_doesNotMatchLoneHighSurrogateInput, but with a
    // trailing literal on both sides -- exercises find()'s own multi-construct dispatch, not just
    // a single-construct pattern/input pair.
    assertFind(VALID_PAIR + "y", LONE_HIGH_SURROGATE + "y", false);
  }

  @Test
  public void loneHighSurrogatePatternFollowedByLiteral_validPairInputStillRejected() {
    assertFind(LONE_HIGH_SURROGATE + "y", VALID_PAIR + "y", false);
  }
}

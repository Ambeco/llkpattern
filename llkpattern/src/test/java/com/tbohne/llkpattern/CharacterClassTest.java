package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Character-class grammar coverage: literal sets, negation, ranges, nested unions/intersections.
 * Real match()/matches() calls, not structural-only assertions -- see notes.md.
 *
 * <p>Uses a run of 26 supplementary (astral) code points, U+10000-U+10019, as a stand-in for
 * a-z -- {@code A}/{@code B}/{@code C}/... below name them the same way the letters they replace
 * would, purely so each test below reads the same as its original ASCII version. {@code OUTSIDE}
 * stands in for 'A' (a code point outside the a-z range entirely) -- see
 * SupplementaryPatternTextTest for why exercising the parser's own lookahead across a
 * multi-code-unit character, inside bracket-expression syntax specifically here, matters.
 */
@RunWith(JUnit4.class)
public class CharacterClassTest {
  private static String letter(int offset) {
    return "\uD800" + (char) (0xDC00 + offset);
  }

  private static final String A = letter(0);
  private static final String B = letter(1);
  private static final String C = letter(2);
  private static final String D = letter(3);
  private static final String E = letter(4);
  private static final String H = letter(7);
  private static final String I = letter(8);
  private static final String M = letter(12);
  private static final String O = letter(14);
  private static final String P = letter(15);
  private static final String Q = letter(16);
  private static final String U = letter(20);
  private static final String Z = letter(25);
  // U+10400 (DESERET CAPITAL LETTER LONG A) -- outside the U+10000-U+10019 range above entirely,
  // same role 'A' (outside [a-z]) played in the original ASCII version of these tests.
  private static final String OUTSIDE = "𐐀";

  @Test
  public void simpleSet_matchesMember() {
    Ll1Pattern p = Ll1Pattern.compile("[" + A + B + C + "]");
    assertThat(p.matcher(A).matches(), is(true));
    assertThat(p.matcher(B).matches(), is(true));
    assertThat(p.matcher(D).matches(), is(false));
  }

  @Test
  public void negatedSet_excludesMembers() {
    Ll1Pattern p = Ll1Pattern.compile("[^" + A + B + C + "]");
    assertThat(p.matcher(A).matches(), is(false));
    assertThat(p.matcher(D).matches(), is(true));
  }

  @Test
  public void range_matchesWithinBounds() {
    Ll1Pattern p = Ll1Pattern.compile("[" + A + "-" + Z + "]");
    assertThat(p.matcher(M).matches(), is(true));
    assertThat(p.matcher(OUTSIDE).matches(), is(false));
  }

  @Test
  public void union_ofNestedBracketedClasses() {
    Ll1Pattern p = Ll1Pattern.compile("[" + A + "-" + C + "[" + P + "-" + Z + "]]");
    assertThat(p.matcher(B).matches(), is(true));
    assertThat(p.matcher(Q).matches(), is(true));
    assertThat(p.matcher(H).matches(), is(false));
  }

  @Test
  public void intersection_ofTwoRanges() {
    // "vowels" (A/E/I/O/U, i.e. offsets 0/4/8/14/20) intersected against the whole a-z range.
    Ll1Pattern p = Ll1Pattern.compile("[" + A + "-" + Z + "&&[" + A + E + I + O + U + "]]");
    assertThat(p.matcher(A).matches(), is(true));
    assertThat(p.matcher(E).matches(), is(true));
    assertThat(p.matcher(B).matches(), is(false));
  }

  @Test
  public void intersection_withNegatedOperand_excludesThatOperandFromTheOther() {
    // [a-z&&[^aeiou]]: consonants only -- the RHS operand is negated, not the whole intersection.
    Ll1Pattern p = Ll1Pattern.compile("[" + A + "-" + Z + "&&[^" + A + E + I + O + U + "]]");
    assertThat(p.matcher(B).matches(), is(true));
    assertThat(p.matcher(A).matches(), is(false));
    // Crucially, this must NOT behave like negating the whole intersection: OUTSIDE (outside a-z
    // entirely) must still be excluded, unlike [^[a-z&&[aeiou]]] below.
    assertThat(p.matcher(OUTSIDE).matches(), is(false));
  }

  @Test
  public void negationOfWholeIntersection_differsFromNegatedOperand() {
    // [^[a-z&&[aeiou]]]: negate the RESULT of the intersection (vowels), so everything that is
    // NOT a lowercase vowel matches -- including characters outside a-z entirely, unlike the
    // "negated operand" case above where non-a-z characters are still excluded.
    Ll1Pattern p = Ll1Pattern.compile("[^[" + A + "-" + Z + "&&[" + A + E + I + O + U + "]]]");
    assertThat(p.matcher(A).matches(), is(false)); // vowel: excluded by the negation
    assertThat(p.matcher(B).matches(), is(true)); // consonant: not a vowel, so included
    assertThat(p.matcher(OUTSIDE).matches(), is(true)); // outside a-z altogether: also included
  }

  @Test
  public void unbracketed_intersectionRhs_stillIntersects() {
    // java.util.regex treats "&&" as the intersection operator even without brackets around the
    // right-hand run of members -- verified against a real JDK, see remaining_work.md's "&&" FIXED
    // entry. Confirms this project's fix for that behaves the same way.
    Ll1Pattern p = Ll1Pattern.compile("[" + A + "-" + Z + "&&" + A + E + I + O + U + "]");
    assertThat(p.matcher(E).matches(), is(true));
    assertThat(p.matcher(B).matches(), is(false));
  }
}

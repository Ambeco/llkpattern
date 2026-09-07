package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Character-class grammar coverage: literal sets, negation, ranges, nested unions/intersections.
 * Real match()/matches() calls, not structural-only assertions -- see notes.md.
 */
@RunWith(JUnit4.class)
public class CharacterClassTest {
  @Test
  public void simpleSet_matchesMember() {
    Ll1Pattern p = Ll1Pattern.compile("[abc]");
    assertThat(p.matcher("a").matches(), is(true));
    assertThat(p.matcher("b").matches(), is(true));
    assertThat(p.matcher("d").matches(), is(false));
  }

  @Test
  public void negatedSet_excludesMembers() {
    Ll1Pattern p = Ll1Pattern.compile("[^abc]");
    assertThat(p.matcher("a").matches(), is(false));
    assertThat(p.matcher("d").matches(), is(true));
  }

  @Test
  public void range_matchesWithinBounds() {
    Ll1Pattern p = Ll1Pattern.compile("[a-z]");
    assertThat(p.matcher("m").matches(), is(true));
    assertThat(p.matcher("A").matches(), is(false));
  }

  @Test
  public void union_ofNestedBracketedClasses() {
    Ll1Pattern p = Ll1Pattern.compile("[a-c[p-z]]");
    assertThat(p.matcher("b").matches(), is(true));
    assertThat(p.matcher("q").matches(), is(true));
    assertThat(p.matcher("h").matches(), is(false));
  }

  @Test
  public void intersection_ofTwoRanges() {
    Ll1Pattern p = Ll1Pattern.compile("[a-z&&[aeiou]]");
    assertThat(p.matcher("a").matches(), is(true));
    assertThat(p.matcher("e").matches(), is(true));
    assertThat(p.matcher("b").matches(), is(false));
  }

  @Test
  public void intersection_withNegatedOperand_excludesThatOperandFromTheOther() {
    // [a-z&&[^aeiou]]: consonants only -- the RHS operand is negated, not the whole intersection.
    Ll1Pattern p = Ll1Pattern.compile("[a-z&&[^aeiou]]");
    assertThat(p.matcher("b").matches(), is(true));
    assertThat(p.matcher("a").matches(), is(false));
    // Crucially, this must NOT behave like negating the whole intersection: 'A' (outside [a-z]
    // entirely) must still be excluded, unlike [^[a-z&&[aeiou]]] below.
    assertThat(p.matcher("A").matches(), is(false));
  }

  @Test
  public void negationOfWholeIntersection_differsFromNegatedOperand() {
    // [^[a-z&&[aeiou]]]: negate the RESULT of the intersection (vowels), so everything that is
    // NOT a lowercase vowel matches -- including characters outside [a-z] entirely, unlike the
    // "negated operand" case above where non-[a-z] characters are still excluded.
    Ll1Pattern p = Ll1Pattern.compile("[^[a-z&&[aeiou]]]");
    assertThat(p.matcher("a").matches(), is(false)); // vowel: excluded by the negation
    assertThat(p.matcher("b").matches(), is(true)); // consonant: not a vowel, so included
    assertThat(p.matcher("A").matches(), is(true)); // outside [a-z] altogether: also included
  }

  @Test
  public void unbracketed_intersectionRhs_stillIntersects() {
    // java.util.regex treats "&&" as the intersection operator even without brackets around the
    // right-hand run of members -- verified against a real JDK, see remaining_work.md's "&&" FIXED
    // entry. Confirms this project's fix for that behaves the same way.
    Ll1Pattern p = Ll1Pattern.compile("[a-z&&aeiou]");
    assertThat(p.matcher("e").matches(), is(true));
    assertThat(p.matcher("b").matches(), is(false));
  }
}

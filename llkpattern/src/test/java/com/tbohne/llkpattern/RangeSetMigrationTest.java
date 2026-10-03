package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Regression coverage for the Guava {@code RangeSet} -> {@code CodePointMap} migration of
 * {@code ComplexCharacterPatternConstruct}/{@code containsFolded} (see notes.md's entry for that migration).
 * Targets the two specific failure modes an else-value-backed character class introduces that a
 * bounded Guava {@code RangeSet} couldn't: (1) a negated/DOTALL class's else-value fill wrongly
 * reporting the {@code -1} end-of-input sentinel as a member, and (2) an else-value-backed
 * "matches everything" class (DOTALL {@code .}) still participating correctly in compile-time
 * union-ambiguity detection instead of the map degenerating in some unexpected way.
 */
@RunWith(JUnit4.class)
public class RangeSetMigrationTest {
  @Test
  public void negatedClass_doesNotMatchAtEndOfInput() {
    // A negated class is an else-value map (see ComplexCharacterPatternConstruct#validRanges/containsFolded's
    // -1 guard) -- this would previously fail if -1 were ever treated as a "member" of the fill.
    Ll1Pattern p = Ll1Pattern.compile("a[^a]?");
    assertThat(p.matcher("a").matches(), is(true)); // the optional [^a] matches zero-width here
    assertThat(p.matcher("ab").matches(), is(true));
  }

  @Test
  public void dotAll_matchesEveryCharacterIncludingNewline() {
    Ll1Pattern p = Ll1Pattern.compile("a.b", Ll1Pattern.DOTALL);
    assertThat(p.matcher("a\nb").matches(), is(true));
    assertThat(p.matcher("a\rb").matches(), is(true));
  }

  @Test
  public void dotAll_atEndOfInput_doesNotMatch() {
    // "." (even DOTALL, an else-value "everything" map) must never treat end-of-input as a
    // character to consume.
    Ll1Pattern p = Ll1Pattern.compile(".", Ll1Pattern.DOTALL);
    assertThat(p.matcher("").matches(), is(false));
  }

  @Test
  public void union_withDotAllBranch_dotClaimsOnlyWhatItsSiblingsDont() {
    // DOTALL "." covers the whole domain but claims only "all other options": "a|." is "a|[^a]",
    // so it compiles and 'a' still goes to the first branch, without crashing while walking a branch
    // whose accept set is [0, MAX_CODE_POINT].
    Ll1Pattern all = Ll1Pattern.compile("a|.", Ll1Pattern.DOTALL);
    assertThat(all.matcher("a").matches(), is(true));
    assertThat(all.matcher("z").matches(), is(true));
    assertThat(all.matcher("\n").matches(), is(true));
    assertThat(all.matcher("").matches(), is(false));

    // Two "all other options" branches have nothing left to split, so that one IS ambiguous.
    try {
      Ll1Pattern.compile(".|.", Ll1Pattern.DOTALL);
      throw new AssertionError("expected an ambiguity PatternSyntaxException");
    } catch (PatternSyntaxException expected) {
      // expected
    }

    // "." excludes '\n' by default (no DOTALL): "\n|." still matches both.
    Ll1Pattern p = Ll1Pattern.compile("\\n|.");
    assertThat(p.matcher("\n").matches(), is(true));
    assertThat(p.matcher("z").matches(), is(true));
    // ... but a plain "a|." must not let "." swallow a line terminator.
    assertThat(Ll1Pattern.compile("a|.").matcher("\n").matches(), is(false));

    // A genuinely ambiguous union not involving "." must still be rejected too.
    try {
      Ll1Pattern.compile("a|[a-c]");
      throw new AssertionError("expected an ambiguity PatternSyntaxException");
    } catch (PatternSyntaxException expected) {
      // expected
    }
  }

  @Test
  public void intersectionAndNegationCombined_matchesJavaUtilRegex() {
    // [a-z&&[^aeiou]] -- exercises the intersect()/complement() combination directly (the "&&"
    // operator's RHS is itself a negated class).
    String regex = "[a-z&&[^aeiou]]+";
    Ll1Pattern actual = Ll1Pattern.compile(regex);
    Pattern expected = Pattern.compile(regex);
    for (String s : new String[] {"bcdfg", "aeiou", "xyz", "hello"}) {
      assertThat("mismatch for \"" + s + "\"", actual.matcher(s).matches(), is(expected.matcher(s).matches()));
    }
  }

  @Test
  public void wordBoundary_stillWorksAfterElseValueMigration() {
    // \b's compile-time optimization (WordBoundaryPatternConstruct.classify) now does subset/disjoint
    // checks against a CodePointMap<Boolean> instead of RangeSet#enclosesAll/complement.
    Ll1Pattern p = Ll1Pattern.compile("\\bfoo\\b");
    assertThat(p.matcher("foo").matches(), is(true));
    assertThat(p.matcher("a foo b").find(), is(true));
    assertThat(p.matcher("foobar").find(), is(false));
  }
}

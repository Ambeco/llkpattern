package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * A union branch (or loop part) that OPENS with a zero-width assertion is ambiguity-checked by the
 * code points it can really start with -- the first set of whatever follows the assertion -- not by
 * a catch-all: {@code a*\b-|a} and {@code \b[ab]c|a} both have a branch and a sibling that can
 * start with {@code a}, exactly like {@code a*-|a}, so all are compile-time errors. Before this
 * was fixed, the assertion-led branch was treated as a catch-all tail fallback that was never
 * compared with its siblings at all: the patterns compiled, and the sibling silently won ({@code
 * a*\b-|a} matched only {@code "a"} in {@code "a-"}, where {@code java.util.regex} matches {@code
 * "a-"}). See design.md's "Boundary matching" section.
 */
@RunWith(JUnit4.class)
public class UnionAssertionAmbiguityTest {
  private static void assertRejected(String pattern) {
    assertThrows(pattern, PatternSyntaxException.class, () -> Ll1Pattern.compile(pattern));
  }

  private static void assertAgreesWithJdk(String pattern, String input) {
    java.util.regex.Matcher expected = java.util.regex.Pattern.compile(pattern).matcher(input);
    Matcher actual = Ll1Pattern.compile(pattern).matcher(input);
    boolean found = expected.find();
    assertThat(pattern + " on " + input, actual.find(), is(found));
    if (found) {
      assertThat(pattern + " on " + input, actual.group(), is(expected.group()));
      assertThat(pattern + " on " + input, actual.start(), is(expected.start()));
    }
  }

  @Test
  public void nullableLoopThenAssertion_overlappingSibling_rejected() {
    assertRejected("a*-|a"); // control: no assertion, always rejected
    assertRejected("a*\\b-|a");
    assertRejected("a?\\b-|a");
    assertRejected("(a*\\b-)|a");
    assertRejected("(?:a*\\b-)+|a");
    assertRejected("(?:x|\\b-)|x");
  }

  @Test
  public void leadingAssertion_overlappingSibling_rejected_whicheverOrder() {
    assertRejected("\\b[ab]c|a");
    assertRejected("a|\\b[ab]c");
    assertRejected("\\ba|a");
    assertRejected("^a|a");
    assertRejected("(?m)^a|a");
    assertRejected("(?m)a$|a");
    assertRejected("(?<=x)a|a");
    assertRejected("\\b{g}a|a");
  }

  @Test
  public void loopBodyWithLeadingAssertion_overlappingSibling_stillRejected() {
    assertRejected("(?:a*\\b-|a)+");
    assertRejected("(?:a*\\b-|a)*");
  }

  @Test
  public void leadingAssertion_disjointSibling_stillCompilesAndMatchesLikeJdk() {
    String[][] cases = {
        {"\\b[ab]c|x", "ac"}, {"\\b[ab]c|x", "bc"}, {"\\b[ab]c|x", "x"}, {"\\b[ab]c|x", "zc"},
        {"\\ba|c", "a"}, {"\\ba|c", "c"}, {"\\ba|c", "ba"},
        {"(^a|b)c", "ac"}, {"(^a|b)c", "bc"}, {"(^a|b)c", "xac"},
        {"a*\\b-|c", "a-"}, {"a*\\b-|c", "aa-"}, {"a*\\b-|c", "c"}, {"a*\\b-|c", "-"},
        {"(?m)^a|b", "x\na"}, {"(?<=x)a|b", "xa"}, {"(?<=x)a|b", "ya"},
    };
    for (String[] c : cases) {
      assertAgreesWithJdk(c[0], c[1]);
    }
  }

  // The two CLAUDE.md hand-checks for any dispatch/capture change.
  @Test
  public void handChecks() {
    assertThat(Ll1Pattern.compile("((a?b)c)?").matcher("").matches(), is(true));
    Matcher m = Ll1Pattern.compile("(a+b)+").matcher("ababab");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("ab"));
  }
}

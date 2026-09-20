package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Pins the documented differences from java.util.regex (README's "Intentional differences") and
 * the syntax gaps still open (remaining_work.md), so a change in either direction is noticed.
 */
@RunWith(JUnit4.class)
public class KnownDivergenceTest {
  private static final int CANON_EQ = Ll1Pattern.CANON_EQ;

  private static void assertRejected(String pattern, int flags) {
    try {
      Ll1Pattern.compile(pattern, flags);
      fail("expected /" + pattern + "/ to be rejected");
    } catch (PatternSyntaxException expected) {
      // fine
    }
  }

  private static String findAll(Ll1Pattern p, String input) {
    Matcher m = p.matcher(input);
    StringBuilder sb = new StringBuilder();
    while (m.find()) sb.append(m.start()).append('-').append(m.end()).append(' ');
    return sb.toString();
  }

  // --- CANON_EQ: where this engine deliberately differs from java.util.regex.

  @Test
  public void canonEq_negatedClassAndPropertyDoNotSwallowTrailingMarks() {
    // java.util.regex matches all of "e" + U+0301 here.
    assertThat(findAll(Ll1Pattern.compile("[^x]", CANON_EQ), "e\u0301"), is("0-1 1-2 "));
    assertThat(Ll1Pattern.compile("\\p{L}", CANON_EQ).matcher("e\u0301").matches(), is(false));
  }

  @Test
  public void canonEq_quotedTextIsRewrittenToo() {
    // java.util.regex takes the text inside \Q..\E literally after its rewrite and fails to match.
    assertThat(Ll1Pattern.compile("\\Q\u00e9\\E", CANON_EQ).matcher("e\u0301").matches(), is(true));
  }

  @Test
  public void canonEq_classAlternativesAreCaseFolded() {
    // java.util.regex does not fold the rewritten alternatives of a class.
    int flags = CANON_EQ | Ll1Pattern.CASE_INSENSITIVE;
    assertThat(Ll1Pattern.compile("[\u00e9]", flags).matcher("E\u0301").matches(), is(true));
  }

  @Test
  public void canonEq_loopOverClusterDoesNotRetryFewerIterations() {
    // java.util.regex finds 0-2 (one iteration, the second fails and is dropped).
    assertThat(findAll(Ll1Pattern.compile("\u00e9+", CANON_EQ), "e\u0301e\u0300"), is(""));
  }

  @Test
  public void canonEq_clusterInUnsupportedClassPositionsIsRejected() {
    assertRejected("[^\u00e9]", CANON_EQ);
    assertRejected("[a-\u00e9]", CANON_EQ);
    assertRejected("[a[\u00e9]]", CANON_EQ);
  }

  @Test
  public void canonEq_tooManyMarksIsRejected() {
    assertRejected("a\u0300\u0301\u0302\u0303\u0304\u0305\u0306", CANON_EQ);
  }

  // --- The general no-backtracking limit that explains the loop row above.

  @Test
  public void multiCharLoopBodyThatFailsMidIterationIsNotRetried() {
    // java.util.regex: "(ab)+" finds 0-2 in "abac", "(ab)*" finds 0-0 and 1-1 in "a".
    assertThat(findAll(Ll1Pattern.compile("(ab)+"), "abac"), is(""));
    assertThat(findAll(Ll1Pattern.compile("(ab)*"), "a"), is("1-1 "));
  }

  // --- Syntax: patterns java.util.regex and this engine must agree on compiling.

  private static final String[] AGREE_ON_COMPILING = {
    "\\R", "\\h", "\\H", "\\v", "\\V", "\\x{1F600}", "\\0101", "\\cA", "\\e", "\\a", "\\f", "\\t",
    "\\p{javaLowerCase}", "\\p{IsAlphabetic}", "\\p{IsWhite_Space}", "(?<n>a)\\k<n>", "a\\Z", "a\\z",
    "[a-z&&[^aeiou]]", "(?u)a", "(?U)\\w", "\\p{Lu}", "a++b", "a*+b", "(?>a)b", "(a)\\1", "\\p{Alpha}",
    "\\p{L}", "\\P{L}", "\\p{InGreek}", "\\p{IsGreek}", "[\\p{L}&&[^a]]", "\\Bx", "(?i:a)b", "(?x) a b",
    "a{2,}", "a{,3}", "\\g", "\\pL", "\\p{Is_L}", "\\p{general_category=Lu}", "\\p{script=Latin}",
    "\\p{block=Greek}", "\\p{IsHan}", "\\p{XDigit}", "\\p{Punct}", "\\p{Graph}", "\\p{ASCII}", "\\p{Sc}",
    "(a", "a)", "[a", "a{2", "\\", "(?<1a>x)", "\\k<nope>",
    "*a", "+a", "?a", "a|*b", "(*a)", "a**", "a?*", "{a}", "(?i)*a", "\\B{g}", "\\b{w}", "\\b{",
  };

  @Test
  public void syntaxAcceptedOrRejectedLikeJavaUtilRegex() {
    StringBuilder disagreements = new StringBuilder();
    for (String p : AGREE_ON_COMPILING) {
      boolean jdk = compiles(p, true);
      boolean llk = compiles(p, false);
      if (jdk != llk) {
        disagreements.append('/').append(p).append("/ jdk=").append(jdk).append(" llk=").append(llk).append('\n');
      }
    }
    assertThat("disagreements:\n" + disagreements, disagreements.length(), is(0));
  }

  private static boolean compiles(String p, boolean jdk) {
    try {
      if (jdk) {
        java.util.regex.Pattern.compile(p);
      } else {
        Ll1Pattern.compile(p);
      }
      return true;
    } catch (java.util.regex.PatternSyntaxException e) {
      return false;
    }
  }

  // --- Syntax java.util.regex accepts and this engine doesn't. When one gets implemented, this test
  // fails: move the pattern into AGREE_ON_COMPILING and drop it from remaining_work.md.

  private static final String[] OPEN_GAPS = {
    "\\X", "\\b{g}",
  };

  @Test
  public void openSyntaxGapsAreStillRejected() {
    for (String p : OPEN_GAPS) {
      assertRejected(p, 0);
    }
  }

  // --- Forward references are rejected by design (design.md), java.util.regex accepts them.

  @Test
  public void forwardReferenceIsRejectedByDesign() {
    assertRejected("\\2(a)", 0);
  }

  // --- Rejected by design: they can't be guaranteed to run in linear time.

  @Test
  public void lookaroundIsRejectedByDesign() {
    for (String p : new String[] {"(?=a)a", "(?!b)a", "(?<=a)b", "(?<!a)b"}) {
      assertRejected(p, 0);
    }
  }

  // --- Reluctant and possessive modifiers are accepted but mean the same as the greedy form.

  @Test
  public void reluctantQuantifierIsGreedyHere() {
    Matcher m = Ll1Pattern.compile("a{2,3}?").matcher("aaa");
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("aaa"));
  }
}

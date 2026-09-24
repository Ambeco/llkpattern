package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@code CANON_EQ}: canonically equivalent spellings match each other; diffed against java.util.regex. */
@RunWith(JUnit4.class)
public class CanonEqTest {
  // Deliberately absent, each a known difference (see CanonEq* tests below and remaining_work.md):
  //  - "\u00e9+", "e\u0301*": a multi-code-point loop body that starts to match and then fails is not
  //    retried as fewer iterations here (same as "(ab)+" on "abac" without CANON_EQ).
  //  - "(?:\u00e9|\u00e8)": both branches start with 'e' -- an LL(1) ambiguity, rejected.
  //  - "\\Q\u00e9\\E", "[^x]", "\u00e9\\b", "\\p{L}": java.util.regex is inconsistent with itself there
  //    (\Q..\E text is taken literally after rewriting, [^x]/\p{L} swallow trailing marks but [a-z]/\w
  //    don't, \b looks through marks); this engine treats them as it does without CANON_EQ.
  //  - "\uac01", "\u1100\u1161\u11a8": java.util.regex doesn't match the partly composed U+AC00 U+11A8.
  private static final String[] PATTERNS = {
    "e\u0301", "\u00e9", "e", "a\u0323\u0302", "\u1ead", "a\u0302\u0323", "\u00e2\u0323", "\u00e9x", "x\u00e9",
    "(\u00e9)", "\u00e9|f", "[\u00e9]", "[e\u0301]", "[x\u00e9y]", "[a-z]", "\u212b", "\u00c5",
    "\u0958", "\u0915\u093c", "\uac00", "\u1100\u1161", "\u0344", "a\u0301",
    "\\u00e9", "\u00e9\u00e9", "\u00e9?x", "\u00e9\u00e8", "\u1e69", "s\u0323\u0307",
    "s\u0307\u0323", "\u01d6", "\u00fc\u0304", "u\u0308\u0304", "q\u0301", "abc", "a.c",
    "\u00e9{2}", "(\u00e9)\\1", "^\u00e9$",
  };

  private static final String[] INPUTS = {
    "", "e", "\u00e9", "e\u0301", "\u00e8", "e\u0300", "x", "xe\u0301", "x\u00e9", "\u00e9x", "e\u0301x", "\u00e9\u00e9",
    "e\u0301e\u0301", "e\u0301\u00e9", "a\u0323\u0302", "a\u0302\u0323", "\u1ea1\u0302", "\u00e2\u0323", "\u1ead", "a",
    "\u212b", "\u00c5", "A\u030a", "\u0958", "\u0915\u093c", "\u0915", "\uac00", "\u1100\u1161", "\uac01",
    "\u1100\u1161\u11a8", "\uac00\u11a8", "\u0344", "\u0308\u0301", "\u00e1", "a\u0301", "f", "y", "E\u0301", "\u00c9",
    "\u1e69", "s\u0323\u0307", "s\u0307\u0323", "\u1e63\u0307", "\u1e61\u0323", "\u01d6", "\u00fc\u0304", "u\u0308\u0304",
    "abc", "abcd", "\\u00e9", "\u00e9\u00e8", "e\u0301e\u0300", "z\u00e9z", "q\u0301", "\u00e9e\u0301", "e\u0301 ",
  };

  private static final int[] FLAG_SETS = {
    Ll1Pattern.CANON_EQ,
    Ll1Pattern.CANON_EQ | Ll1Pattern.CASE_INSENSITIVE,
    Ll1Pattern.CANON_EQ | Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CASE,
    Ll1Pattern.CANON_EQ | Ll1Pattern.MULTILINE | Ll1Pattern.DOTALL,
  };

  private static String findAll(java.util.regex.Matcher m) {
    StringBuilder sb = new StringBuilder();
    while (m.find()) sb.append(m.start()).append('-').append(m.end()).append(' ');
    return sb.toString();
  }

  private static String findAll(Matcher m) {
    StringBuilder sb = new StringBuilder();
    while (m.find()) sb.append(m.start()).append('-').append(m.end()).append(' ');
    return sb.toString();
  }

  private static String vis(String s) {
    StringBuilder b = new StringBuilder();
    for (char c : s.toCharArray()) b.append(c < 128 ? String.valueOf(c) : String.format("\\u%04x", (int) c));
    return b.toString();
  }

  @Test
  public void canonEq_matchesJavaUtilRegex() {
    StringBuilder divergences = new StringBuilder();
    int compared = 0;
    int compiled = 0;
    for (String p : PATTERNS) {
      for (int flags : FLAG_SETS) {
        if (p.startsWith("[") && (flags & Ll1Pattern.CASE_INSENSITIVE) != 0) {
          continue; // java.util.regex doesn't fold the rewritten alternatives of a class
        }
        Ll1Pattern llk;
        try {
          llk = Ll1Pattern.compile(p, flags);
        } catch (PatternSyntaxException e) {
          divergences.append("compile rejected /").append(vis(p)).append("/ flags ").append(flags).append(": ")
              .append(e.getMessage().replace('\n', ' ')).append('\n');
          continue;
        }
        compiled++;
        Pattern jdk = Pattern.compile(p, flags);
        for (String in : INPUTS) {
          String desc = "/" + vis(p) + "/ flags " + flags + " on \"" + vis(in) + "\"";
          compared++;
          java.util.regex.Matcher jm = jdk.matcher(in);
          Matcher lm = llk.matcher(in);
          boolean jMatches = jm.matches();
          if (jMatches != lm.matches()) divergences.append("matches ").append(desc).append('\n');
          else if (jMatches && (jm.start() != lm.start() || jm.end() != lm.end())) {
            divergences.append("matches span ").append(desc).append('\n');
          }
          if (jdk.matcher(in).lookingAt() != llk.matcher(in).lookingAt()) divergences.append("lookingAt ").append(desc).append('\n');
          String expected = findAll(jdk.matcher(in));
          String actual = findAll(llk.matcher(in));
          if (!expected.equals(actual)) {
            divergences.append("find ").append(desc).append(": expected ").append(expected).append("got ").append(actual).append('\n');
          }
        }
      }
    }
    assertThat("compared " + compared + " over " + compiled + " compiled; divergences:\n" + divergences,
        divergences.length(), is(0));
  }

  @Test
  public void canonEq_ambiguousAlternativesAreStillRejected() {
    // "e" and the cluster e+U+0301 both start with 'e' -- LL(1) ambiguity, as for any other pair.
    try {
      Ll1Pattern.compile("e|\u00e9", Ll1Pattern.CANON_EQ);
      fail("expected PatternSyntaxException");
    } catch (PatternSyntaxException expected) {
      // fine
    }
  }

  @Test
  public void canonEq_ambiguousUnionOfTwoClustersIsRejected() {
    try {
      Ll1Pattern.compile("(?:\u00e9|\u00e8)", Ll1Pattern.CANON_EQ);
      fail("expected PatternSyntaxException");
    } catch (PatternSyntaxException expected) {
      // fine
    }
  }

  @Test
  public void canonEq_multiMarkClusterIsLeftFactoredNotRejected() {
    // Every spelling of a\u0323\u0302 starts with 'a', U+1EA1 or U+00E2; a flat alternation
    // would be ambiguous, the factored one is not.
    Ll1Pattern p = Ll1Pattern.compile("a\u0323\u0302", Ll1Pattern.CANON_EQ);
    for (String in : new String[] {"a\u0323\u0302", "a\u0302\u0323", "\u1ea1\u0302", "\u00e2\u0323", "\u1ead"}) {
      assertThat(in, p.matcher(in).matches(), is(true));
    }
    assertThat(p.matcher("a\u0323").matches(), is(false));
  }

  @Test
  public void canonEq_partiallyComposedHangulMatches() {
    // Canonically equivalent, though java.util.regex doesn't match it.
    Ll1Pattern p = Ll1Pattern.compile("\uac01", Ll1Pattern.CANON_EQ);
    assertThat(p.matcher("\uac00\u11a8").matches(), is(true));
    assertThat(p.matcher("\u1100\u1161\u11a8").matches(), is(true));
  }

  @Test
  public void canonEq_literalFlagDisablesIt() {
    assertThat(
        Ll1Pattern.compile("\u00e9", Ll1Pattern.CANON_EQ | Ll1Pattern.LITERAL).matcher("e\u0301").matches(),
        is(false));
  }

  @Test
  public void canonEq_withoutTheFlagNothingIsEquivalent() {
    assertThat(Ll1Pattern.compile("\u00e9").matcher("e\u0301").matches(), is(false));
    assertThat(Ll1Pattern.compile("\u00e9", Ll1Pattern.CANON_EQ).matcher("e\u0301").matches(), is(true));
  }

  @Test
  public void canonEq_negatedClassWithClusterIsRejectedWithExplanation() {
    try {
      Ll1Pattern.compile("[^\u00e9]", Ll1Pattern.CANON_EQ);
      fail("expected PatternSyntaxException");
    } catch (PatternSyntaxException expected) {
      assertThat(expected.getMessage().contains("negated"), is(true));
    }
  }

  @Test
  public void canonEq_nestedClassWithClusterIsRejectedWithExplanation() {
    try {
      Ll1Pattern.compile("[a[\u00e9]]", Ll1Pattern.CANON_EQ);
      fail("expected PatternSyntaxException");
    } catch (PatternSyntaxException expected) {
      assertThat(expected.getMessage().contains("nested"), is(true));
    }
  }

  @Test
  public void canonEq_rangeBoundingClassWithClusterIsRejectedWithExplanation() {
    try {
      Ll1Pattern.compile("[\u00e9-z]", Ll1Pattern.CANON_EQ);
      fail("expected PatternSyntaxException");
    } catch (PatternSyntaxException expected) {
      assertThat(expected.getMessage().contains("range-bounding"), is(true));
    }
    try {
      Ll1Pattern.compile("[a-\u00e9]", Ll1Pattern.CANON_EQ);
      fail("expected PatternSyntaxException");
    } catch (PatternSyntaxException expected) {
      assertThat(expected.getMessage().contains("range-bounding"), is(true));
    }
  }

  @Test
  public void canonEq_tooManyConsecutiveMarksIsRejectedWithExplanation() {
    StringBuilder cluster = new StringBuilder("a");
    for (int i = 0; i < 7; i++) {
      cluster.append('\u0323'); // 7 combining marks after the base, one more than MAX_MARKS
    }
    try {
      Ll1Pattern.compile(cluster.toString(), Ll1Pattern.CANON_EQ);
      fail("expected PatternSyntaxException");
    } catch (PatternSyntaxException expected) {
      assertThat(expected.getMessage().contains("consecutive combining marks"), is(true));
    }
  }

  @Test
  public void canonEq_maxConsecutiveMarksCompilesAndMatches() {
    StringBuilder cluster = new StringBuilder("a");
    for (int i = 0; i < 6; i++) {
      cluster.append('\u0323'); // exactly MAX_MARKS: still allowed
    }
    Ll1Pattern p = Ll1Pattern.compile(cluster.toString(), Ll1Pattern.CANON_EQ);
    assertThat(p.matcher(cluster.toString()).matches(), is(true));
  }
}

package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@code X{0}} and {@code X{0,0}} match nothing at all, as in java.util.regex (found by the RE2J corpus). */
@RunWith(JUnit4.class)
public class ZeroCountQuantifierTest {
  private static final String[] BODIES = {"a", "[ab]", "(a)", "(?:ab)", "(a|b)", "\\d", "."};
  private static final String[] QUANTIFIERS = {"{0}", "{0,0}"};
  private static final String[] SHAPES = {"%sb", "x%sb", "%s", "a%sb", "%sb*", "(c)%s(d)"};
  private static final String[] INPUTS = {"", "a", "b", "ab", "ba", "xb", "xab", "abab", "1b", "cd", "c"};

  private static String jdkFind(String p, String input) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(input);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      sb.append(m.start()).append('-').append(m.end());
      for (int g = 1; g <= m.groupCount(); g++) sb.append(',').append(m.group(g));
      sb.append(' ');
    }
    return sb.toString();
  }

  private static String llkFind(String p, String input) {
    Matcher m;
    try {
      m = Ll1Pattern.compile(p).matcher(input);
    } catch (PatternSyntaxException e) {
      return "compile error: " + e.getMessage();
    }
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      sb.append(m.start()).append('-').append(m.end());
      for (int g = 1; g <= m.groupCount(); g++) sb.append(',').append(m.group(g));
      sb.append(' ');
    }
    return sb.toString();
  }

  @Test
  public void zeroCountQuantifierMatchesLikeJdk() {
    StringBuilder diffs = new StringBuilder();
    for (String body : BODIES) {
      for (String q : QUANTIFIERS) {
        for (String shape : SHAPES) {
          String p = String.format(shape, body + q);
          for (String input : INPUTS) {
            String jdk = jdkFind(p, input);
            String llk = llkFind(p, input);
            if (!jdk.equals(llk)) {
              diffs.append("/" + p + "/ on '" + input + "': jdk=" + jdk + " llk=" + llk + "\n");
            }
          }
        }
      }
    }
    assertThat(diffs.toString(), is(""));
  }

  @Test
  public void zeroCountGroupStaysUnset() {
    Matcher m = Ll1Pattern.compile("(a){0}b").matcher("b");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1) == null, is(true));
  }
}

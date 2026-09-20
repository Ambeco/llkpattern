package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** A quantifier after a zero-width construct (^*a, \b+a) behaves as in java.util.regex. */
@RunWith(JUnit4.class)
public class ZeroWidthQuantifierTest {
  private static final String[] ZERO_WIDTH = {"^", "$", "\\b", "\\B", "\\A", "\\Z", "\\z"};
  private static final String[] QUANTIFIERS = {"*", "+", "?", "{0}", "{1}", "{2}", "{0,3}", "{2,}", "*?", "++", "?+"};
  private static final String[] SHAPES = {"%sa", "a%s", "b%sa"};
  private static final String[] INPUTS = {"a", "ba", "a\nb", "aa", " a", "a ", "\na"};
  private static final int[] FLAGS = {0, Ll1Pattern.MULTILINE, Ll1Pattern.MULTILINE | Ll1Pattern.UNIX_LINES};

  private static String jdkFind(String p, int flags, String input) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(p, flags).matcher(input);
    StringBuilder sb = new StringBuilder();
    while (m.find()) sb.append(m.start()).append('-').append(m.end()).append(' ');
    return sb.toString();
  }

  private static String llkFind(String p, int flags, String input) {
    Matcher m;
    try {
      m = Ll1Pattern.compile(p, flags).matcher(input);
    } catch (PatternSyntaxException e) {
      // Compile-time "can never match" rejections (e.g. b+a) are by design; the JDK finds nothing.
      return "";
    }
    StringBuilder sb = new StringBuilder();
    while (m.find()) sb.append(m.start()).append('-').append(m.end()).append(' ');
    return sb.toString();
  }

  private static String describe(String p, int flags, String input, String jdk, String llk) {
    return "/" + p + "/ flags=" + flags + " on " + input.replace("\n", "\\n") + ": jdk=" + jdk + "llk=" + llk + "\n";
  }

  @Test
  public void quantifiedZeroWidthConstructMatchesLikeJdk() {
    StringBuilder diffs = new StringBuilder();
    for (String z : ZERO_WIDTH) {
      for (String q : QUANTIFIERS) {
        for (String shape : SHAPES) {
          String p = String.format(shape, z + q);
          for (int flags : FLAGS) {
            for (String input : INPUTS) {
              String jdk = jdkFind(p, flags, input);
              String llk = llkFind(p, flags, input);
              if (!jdk.equals(llk)) {
                diffs.append(describe(p, flags, input, jdk, llk));
              }
            }
          }
        }
      }
    }
    assertThat("divergences:\n" + diffs, diffs.length(), is(0));
  }
}

package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@code .} excludes every line terminator by default, only {@code \n} under UNIX_LINES; diffed against java.util.regex. */
@RunWith(JUnit4.class)
public class DotLineTerminatorTest {
  private static final String[] PATTERNS = {
    ".", ".+", "a.", "a.+", ".+a?", "(?d).+", "(?s).+", "(?d)a.", "(?s)(?d).+", "(?d:.+)x?", "[a.]+", "\\Q.\\E+",
  };

  private static final String[] INPUTS = {
    "", "a", "ab", "\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029", "a\nb", "a\rb", "a\r\nb", "a\u0085b",
    "a\u2028b", "a\u2029b", "\u2027\u202a", "\n\n", "ab\ncd\rx",
  };

  private static final int[] FLAG_SETS = {
    0,
    Ll1Pattern.UNIX_LINES,
    Ll1Pattern.DOTALL,
    Ll1Pattern.MULTILINE,
    Ll1Pattern.DOTALL | Ll1Pattern.UNIX_LINES,
    Ll1Pattern.MULTILINE | Ll1Pattern.UNIX_LINES,
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

  @Test
  public void dot_matchesJavaUtilRegex() {
    StringBuilder divergences = new StringBuilder();
    int compared = 0;
    for (String p : PATTERNS) {
      for (int flags : FLAG_SETS) {
        Ll1Pattern llk;
        try {
          llk = Ll1Pattern.compile(p, flags);
        } catch (PatternSyntaxException e) {
          continue; // ambiguous under LL(1); not this test's concern
        }
        Pattern jdk = Pattern.compile(p, flags);
        for (String in : INPUTS) {
          String desc = "/" + p + "/ flags " + flags + " on \"" + in.replace("\n", "\\n").replace("\r", "\\r") + "\"";
          compared++;
          if (jdk.matcher(in).matches() != llk.matcher(in).matches()) divergences.append("matches ").append(desc).append('\n');
          if (jdk.matcher(in).lookingAt() != llk.matcher(in).lookingAt()) divergences.append("lookingAt ").append(desc).append('\n');
          String expected = findAll(jdk.matcher(in));
          String actual = findAll(llk.matcher(in));
          if (!expected.equals(actual)) {
            divergences.append("find ").append(desc).append(": expected ").append(expected).append("got ").append(actual).append('\n');
          }
        }
      }
    }
    assertThat("compared " + compared + "; divergences:\n" + divergences, divergences.length(), is(0));
    assertThat("matrix compared nothing", compared > 0, is(true));
  }
}

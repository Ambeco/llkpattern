package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Negated classes under CASE_INSENSITIVE fold their members first, then complement, as the JDK does. */
@RunWith(JUnit4.class)
public class NegatedClassCaseFoldTest {
  private static final String[] PATTERNS = {
    "[^a]", "[^A]", "[^ab]", "[^a-c]", "[^a-cX]", "[^\\x61]", "[^\\N{LATIN SMALL LETTER A}]",
    "[^a]+", "x[^a]y", "[^a]{2}", "[^a-z]", "[^A-Z]", "[^0-9a]", "[^\\p{Lu}]", "[^\\p{Ll}]", "[^\\P{Lu}]",
    "\\P{Lu}", "\\P{Ll}", "\\P{L}", "[\\P{Lu}]", "\\P{IsAlphabetic}", "\\P{IsUppercase}", "\\P{javaLowerCase}",
    "[a-z&&[^aeiou]]", "[^a-z&&[^aeiou]]", "[a-c[^b]]", "[^a[b]]", "[^\\W]", "\\W", "[^\\D]",
    "[^é]", "[^É]",
  };
  private static final String[] INPUTS = {
    "a", "A", "b", "B", "aAbB", "xay", "xAy", "xby", "ab", "AB", "z", "Z", "1", "_", "éÉ",
  };
  private static final int[] FLAGS = {
    0, Ll1Pattern.CASE_INSENSITIVE, Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CASE,
    Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CASE | Ll1Pattern.UNICODE_CHARACTER_CLASS,
  };

  private static String jdkFind(String p, int flags, String input) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(p, flags).matcher(input);
    StringBuilder sb = new StringBuilder();
    while (m.find()) sb.append(m.start()).append('-').append(m.end()).append(' ');
    return sb.toString();
  }

  private static String llkFind(String p, int flags, String input) {
    Matcher m = Ll1Pattern.compile(p, flags).matcher(input);
    StringBuilder sb = new StringBuilder();
    while (m.find()) sb.append(m.start()).append('-').append(m.end()).append(' ');
    return sb.toString();
  }

  @Test
  public void negatedClassesMatchLikeJdkUnderCaseInsensitive() {
    StringBuilder diffs = new StringBuilder();
    for (String p : PATTERNS) {
      for (int flags : FLAGS) {
        for (String input : INPUTS) {
          // Not covered here (remaining_work.md): non-ASCII input outside UNICODE_CASE, or against a \p{...}
          // class, whose fold the JDK does regardless of UNICODE_CASE; and the ſ/Kelvin special folds.
          boolean ascii = input.chars().allMatch(c -> c < 0x80);
          boolean property = p.contains("\\p") || p.contains("\\P");
          if (!ascii && ((flags & Ll1Pattern.UNICODE_CASE) == 0 || property)) {
            continue;
          }
          String jdk = jdkFind(p, flags, input);
          String llk;
          try {
            llk = llkFind(p, flags, input);
          } catch (PatternSyntaxException e) {
            llk = "REJECTED ";
          }
          if (!jdk.equals(llk)) {
            diffs.append('/').append(p).append("/ flags=").append(flags).append(" on ")
                .append(input.replace("\n", "\\n")).append(": jdk=").append(jdk).append("llk=").append(llk).append('\n');
          }
        }
      }
    }
    assertThat("divergences:\n" + diffs, diffs.length(), is(0));
  }
}

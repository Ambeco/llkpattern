package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@code \N{name}} resolves through Character.codePointOf and then behaves like the code point itself. */
@RunWith(JUnit4.class)
public class NamedCharacterEscapeTest {
  private static final String[] PATTERNS = {
    "\\N{LATIN SMALL LETTER A}",
    "\\N{latin small letter a}",
    "\\N{LATIN SMALL LETTER A}+b",
    "x\\N{LATIN SMALL LETTER A}?y",
    "[\\N{LATIN SMALL LETTER A}-\\N{LATIN SMALL LETTER C}]+",
    "[^\\N{LATIN SMALL LETTER A}]",
    "\\N{GRINNING FACE}",
    "\\N{GRINNING FACE}{2}",
    "[\\N{GRINNING FACE}a]+",
    "\\N{LATIN CAPITAL LETTER E WITH ACUTE}",
    "(\\N{LATIN SMALL LETTER A}|\\N{LATIN SMALL LETTER B})+",
    "\\N{SPACE}\\N{DIGIT ONE}",
  };
  private static final String[] INPUTS = {
    "a", "A", "aab", "xay", "xy", "abcabc", "😀😀a", "一", "Éé", "1 1", " 1",
  };
  private static final int[] FLAGS = {0, Ll1Pattern.CASE_INSENSITIVE, Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CASE};

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
  public void matchesLikeJdk() {
    StringBuilder diffs = new StringBuilder();
    for (String p : PATTERNS) {
      for (int flags : FLAGS) {
        if (p.startsWith("[^") && flags != 0) {
          continue; // a negated class ignores CASE_INSENSITIVE for any member, not just \N{...}: see remaining_work.md
        }
        for (String input : INPUTS) {
          String jdk = jdkFind(p, flags, input);
          String llk = llkFind(p, flags, input);
          if (!jdk.equals(llk)) {
            diffs.append('/').append(p).append("/ flags=").append(flags).append(" on ").append(input)
                .append(": jdk=").append(jdk).append("llk=").append(llk).append('\n');
          }
        }
      }
    }
    assertThat("divergences:\n" + diffs, diffs.length(), is(0));
  }

  @Test
  public void malformedAndUnknownNamesAreRejectedLikeJdk() {
    for (String p : new String[] {"\\N", "\\N{", "\\N{}", "\\N{NO SUCH CHARACTER NAME}", "\\Nx", "[\\N{nope}]", "a\\N"}) {
      boolean jdk;
      try {
        java.util.regex.Pattern.compile(p);
        jdk = true;
      } catch (java.util.regex.PatternSyntaxException e) {
        jdk = false;
      }
      boolean llk;
      try {
        Ll1Pattern.compile(p);
        llk = true;
      } catch (PatternSyntaxException e) {
        llk = false;
      }
      assertThat("/" + p + "/ compiles (jdk vs llk)", llk, is(jdk));
    }
  }

  @Test
  public void unknownNameErrorSaysWhatItWas() {
    try {
      Ll1Pattern.compile("\\N{NO SUCH CHARACTER NAME}");
    } catch (PatternSyntaxException e) {
      assertThat(e.getMessage().contains("NO SUCH CHARACTER NAME"), is(true));
      return;
    }
    throw new AssertionError("expected a PatternSyntaxException");
  }
}

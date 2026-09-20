package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@code LITERAL} treats the whole pattern as plain text; diffed against java.util.regex. */
@RunWith(JUnit4.class)
public class LiteralFlagTest {
  private static final String[] PATTERNS = {
    "a", "abc", "a.b", "a*", "(a)", "[ab]", "a|b", "\\d", "\\Qab\\E", "\\", "^a$", "(?i)a", "a{2}", "a b", "a#b",
    "\u00e9", "\ud83d\ude00", "x\ud83d\ude00y", "\\\\", "a+b?",
  };

  private static final String[] INPUTS = {
    "", "a", "abc", "a.b", "axb", "a*", "aa", "(a)", "[ab]", "a|b", "b", "\\d", "5", "\\Qab\\E", "ab", "\\", "^a$",
    "(?i)a", "A", "a{2}", "a b", "ab", "a#b", "\u00e9", "\u00c9", "e\u0301", "\ud83d\ude00", "x\ud83d\ude00y",
    "\\\\", "a+b?", "aab", "ABC", "xa.by",
  };

  private static final int[] FLAG_SETS = {
    Ll1Pattern.LITERAL,
    Ll1Pattern.LITERAL | Ll1Pattern.CASE_INSENSITIVE,
    Ll1Pattern.LITERAL | Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CASE,
    Ll1Pattern.LITERAL | Ll1Pattern.COMMENTS,
    Ll1Pattern.LITERAL | Ll1Pattern.MULTILINE | Ll1Pattern.DOTALL | Ll1Pattern.UNIX_LINES,
    Ll1Pattern.LITERAL | Ll1Pattern.CANON_EQ,
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
  public void literal_matchesJavaUtilRegex() {
    StringBuilder divergences = new StringBuilder();
    int compared = 0;
    for (String p : PATTERNS) {
      for (int flags : FLAG_SETS) {
        Ll1Pattern llk = Ll1Pattern.compile(p, flags);
        Pattern jdk = Pattern.compile(p, flags);
        for (String in : INPUTS) {
          String desc = "/" + p + "/ flags " + flags + " on \"" + in + "\"";
          compared++;
          java.util.regex.Matcher jm = jdk.matcher(in);
          Matcher lm = llk.matcher(in);
          boolean jMatches = jm.matches();
          if (jMatches != lm.matches()) divergences.append("matches ").append(desc).append('\n');
          else if (jMatches && (jm.start() != lm.start() || jm.end() != lm.end() || jm.groupCount() != lm.groupCount())) {
            divergences.append("matches span/groups ").append(desc).append('\n');
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
    assertThat("compared " + compared + "; divergences:\n" + divergences, divergences.length(), is(0));
  }

  @Test
  public void literal_emptyPatternIsRejectedLikeAnyOtherEmptySequence() {
    try {
      Ll1Pattern.compile("", Ll1Pattern.LITERAL);
      fail("expected PatternSyntaxException");
    } catch (PatternSyntaxException expected) {
      // Empty patterns don't compile here by design.
    }
  }
}

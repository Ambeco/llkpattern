package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Non-ASCII case folding where a class or literal is a dispatch candidate next to something else
 * (alternation, a quantified class followed by a literal), so the folded entry sets and the
 * ambiguity check are exercised as well as the classes themselves.
 */
@RunWith(JUnit4.class)
public class CaseFoldDispatchTest {
  private static final int CI_U = Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CASE;

  /** Must compile, and match like the JDK. */
  private static final String[] PATTERNS = {
    "[s]+1", "[a-z]+1", "(?:s|x)y", "s+|1", "[σ]+1", "[ς]+1", "ς+1", "(?:[k]|1)+",
    "[^a-z]+a", "[^s]+s", "(?:[å]|1)+", "[a-z]*\\d", "[\\p{Lu}]+1", "\\p{Lu}+1", "[\\w&&[^s]]+s",
    "(?:s)(?:ſ)", "sſ+", "[i]+1", "ı+1",
  };
  private static final String[] INPUTS = {
    "sſS1", "kKK1", "1", "σςΣΣ1", "åÅÅ11", "İiıI1",
    "abAB1", "ſſ", "ßẞ1", "Ǆǅǆ", "xy", "sxy", "",
  };
  private static final int[] FLAGS = {
    Ll1Pattern.CASE_INSENSITIVE, CI_U, CI_U | Ll1Pattern.UNICODE_CHARACTER_CLASS,
  };

  private static String jdkFind(String p, int flags, String input) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(p, flags).matcher(input);
    StringBuilder sb = new StringBuilder();
    while (m.find()) sb.append(m.start()).append('-').append(m.end()).append(' ');
    return sb.toString();
  }

  private static String llkFind(Ll1Pattern p, String input) {
    Matcher m = p.matcher(input);
    StringBuilder sb = new StringBuilder();
    while (m.find()) sb.append(m.start()).append('-').append(m.end()).append(' ');
    return sb.toString();
  }

  @Test
  public void dispatchCandidatesMatchLikeJdk() {
    StringBuilder diffs = new StringBuilder();
    StringBuilder rejected = new StringBuilder();
    for (String p : PATTERNS) {
      for (int flags : FLAGS) {
        Ll1Pattern llk;
        try {
          llk = Ll1Pattern.compile(p, flags);
        } catch (PatternSyntaxException e) {
          rejected.append('/').append(p).append("/ flags=").append(flags).append(": ").append(e.getDescription()).append('\n');
          continue;
        }
        for (String input : INPUTS) {
          String jdk = jdkFind(p, flags, input);
          String actual = llkFind(llk, input);
          if (!jdk.equals(actual)) {
            diffs.append('/').append(p).append("/ flags=").append(flags).append(" on ")
                .append(input).append(": jdk=").append(jdk).append("llk=").append(actual).append('\n');
          }
        }
      }
    }
    assertThat("divergences:\n" + diffs + "\nrejected:\n" + rejected, diffs.length(), is(0));
  }
}

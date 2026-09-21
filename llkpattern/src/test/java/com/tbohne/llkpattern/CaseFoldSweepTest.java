package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Every code point against classes/literals under CASE_INSENSITIVE, compared with java.util.regex:
 * non-ASCII folding ({@code (?iu)[s]} matches {@code ſ}), and property classes, which the JDK
 * substitutes (Lu/Ll/Lt/Upper/Lower/...) rather than folds, so {@code \w}, scripts and blocks
 * must NOT fold. Covers both the positive class and its negation.
 */
@RunWith(JUnit4.class)
public class CaseFoldSweepTest {
  private static final String[] PATTERNS = {
    "s", "k", "å", "σ", "ς", "ß", "µ", "i", "ı",
    "[s]", "[k]", "[i]", "[σ]", "[ς]", "[Σ]", "[ß]", "[ẞ]", "[µ]", "[å]",
    "[a-z]", "[A-Z]", "[k-l]", "[a-r]", "[À-Þ]", "[à-þ]", "[Α-Ω]", "[α-ω]",
    "[Ǆ-ǆ]", "[Ⴀ-Ⴥ]", "[a-cX-Z]", "[abc]", "[a-c&&[^b]]", "[a-z&&[^aeiou]]",
    "[^a-z]", "[^A-Z]", "[^s]", "[^k]", "[^σ]", "[^å]", "[^a-zå]", "[a-c[x-z]]", "[^a[b]]",
    "\\p{Lu}", "\\p{Ll}", "\\p{Lt}", "\\p{LC}", "\\p{L}", "\\P{Lu}", "\\P{Ll}", "\\p{IsLu}", "\\p{gc=Lu}",
    "\\p{IsUppercase}", "\\p{IsLowercase}", "\\p{IsTitlecase}", "\\p{IsAlphabetic}", "\\p{javaUpperCase}",
    "\\p{javaLowerCase}", "\\p{javaTitleCase}", "\\p{Upper}", "\\p{Lower}", "\\p{IsUpper}", "\\p{IsLower}",
    "\\p{Alpha}", "\\p{Alnum}", "\\p{InGreek}", "\\P{InGreek}", "\\p{IsGreek}", "\\p{IsLatin}", "\\w", "\\W",
    "[\\w]", "[^\\w]", "[\\p{Lu}]", "[^\\p{Lu}]", "[\\p{Lu}a]", "[\\p{InGreek}s]", "[\\w&&[^s]]",
  };
  private static final int[] FLAGS = {
    Ll1Pattern.CASE_INSENSITIVE,
    Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CASE,
    Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CASE | Ll1Pattern.UNICODE_CHARACTER_CLASS,
    Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CHARACTER_CLASS,
  };

  /** llk's Unicode tables are usually newer than an older JDK's, which would show up as noise in every property class. */
  private static boolean unicodeDataMatchesJdk() {
    for (String p : new String[] {"\\p{Lu}", "\\p{Ll}", "\\p{Lt}", "\\p{IsUppercase}", "\\p{IsLowercase}"}) {
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(p);
      Ll1Pattern llk = Ll1Pattern.compile(p, 0);
      for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
        String s = new String(Character.toChars(cp));
        if (Character.isDefined(cp) && jdk.matcher(s).matches() != llk.matcher(s).matches()) {
          return false;
        }
      }
    }
    return true;
  }

  @Test
  public void everyCodePointMatchesLikeJdk() {
    StringBuilder diffs = new StringBuilder();
    int total = 0;
    boolean sameData = unicodeDataMatchesJdk();
    for (String p : PATTERNS) {
      if (!sameData && p.matches(".*\\\\[pPwW].*")) {
        continue;
      }
      // Older JDKs don't fold sharp s with U+1E9E.
      if (Runtime.version().feature() < 21 && (p.contains("ß") || p.contains("ẞ"))) {
        continue;
      }
      for (int flags : FLAGS) {
        java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(p, flags);
        Ll1Pattern llk = Ll1Pattern.compile(p, flags);
        int count = 0;
        StringBuilder first = new StringBuilder();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
          if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
            continue;
          }
          if (!Character.isDefined(cp)) {
            continue; // llk's Unicode data may be newer than the running JDK's
          }
          String s = new String(Character.toChars(cp));
          boolean expected = jdk.matcher(s).matches();
          if (expected != llk.matcher(s).matches()) {
            if (++count <= 6) {
              first.append(String.format(" U+%04X(jdk=%b)", cp, expected));
            }
          }
        }
        if (count > 0) {
          total++;
          diffs.append('/').append(p).append("/ flags=").append(flags).append(": ").append(count)
              .append(" differ:").append(first).append('\n');
        }
      }
    }
    assertThat("divergences (" + total + "):\n" + diffs, total, is(0));
  }
}

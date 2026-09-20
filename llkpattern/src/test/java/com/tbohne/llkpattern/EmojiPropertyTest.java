package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assume.assumeNoException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The JDK 21 emoji binary properties. java.util.regex only knows them from JDK 21 on, so the JDK
 * side is compiled at run time and the tests are skipped on older JDKs.
 */
@RunWith(JUnit4.class)
public class EmojiPropertyTest {
  private static final String[] NAMES = {
    "Emoji", "Emoji_Presentation", "Emoji_Modifier", "Emoji_Modifier_Base", "Emoji_Component",
    "Extended_Pictographic",
  };

  private static java.util.regex.Pattern jdkPattern(String p) {
    try {
      return java.util.regex.Pattern.compile(p);
    } catch (java.util.regex.PatternSyntaxException e) {
      assumeNoException("this JDK predates the emoji properties", e);
      throw e;
    }
  }

  /** llk may know newer Unicode data than the running JDK, so it may match extra code points, but
   *  only ones this JDK still considers unassigned. */
  private static void assertSameProperty(String llkPattern, String jdkPattern) {
    java.util.regex.Matcher jdk = jdkPattern(jdkPattern).matcher("");
    Matcher llk = Ll1Pattern.compile(llkPattern).matcher("");
    StringBuilder diffs = new StringBuilder();
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; ++cp) {
      if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
        continue;
      }
      String s = new String(Character.toChars(cp));
      boolean expected = jdk.reset(s).matches();
      boolean actual = llk.reset(s).matches();
      if (expected != actual && (expected || Character.isDefined(cp))) {
        diffs.append(Integer.toHexString(cp)).append(" jdk=").append(expected).append(' ');
      }
    }
    assertThat("/" + llkPattern + "/ diverges from the JDK at: " + diffs, diffs.length(), is(0));
  }

  @Test
  public void matchesJdkForEveryCodePoint() {
    for (String name : NAMES) {
      assertSameProperty("\\p{Is" + name + "}", "\\p{Is" + name + "}");
    }
  }

  @Test
  public void isWordMatchesJdkAndUnicodeW() {
    assertSameProperty("\\p{IsWord}", "\\p{IsWord}");
    assertSameProperty("\\p{IsWORD}", "\\p{IsWORD}");
    assertSameProperty("\\p{IsWord}", "(?U)\\w");
    assertSameProperty("\\P{IsWord}", "\\P{IsWord}");
  }

  @Test
  public void negationAndCaseInsensitiveNameMatchJdk() {
    assertSameProperty("\\P{IsEmoji}", "\\P{IsEmoji}");
    assertSameProperty("\\p{IsEMOJI_MODIFIER_BASE}", "\\p{IsEMOJI_MODIFIER_BASE}");
    assertSameProperty("[\\p{IsEmoji}&&[^0-9#*]]", "[\\p{IsEmoji}&&[^0-9#*]]");
  }

  @Test
  public void isNamesAreCaseInsensitiveExceptCategoriesAndAscii() {
    StringBuilder diffs = new StringBuilder();
    for (String name : new String[] {
        "ALPHABETIC", "alphabetic", "WHITESPACE", "White_Space", "HEXDIGIT", "JOINCONTROL", "NONCHARACTERCODEPOINT",
        "LOWERCASE", "ALPHA", "alpha", "XDIGIT", "BLANK", "ASCII", "ascii", "Lu", "LU", "lu", "L", "l", "Han", "HAN", "Word", "WORD", "word"}) {
      String p = "\\p{Is" + name + "}";
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
      if (jdk != llk) {
        diffs.append(p).append(" jdk=").append(jdk).append(' ');
      }
    }
    assertThat("compile disagreements: " + diffs, diffs.length(), is(0));
  }

  @Test
  public void knownMembers() {
    assertThat(Ll1Pattern.compile("\\p{IsEmoji}").matcher("\uD83D\uDE00").matches(), is(true)); // U+1F600
    assertThat(Ll1Pattern.compile("\\p{IsEmoji}").matcher("a").matches(), is(false));
    assertThat(Ll1Pattern.compile("\\p{IsEmoji}").matcher("#").matches(), is(true)); // keycap base
    assertThat(Ll1Pattern.compile("\\p{IsEmoji_Presentation}").matcher("#").matches(), is(false));
    assertThat(Ll1Pattern.compile("\\p{IsEmoji_Modifier}").matcher("\uD83C\uDFFB").matches(), is(true)); // U+1F3FB
  }
}

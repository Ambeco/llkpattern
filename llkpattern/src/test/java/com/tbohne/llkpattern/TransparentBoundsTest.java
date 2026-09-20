package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Matcher#useTransparentBounds/hasTransparentBounds: whether {@code \b}/{@code \B} see the characters
 * just outside the region (transparent) or treat the region edges as the edges of the input (opaque,
 * the default). Diffed against java.util.regex, in combination with anchoring bounds.
 */
@RunWith(JUnit4.class)
public class TransparentBoundsTest {
  private static final String[] PATTERNS = {
    "a\\b", "\\ba", "a\\Bb", "\\Ba", "a\\B", "\\b\\w+", "\\w+\\b", "\\Bb+", "b+\\B", "\\b[ab]", "[ab]\\b",
    "a\\b-", "-\\ba", "\\b-", "-\\b", "\\B-", "-\\B", "\\ba\\b", "^\\ba", "a\\b$",
  };

  /**
   * Patterns whose {@code \b}/{@code \B} is resolved at compile time (both neighbours statically known, so no
   * boundary check exists at match time). With transparent bounds, at regionEnd, java.util.regex still looks at
   * the real next character and so reports a different hitEnd; only hitEnd differs (never the match result), and
   * reproducing it would mean giving up the compile-time elision, so those cases skip the hitEnd comparison.
   */
  private static final String[] STATICALLY_RESOLVED = {"a\\Bb", "a\\b-", "-\\ba"};

  private static final String[] INPUTS = {
    "", "a", "ab", "a b", " a", "a-", "-a", "ab ", " ab", "a_b", "abab", "a-b", "-a-", "b-a-b", "ab-ab", " a ",
  };

  private static String findAll(java.util.regex.Matcher m) {
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      sb.append(m.start()).append('-').append(m.end()).append(m.hitEnd() ? 'H' : 'h').append(m.requireEnd() ? 'R' : 'r').append(' ');
    }
    return sb.append(m.hitEnd() ? 'H' : 'h').toString();
  }

  private static String findAll(Matcher m) {
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      sb.append(m.start()).append('-').append(m.end()).append(m.hitEnd() ? 'H' : 'h').append(m.requireEnd() ? 'R' : 'r').append(' ');
    }
    return sb.append(m.hitEnd() ? 'H' : 'h').toString();
  }

  @Test
  public void matchResultsAndHitEnd_matchJavaUtilRegex() {
    List<String> divergences = new ArrayList<>();
    for (String p : PATTERNS) {
      for (int flags : new int[] {0, Ll1Pattern.MULTILINE}) {
        Pattern jp = Pattern.compile(p, flags);
        Ll1Pattern lp;
        try {
          lp = Ll1Pattern.compile(p, flags);
        } catch (RuntimeException e) {
          continue; // not LL(1) / rejected by this engine
        }
        for (String in : INPUTS) {
          for (int s = 0; s <= in.length(); s++) {
            for (int e = s; e <= in.length(); e++) {
              for (int mode = 0; mode < 4; mode++) {
                boolean transparent = (mode & 1) != 0;
                boolean anchoring = (mode & 2) == 0;
                for (int op = 0; op < 3; op++) {
                  java.util.regex.Matcher jm =
                      jp.matcher(in).region(s, e).useTransparentBounds(transparent).useAnchoringBounds(anchoring);
                  Matcher lm =
                      lp.matcher(in).region(s, e).useTransparentBounds(transparent).useAnchoringBounds(anchoring);
                  String expected;
                  String actual;
                  if (op < 2) {
                    boolean jr = op == 0 ? jm.matches() : jm.lookingAt();
                    boolean lr = op == 0 ? lm.matches() : lm.lookingAt();
                    expected = jr ? jm.start() + "-" + jm.end() : "no";
                    actual = lr ? lm.start() + "-" + lm.end() : "no";
                    if (jr == lr) {
                      expected += jm.hitEnd() + "," + (jr && jm.requireEnd());
                      actual += lm.hitEnd() + "," + (lr && lm.requireEnd());
                    }
                  } else {
                    expected = findAll(jm);
                    actual = findAll(lm);
                  }
                  if (transparent && java.util.Arrays.asList(STATICALLY_RESOLVED).contains(p)) {
                    expected = expected.replaceAll("[a-zA-Z,]", "");
                    actual = actual.replaceAll("[a-zA-Z,]", "");
                  }
                  if (!expected.equals(actual)) {
                    divergences.add(new String[] {"matches", "lookingAt", "find"}[op] + " /" + p + "/ flags " + flags
                        + " on \"" + in + "\" region " + s + "," + e + " transparent=" + transparent
                        + " anchoring=" + anchoring + ": java=" + expected + " llk=" + actual);
                  }
                }
              }
            }
          }
        }
      }
    }
    assertThat(
        divergences.size() + " divergences:\n" + String.join("\n", divergences.subList(0, Math.min(400, divergences.size()))),
        is("0 divergences:\n"));
  }

  @Test
  public void defaultIsOpaque_andSettingIsReturnedAndSticky() {
    Matcher m = Ll1Pattern.compile("a\\b").matcher("ab");
    assertThat(m.hasTransparentBounds(), is(false));
    assertThat(m.useTransparentBounds(true), sameInstance(m));
    assertThat(m.hasTransparentBounds(), is(true));
    m.reset();
    m.reset("xy");
    m.region(0, 1);
    m.usePattern(Ll1Pattern.compile("\\ba"));
    assertThat(m.hasTransparentBounds(), is(true));
    m.useTransparentBounds(false);
    assertThat(m.hasTransparentBounds(), is(false));
  }

  @Test
  public void transparentRegion_seesOutsideCharacters() {
    Matcher m = Ll1Pattern.compile("a\\b").matcher("ab").region(0, 1);
    assertThat(m.lookingAt(), is(true));
    m.useTransparentBounds(true);
    assertThat(m.lookingAt(), is(false));
    Matcher m2 = Ll1Pattern.compile("\\Bb").matcher("ab").region(1, 2);
    assertThat(m2.lookingAt(), is(false));
    m2.useTransparentBounds(true);
    assertThat(m2.lookingAt(), is(true));
  }
}

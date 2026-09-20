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
 * Matcher#useAnchoringBounds/hasAnchoringBounds: whether {@code ^ $ \A \z \Z} match at the region's
 * edges (default) or only at the true edges of the input. Diffed against java.util.regex.
 */
@RunWith(JUnit4.class)
public class AnchoringBoundsTest {
  private static final String[] PATTERNS = {
    "^a", "a$", "\\Aa", "a\\z", "a\\Z", "a+$", "^a+", "^a$", "\\Aa\\z", "a\\Zb?", "[ab]+\\Z", "\\A[ab]+",
    "b^a", "a$b", "b\\Aa", "a\\n^", "a\\n^b", "b\\n^a",
  };

  private static final String[] INPUTS = {
    "", "a", "aa", "ab", "ba", "aba", "a\n", "\na", "a\nb", "b\na", "a\r\n", "aba\n", "a\n\n", "\r\na",
    "ab\r\nab", "a\u0085",
  };

  private static final int[] FLAG_SETS = {
    0,
    Ll1Pattern.MULTILINE,
    Ll1Pattern.UNIX_LINES,
    Ll1Pattern.MULTILINE | Ll1Pattern.UNIX_LINES,
  };

  private static String describe(String p, int flags, String in, int s, int e, boolean anchoring, String op) {
    return op + " /" + p + "/ flags " + flags + " on \"" + in.replace("\n", "\\n").replace("\r", "\\r")
        + "\" region " + s + "," + e + " anchoring=" + anchoring;
  }

  /** Every match span of a find() loop, "" if none, plus each step's hitEnd/requireEnd when asked. */
  private static String findAll(java.util.regex.Matcher m, boolean flags) {
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      sb.append(m.start()).append('-').append(m.end());
      if (flags) sb.append(m.hitEnd() ? 'H' : 'h').append(m.requireEnd() ? 'R' : 'r');
      sb.append(' ');
    }
    if (flags) sb.append(m.hitEnd() ? 'H' : 'h');
    return sb.toString();
  }

  private static String findAll(Matcher m, boolean flags) {
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      sb.append(m.start()).append('-').append(m.end());
      if (flags) sb.append(m.hitEnd() ? 'H' : 'h').append(m.requireEnd() ? 'R' : 'r');
      sb.append(' ');
    }
    if (flags) sb.append(m.hitEnd() ? 'H' : 'h');
    return sb.toString();
  }

  private void diff(boolean compareHitEnd) {
    List<String> divergences = new ArrayList<>();
    for (String p : PATTERNS) {
      for (int flags : FLAG_SETS) {
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
              for (boolean anchoring : new boolean[] {true, false}) {
                for (int op = 0; op < 3; op++) {
                  java.util.regex.Matcher jm = jp.matcher(in).region(s, e).useAnchoringBounds(anchoring);
                  Matcher lm = lp.matcher(in).region(s, e).useAnchoringBounds(anchoring);
                  String opName = new String[] {"matches", "lookingAt", "find"}[op];
                  String expected;
                  String actual;
                  if (op == 0 || op == 1) {
                    boolean jr = op == 0 ? jm.matches() : jm.lookingAt();
                    boolean lr = op == 0 ? lm.matches() : lm.lookingAt();
                    expected = jr ? jm.start() + "-" + jm.end() : "no";
                    actual = lr ? lm.start() + "-" + lm.end() : "no";
                    if (compareHitEnd && jr == lr) {
                      expected += jm.hitEnd() + "," + (jr && jm.requireEnd());
                      actual += lm.hitEnd() + "," + (lr && lm.requireEnd());
                    }
                  } else {
                    expected = findAll(jm, compareHitEnd);
                    actual = findAll(lm, compareHitEnd);
                  }
                  if (!expected.equals(actual)) {
                    divergences.add(describe(p, flags, in, s, e, anchoring, opName) + ": java=" + expected
                        + " llk=" + actual);
                  }
                }
              }
            }
          }
        }
      }
    }
    assertThat(
        divergences.size() + " divergences:\n" + String.join("\n", divergences.subList(0, Math.min(40, divergences.size()))),
        is("0 divergences:\n"));
  }

  @Test
  public void matchResults_matchJavaUtilRegex() {
    diff(false);
  }

  @Test
  public void hitEndAndRequireEnd_matchJavaUtilRegex() {
    diff(true);
  }

  @Test
  public void defaultIsAnchoring_andSettingIsReturnedAndSticky() {
    Matcher m = Ll1Pattern.compile("^a").matcher("ba");
    assertThat(m.hasAnchoringBounds(), is(true));
    assertThat(m.useAnchoringBounds(false), sameInstance(m));
    assertThat(m.hasAnchoringBounds(), is(false));
    m.reset();
    assertThat(m.hasAnchoringBounds(), is(false));
    m.reset("xy");
    assertThat(m.hasAnchoringBounds(), is(false));
    m.region(0, 1);
    assertThat(m.hasAnchoringBounds(), is(false));
    m.usePattern(Ll1Pattern.compile("a$"));
    assertThat(m.hasAnchoringBounds(), is(false));
    m.useAnchoringBounds(true);
    assertThat(m.hasAnchoringBounds(), is(true));
  }

  @Test
  public void nonAnchoringRegion_hidesInputEdgeAnchorsAtRegionEdges() {
    Matcher m = Ll1Pattern.compile("^a").matcher("ba").region(1, 2);
    assertThat(m.lookingAt(), is(true));
    m.useAnchoringBounds(false);
    assertThat(m.lookingAt(), is(false));
    Matcher m2 = Ll1Pattern.compile("a$").matcher("ab").region(0, 1);
    assertThat(m2.find(), is(true));
    m2.reset().region(0, 1).useAnchoringBounds(false);
    assertThat(m2.find(), is(false));
  }

  @Test
  public void changingAnchoring_afterRegion_takesEffectImmediately() {
    Matcher m = Ll1Pattern.compile("a$").matcher("ab").region(0, 1).useAnchoringBounds(false);
    assertThat(m.find(), is(false));
    m.useAnchoringBounds(true);
    m.region(0, 1);
    assertThat(m.find(), is(true));
  }
}

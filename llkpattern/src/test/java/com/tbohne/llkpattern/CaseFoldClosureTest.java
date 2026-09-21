package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * JDK 27's range closure under {@code (?iu)} (a range also matches what folds into it, e.g. {@code
 * [U+017F-U+0180]} matches {@code s}), and dispatch gates that must not be wider than the JDK's own
 * matching (a named class is never folded, so it can't make a candidate ambiguous).
 */
@RunWith(JUnit4.class)
public class CaseFoldClosureTest {
  private static final int CI_U = Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CASE;

  // Every character that has a closing pair, what it folds to, and their case relatives.
  private static final int[] PROBES = {
    0x49, 0x69, 0x131, 0xB5, 0x3BC, 0x39C, 0x130, 0x73, 0x53, 0x17F, 0x1C4, 0x1C5, 0x1C6, 0x1C8,
    0x1C9, 0x1CB, 0x1CC, 0x1F1, 0x1F2, 0x1F3, 0x345, 0x3B9, 0x399, 0x1FBE, 0x3C2, 0x3C3, 0x3A3,
    0x3D0, 0x3B2, 0x392, 0x3D1, 0x3B8, 0x398, 0x3F4, 0x3D5, 0x3C6, 0x3A6, 0x3D6, 0x3C0, 0x3A0,
    0x3F0, 0x3BA, 0x39A, 0x3F1, 0x3C1, 0x3A1, 0x3F5, 0x3B5, 0x395, 0x1C80, 0x432, 0x412, 0x1C81,
    0x434, 0x1C82, 0x43E, 0x1C83, 0x441, 0x1C84, 0x1C85, 0x442, 0x1C86, 0x44A, 0x1C87, 0x463,
    0x1C88, 0xA64B, 0x1E9B, 0x1E61, 0x1E60, 0x1E9E, 0xDF, 0x1FD3, 0x390, 0x1FE3, 0x3B0, 0x2126,
    0x3C9, 0x3A9, 0x212A, 0x6B, 0x4B, 0x212B, 0xE5, 0xC5, 0xFB05, 0xFB06, 0x41, 0x61, 0x7A,
  };

  private static String esc(int cp) {
    return String.format("\\x{%X}", cp);
  }

  @Test
  public void rangesCloseLikeTheHost() {
    StringBuilder diffs = new StringBuilder();
    int checked = 0;
    for (int lo : PROBES) {
      for (int hi : new int[] {lo, lo + 1, lo + 0x100, lo + 0x2000}) {
        if (hi > 0x10FFFF) {
          continue;
        }
        String p = "[" + esc(lo) + "-" + esc(hi) + "]";
        for (int flags : new int[] {CI_U, CI_U | Ll1Pattern.UNICODE_CHARACTER_CLASS}) {
          java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(p, flags);
          Ll1Pattern llk = Ll1Pattern.compile(p, flags);
          for (int probe : PROBES) {
            String input = new String(Character.toChars(probe));
            boolean expected = jdk.matcher(input).matches();
            boolean actual = llk.matcher(input).matches();
            checked++;
            if (expected != actual) {
              diffs.append(p).append(" flags=").append(flags).append(" input=").append(esc(probe))
                  .append(" jdk=").append(expected).append('\n');
            }
          }
        }
      }
    }
    assertThat("of " + checked + " checks:\n" + diffs, diffs.length(), is(0));
  }

  private static final String[] ATOMS = {
    "\\p{Lu}", "\\p{Ll}", "\\p{InGreek}", "\\p{IsGreek}", "\\w", "\\p{Alpha}", "[a-z]", "[A-Z]", "s", "k", "K",
    "\\x{B5}", "\\x{3BC}", "\\x{17F}", "[\\x{17F}-\\x{180}]", "\\x{212A}", "\\d", "\\s", "[^a]", "\\p{Lower}",
  };

  /** llk may reject a pair only if some character really is matched by both alternatives per the JDK. */
  @Test
  public void rejectedPairsReallyOverlap() {
    StringBuilder falseAmbiguities = new StringBuilder();
    for (int flags : new int[] {CI_U, CI_U | Ll1Pattern.UNICODE_CHARACTER_CLASS}) {
      for (String a : ATOMS) {
        for (String b : ATOMS) {
          String p = "(?:" + a + "|" + b + ")";
          try {
            Ll1Pattern.compile(p, flags);
            continue;
          } catch (PatternSyntaxException e) {
            // fall through to check the rejection is justified
          }
          java.util.regex.Pattern pa = java.util.regex.Pattern.compile(a, flags);
          java.util.regex.Pattern pb = java.util.regex.Pattern.compile(b, flags);
          boolean common = false;
          for (int cp = 0; cp < 0x30000 && !common; cp++) {
            String s = new String(Character.toChars(cp));
            common = pa.matcher(s).matches() && pb.matcher(s).matches();
          }
          if (!common) {
            falseAmbiguities.append(p).append(" flags=").append(flags).append('\n');
          }
        }
      }
    }
    assertThat(falseAmbiguities.toString(), falseAmbiguities.length(), is(0));
  }

  /** Unfolded named blocks that only "collide" after folding (U+00B5 folds into Greek) are not ambiguous. */
  @Test
  public void namedClassesAreNotFoldedForDispatch() {
    String p = "(?:\\p{InLatin_1_Supplement}|\\p{InGreek})+";
    for (int flags : new int[] {CI_U, CI_U | Ll1Pattern.UNICODE_CHARACTER_CLASS}) {
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(p, flags);
      Ll1Pattern llk = Ll1Pattern.compile(p, flags);
      for (String input : new String[] {"µ", "μ", "ÅΩ", "µμx", "x"}) {
        assertThat(input, llk.matcher(input).matches(), is(jdk.matcher(input).matches()));
      }
    }
  }
}

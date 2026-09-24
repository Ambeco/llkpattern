package com.tbohne.llkpattern;

import static com.tbohne.llkpattern.SupplementaryChars.A;
import static com.tbohne.llkpattern.SupplementaryChars.B;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@code (?<=X)}/{@code (?<!X)}, restricted to a body {@code X} that always matches exactly one
 * code point -- see design.md's "Boundary matching" section and remaining_work.md. A direct
 * generalization of {@code \b}/{@code \B}'s own single-code-point {@code peekPrevious()} check
 * (see WordBoundaryTest for that construct's own coverage); general lookbehind and all lookahead
 * remain permanently rejected (see KnownDivergenceTest).
 */
@RunWith(JUnit4.class)
public class LookbehindTest {
  // --- Basic positive/negative, literal and character-class bodies ---

  @Test
  public void positive_literalBody() {
    assertThat(Ll1Pattern.compile("ab(?<=b)").matcher("ab").matches(), is(true));
    assertThat(Ll1Pattern.compile(".(?<=a)").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile(".(?<=a)").matcher("b").matches(), is(false));
  }

  @Test
  public void negative_literalBody() {
    assertThat(Ll1Pattern.compile("ab(?<!b)").matcher("ab").matches(), is(false));
    assertThat(Ll1Pattern.compile(".(?<!a)b").matcher("ab").matches(), is(false));
    assertThat(Ll1Pattern.compile(".(?<!a)b").matcher("cb").matches(), is(true));
  }

  @Test
  public void characterClassBody() {
    assertThat(Ll1Pattern.compile("[a-c](?<!a|b)").matcher("c").matches(), is(true));
    assertThat(Ll1Pattern.compile("[a-c](?<![a-b])").matcher("a").matches(), is(false));
    assertThat(Ll1Pattern.compile("[a-c](?<![a-b])").matcher("c").matches(), is(true));
  }

  @Test
  public void negativeLookbehind_holdsAtStartOfInput() {
    // No previous character at all -- peekPrevious()'s sentinel never satisfies a positive
    // lookbehind, and always satisfies a negative one.
    assertThat(Ll1Pattern.compile("(?<!a).").matcher("x").matches(), is(true));
    assertThat(Ll1Pattern.compile("(?<=a).").matcher("x").lookingAt(), is(false));
  }

  @Test
  public void supplementaryCodePointBody_notSplitAcrossSurrogates() {
    assertThat(Ll1Pattern.compile(A + "(?<=" + A + ")").matcher(A).matches(), is(true));
    assertThat(Ll1Pattern.compile(A + "(?<!" + A + ")").matcher(A).matches(), is(false));
    assertThat(Ll1Pattern.compile("(?<!" + A + ")" + B).matcher("x" + B).find(), is(true));
    assertThat(Ll1Pattern.compile("(?<!" + A + ")" + B).matcher(A + B).find(), is(false));
  }

  @Test
  public void find_startingOnLowSurrogateHalf_skipsToNextCodePoint() {
    // find(int) landing squarely on the low half of a surrogate pair (a legal char index, but not a
    // legal code-point-start) must skip that index and try the next one, same as java.util.regex --
    // regression guard for search()'s codePointAt-driven stepping (see Matcher#search).
    String in = "x" + A + "y"; // A is a 2-char supplementary code point; its low half is at index 2.
    Matcher m = Ll1Pattern.compile(".").matcher(in);
    assertThat(m.find(2), is(true));
    assertThat(m.start(), is(3));
    assertThat(m.group(), is("y"));
  }

  @Test
  public void find_startingOnRealSupplementaryCharacter_notMistakenForLowSurrogateHalf() {
    // Regression guard for the int-range check in search() (not a `(char) cp` narrowing cast): U+1DC00
    // is a genuine supplementary code point whose own low 16 bits (0xDC00) fall inside the
    // low-surrogate numeric range, so a buggy narrowing-cast version of the "is this a lone low
    // surrogate" check would wrongly treat find()'s legitimate start position here as illegal and
    // skip it.
    String supplementary = new String(Character.toChars(0x1DC00));
    String in = "x" + supplementary + "y";
    Matcher m = Ll1Pattern.compile(".").matcher(in);
    assertThat(m.find(1), is(true));
    assertThat(m.start(), is(1));
    assertThat(m.group(), is(supplementary));
  }

  // --- CASE_INSENSITIVE/UNICODE_CASE folds a literal body, same as any other literal match ---

  @Test
  public void caseInsensitiveLiteralBody_folds() {
    Ll1Pattern p = Ll1Pattern.compile(".(?<=A)", Ll1Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("a").matches(), is(true));
    assertThat(p.matcher("A").matches(), is(true));
    assertThat(Ll1Pattern.compile(".(?<=A)").matcher("a").matches(), is(false));
  }

  // --- Alternation body, plain and capturing ---

  @Test
  public void alternationBody() {
    Ll1Pattern p = Ll1Pattern.compile(".(?<=a|b)");
    assertThat(p.matcher("a").matches(), is(true));
    assertThat(p.matcher("b").matches(), is(true));
    assertThat(p.matcher("c").matches(), is(false));
  }

  @Test
  public void capturingAlternationBody() {
    Matcher m = Ll1Pattern.compile(".(?<=(a|b))").matcher("b");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("b"));
  }

  // --- Capturing group around the whole body: group()/start()/end(), including failure and loops ---

  @Test
  public void capturingBody_setsGroupOnSuccess() {
    Matcher m = Ll1Pattern.compile("ab(?<=(b))").matcher("ab");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("b"));
    assertThat(m.start(1), is(1));
    assertThat(m.end(1), is(2));
  }

  @Test
  public void capturingBody_unsetWhenAssertionNeverTaken() {
    // The overall match fails before the lookbehind is ever reached, so group 1 stays unset --
    // same "no backtracking to roll back" contract as any other capturing construct here.
    Matcher m = Ll1Pattern.compile("x(?<=(a))").matcher("y");
    assertThat(m.matches(), is(false));
    assertThrows(IllegalStateException.class, () -> m.start(1));
  }

  @Test
  public void capturingBody_insideLoop_lastIterationWins() {
    Matcher m = Ll1Pattern.compile("(.(?<=(.)))+").matcher("abc");
    assertThat(m.matches(), is(true));
    assertThat(m.group(2), is("c"));
  }

  @Test
  public void namedCapturingBody() {
    Matcher m = Ll1Pattern.compile("ab(?<=(?<last>b))").matcher("ab");
    assertThat(m.matches(), is(true));
    assertThat(m.group("last"), is("b"));
  }

  // --- Compile-time rejections ---

  @Test
  public void lookahead_stillRejected() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?=a)a"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?!a)a"));
  }

  @Test
  public void multiCodePointBody_rejected() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?<=ab)c"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?<!ab)c"));
  }

  @Test
  public void optionalOrRepeatedBody_rejected() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?<=a?)b"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?<=a*)b"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?<=a{1,2})b"));
  }

  @Test
  public void doublyNestedCapturingGroups_rejected() {
    // A single capturing group wrapping the whole body ("(?<=(a))") is supported (see
    // capturingBody_setsGroupOnSuccess) -- but two groups, one inside the other, can't both wrap
    // the single code point behind this position.
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?<=((a)))c"));
  }

  @Test
  public void multipleAlternationCaptures_rejected() {
    // Each branch capturing its own group -- as opposed to one capturing group wrapping the
    // whole alternation (capturingAlternationBody, above), which is supported.
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?<=(a)|(b))c"));
  }

  @Test
  public void unterminatedLookbehind_rejected() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?<=a"));
  }

  // --- This project's own mandatory hand-checks after any dispatch/capture change (CLAUDE.md) ---

  @Test
  public void dispatchHandCheck_optionalNestedGroupsAgainstEmptyString() {
    assertThat(Ll1Pattern.compile("((a?b)c)?").matcher("").matches(), is(true));
  }

  @Test
  public void captureHandCheck_loopedGroupKeepsLastIteration() {
    Matcher m = Ll1Pattern.compile("(a+b)+").matcher("ababab");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("ab"));
  }

  // --- Loop-ambiguity interaction: a lookbehind in a greedy loop's own exit path ---

  @Test
  public void loopExit_throughSatisfiableLookbehind_isGenuinelyAmbiguous() {
    // After consuming an 'a', (?<=a) always holds -- so at the next 'a', this engine's flattened
    // (no-lookahead) dispatch genuinely can't tell "continue the loop" from "exit via the
    // lookbehind, then match b" apart -- admittedInteriorExitPeekSet (mirroring
    // WordBoundaryConstruct's a+\B example in design.md) must surface this as a real ambiguity,
    // not silently pick one, since java.util.regex's own backtracking makes the choice contextually.
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a+(?<=a)b"));
  }

  @Test
  public void loopExit_throughNegativeLookbehind_stillRequiresLastCharMismatch() {
    // (?<!a) can never hold right after the loop consumes an 'a' -- so the loop must run to its
    // natural end (or java.util.regex-style non-match) rather than exiting early via this guard.
    Ll1Pattern p = Ll1Pattern.compile("a+(?<!a)b");
    assertThat(p.matcher("aaab").matches(), is(false));
  }

  @Test
  public void reluctantLoop_exitsAsSoonAsLookbehindIsSatisfiable() {
    Ll1Pattern p = Ll1Pattern.compile("a+?(?<=a)b");
    Matcher m = p.matcher("aaab");
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("aaab"));
  }

  // --- Differential: match result, hitEnd, requireEnd against java.util.regex, across regions
  // and (non-)transparent bounds -- confirms this construct never sets hitEnd/requireEnd (it only
  // looks backward, so nothing about it can change with more input ahead). ---

  private static final String[] PATTERNS = {
    "a(?<=a)", "a(?<!a)", "(?<=a)b", "(?<!a)b", ".(?<=[ab])", ".(?<![ab])", "a+(?<!a)b", "a+?(?<=a)b",
  };

  private static final String[] INPUTS = {"", "a", "b", "ab", "aab", "aabb", "ba", "aaab"};

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
      Pattern jp = Pattern.compile(p);
      Ll1Pattern lp = Ll1Pattern.compile(p);
      for (String in : INPUTS) {
        for (int s = 0; s <= in.length(); s++) {
          for (int e = s; e <= in.length(); e++) {
            for (boolean transparent : new boolean[] {false, true}) {
              java.util.regex.Matcher jm = jp.matcher(in).region(s, e).useTransparentBounds(transparent);
              Matcher lm = lp.matcher(in).region(s, e).useTransparentBounds(transparent);
              String expected = findAll(jm);
              String actual = findAll(lm);
              if (!expected.equals(actual)) {
                divergences.add("/" + p + "/ on \"" + in + "\" region " + s + "," + e
                    + " transparent=" + transparent + ": java=" + expected + " llk=" + actual);
              }
            }
          }
        }
      }
    }
    assertThat(
        divergences.size() + " divergences:\n"
            + String.join("\n", divergences.subList(0, Math.min(400, divergences.size()))),
        is("0 divergences:\n"));
  }
}

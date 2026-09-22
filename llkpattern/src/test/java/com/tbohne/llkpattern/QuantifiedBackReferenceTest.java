package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** A quantifier after a backreference ({@code (a)\1+}) behaves as in java.util.regex (found by the AOSP corpus). */
@RunWith(JUnit4.class)
public class QuantifiedBackReferenceTest {
  private static final String[] REFS = {"\\1", "\\k<n>"};
  private static final String[] QUANTIFIERS = {"?", "*", "+", "{0}", "{1}", "{2}", "{1,2}", "{2,}", "{0,3}"};
  // Single-CODE-POINT-length groups, including a multi-valued one ([ab]): a backreference's own
  // compile-time entry set is the group's whole first-character set (e.g. {a,b} for [ab]), so a
  // loop's dispatch gate alone can't tell "some character this group could start with" from "the
  // specific character actually captured this time" -- BackReferenceMatcherConstruct.matchBody
  // handles that precisely now, falling through to the loop's own exit when the ACTUAL captured
  // text doesn't match, as long as nothing has been consumed yet this attempt (see its own doc and
  // remaining_work.md's former "loop over a backreference" entry). A MULTI-code-point group (e.g.
  // "(ab)") is deliberately NOT covered by this differential harness -- see
  // multiCharacterGroupPartialMatchFailsLikeAnyMultiCharLoopBody below for why that shape still
  // diverges from java.util.regex, same as any other multi-character loop body.
  private static final String[] GROUPS = {"(?<n>a)", "(?<n>b)", "(?<n>[ab])"};
  private static final String[] SHAPES = {"%s%s%s", "%s%s%sc", "x%s%s%sc", "%s%s%s$"};
  private static final String[] INPUTS = {"", "a", "aa", "aaa", "aaaa", "aaaaa", "ab", "abab", "ababab", "abc",
      "aac", "aaac", "xaac", "xabc", "xababc", "bbc", "abb", "aba"};

  private static String find(java.util.function.Supplier<Object> matcherFactory) {
    Object m = matcherFactory.get();
    StringBuilder sb = new StringBuilder();
    if (m instanceof java.util.regex.Matcher) {
      java.util.regex.Matcher j = (java.util.regex.Matcher) m;
      while (j.find()) sb.append(j.start()).append('-').append(j.end()).append(',').append(j.group(1)).append(' ');
    } else {
      Matcher l = (Matcher) m;
      while (l.find()) sb.append(l.start()).append('-').append(l.end()).append(',').append(l.group(1)).append(' ');
    }
    return sb.toString();
  }

  @Test
  public void quantifiedBackReferenceMatchesLikeJdk() {
    StringBuilder diffs = new StringBuilder();
    int compiled = 0;
    for (String group : GROUPS) {
      for (String ref : REFS) {
        for (String q : QUANTIFIERS) {
          for (String shape : SHAPES) {
            String p = String.format(shape, group, ref, q);
            Ll1Pattern llk;
            try {
              llk = Ll1Pattern.compile(p);
            } catch (PatternSyntaxException e) {
              continue; // LL(1) ambiguity, e.g. (a)\1*a-style shapes, is a by-design rejection
            }
            compiled++;
            for (String input : INPUTS) {
              String jdk = find(() -> java.util.regex.Pattern.compile(p).matcher(input));
              String mine = find(() -> llk.matcher(input));
              if (!jdk.equals(mine)) {
                diffs.append("/" + p + "/ on '" + input + "': jdk=" + jdk + " llk=" + mine + "\n");
              }
            }
          }
        }
      }
    }
    assertThat(diffs.toString(), is(""));
    assertThat("too few shapes compiled -- did the quantified backreference stop compiling?", compiled > 100, is(true));
  }

  @Test
  public void plusAfterBackReferenceCompiles() {
    Matcher m = Ll1Pattern.compile("(1)\\1+").matcher("1111");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("1"));
  }

  // --- A loop over a backreference to a MULTI-valued, single-code-point group correctly consults
  // the actual captured text now, not just the group's overall first-character set. ---

  @Test
  public void loopOverBackReference_multiValuedSingleCharGroup_consultsActualCapture() {
    Matcher m = Ll1Pattern.compile("([ab])\\1?").matcher("ab");
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("a")); // \1? correctly declines: captured 'a' != next char 'b'.

    m = Ll1Pattern.compile("([ab])\\1*").matcher("aab");
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("aa")); // \1* takes exactly the one matching iteration, then stops.
  }

  @Test
  public void loopOverBackReference_unparticipatedGroup_declinesRatherThanFailingWhole() {
    Matcher m = Ll1Pattern.compile("(a)?\\1*b").matcher("b");
    assertThat(m.matches(), is(true));
  }

  // --- A MULTI-code-point captured group, by contrast, still diverges from java.util.regex when
  // the backreference partially matches then fails mid-comparison: this engine has already
  // irreversibly consumed the matching prefix by the time the mismatch is detected, and (like any
  // other multi-character loop body -- see KnownDivergenceTest's own
  // multiCharLoopBodyThatFailsMidIterationIsNotRetried) has no way to back out. Deliberately not
  // "fixed" further -- see the class doc on GROUPS above and the project owner's own call that this
  // reduces to the same accepted shape as `ab(ab)?` vs "aba". ---

  @Test
  public void loopOverBackReference_multiCharacterGroupPartialMatch_failsLikeAnyMultiCharLoopBody() {
    // java.util.regex backtracks: (ab)\1? matches "ab" on "aba" (0 backreference iterations).
    assertThat(java.util.regex.Pattern.compile("(ab)\\1?").matcher("aba").find(), is(true));
    // llk can't undo the 'a' it already consumed comparing against \1 once 'b' fails to follow --
    // the whole attempt at this position fails, and (like the KnownDivergenceTest cases) there's no
    // earlier position left to retry either.
    assertThat(Ll1Pattern.compile("(ab)\\1?").matcher("aba").find(), is(false));

    // A mismatch on the very FIRST character of the referenced text, by contrast, hasn't consumed
    // anything yet -- that case is unaffected by the multi-character limitation above and matches
    // java.util.regex normally.
    Matcher m = Ll1Pattern.compile("(ab)\\1?").matcher("abXY");
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("ab"));
  }
}

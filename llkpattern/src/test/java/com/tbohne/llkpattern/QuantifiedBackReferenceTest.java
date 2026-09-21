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
  // Single-character groups only: a backreference's entry set is its group's whole first-character
  // set, so a loop over one enters on any of those characters and can't back out (remaining_work.md).
  private static final String[] GROUPS = {"(?<n>a)", "(?<n>b)"};
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
}

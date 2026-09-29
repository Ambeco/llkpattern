package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * A union branch that can END the whole pattern without consuming anything (a nullable tail such
 * as {@code a*}) is not a lowest-priority fallback: {@code java.util.regex} takes the first
 * alternative that succeeds, and under {@code find()}/{@code lookingAt()} such a branch succeeds
 * whatever follows, while under {@code matches()} it succeeds only at end of input. So {@code
 * a*|b} on {@code "b"} is {@code ""} under find and {@code "b"} under matches, and {@code b|a*}
 * is {@code "b"} in both. See {@code PatternConstruct#elseIsEndOfFind}.
 *
 * <p>Every pattern here is compared with {@code java.util.regex} over every input up to length 3
 * (alphabet {@code abcx}), in all three modes, including the spans of every capturing group.
 */
@RunWith(JUnit4.class)
public class EndOfFindUnionTest {
  private static final String[] PATTERNS = {
    "a*|b", "b|a*", "a?|b", "a*|b|c", "b|a*|c", "b|c|a*", "(a*)|b", "(a*)|(b)", "(b)|(a*)",
    "(a|b*)", "(?:a*|b)", "(b|a*)", "a{0,2}|b", "a?b*c{0,2}", "a?b?c?", "x(?:a*|b)", "x(a*|b)",
    "(a*|b)", "(?:a*|b)c", "a*|bc", "a*|b+", "(a*)|b+",
  };

  private static final List<String> INPUTS = new ArrayList<>();

  static {
    addInputs("", 3);
  }

  private static void addInputs(String prefix, int remaining) {
    INPUTS.add(prefix);
    if (remaining > 0) {
      for (char c : "abcx".toCharArray()) {
        addInputs(prefix + c, remaining - 1);
      }
    }
  }

  private static String describe(int groupCount, int start, int end, java.util.function.IntFunction<String> group,
      java.util.function.IntUnaryOperator groupStart) {
    StringBuilder sb = new StringBuilder(start + "-" + end);
    for (int g = 1; g <= groupCount; g++) {
      sb.append(' ').append(group.apply(g)).append('@').append(groupStart.applyAsInt(g));
    }
    return sb.toString();
  }

  private static String jdk(String pattern, String input, int mode) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(input);
    StringBuilder sb = new StringBuilder();
    if (mode < 2) {
      boolean ok = mode == 0 ? m.matches() : m.lookingAt();
      return ok ? describe(m.groupCount(), m.start(), m.end(), m::group, m::start) : "no";
    }
    while (m.find()) {
      sb.append(describe(m.groupCount(), m.start(), m.end(), m::group, m::start)).append(',');
    }
    return sb.toString();
  }

  private static String llk(String pattern, String input, int mode) {
    Matcher m = Ll1Pattern.compile(pattern).matcher(input);
    StringBuilder sb = new StringBuilder();
    if (mode < 2) {
      boolean ok = mode == 0 ? m.matches() : m.lookingAt();
      return ok ? describe(m.groupCount(), m.start(), m.end(), m::group, m::start) : "no";
    }
    while (m.find()) {
      sb.append(describe(m.groupCount(), m.start(), m.end(), m::group, m::start)).append(',');
    }
    return sb.toString();
  }

  @Test
  public void nullableTailUnions_agreeWithJdk_inEveryMode() {
    String[] modeNames = {"matches", "lookingAt", "find"};
    List<String> divergences = new ArrayList<>();
    for (String pattern : PATTERNS) {
      for (int mode = 0; mode < 3; mode++) {
        for (String input : INPUTS) {
          String expected = jdk(pattern, input, mode);
          String actual = llk(pattern, input, mode);
          if (!expected.equals(actual) && divergences.size() < 40) {
            divergences.add(pattern + " " + modeNames[mode] + " on \"" + input + "\": llk=" + actual
                + " jdk=" + expected);
          }
        }
      }
    }
    assertThat(String.join("\n", divergences), divergences.isEmpty(), is(true));
  }

  @Test
  public void spotChecks() {
    assertThat(llk("a*|b", "b", 2), is("0-0,1-1,"));
    assertThat(llk("a*|b", "b", 0), is("0-1"));
    assertThat(llk("b|a*", "b", 2), is("0-1,1-1,"));
  }

  // Two branches that both claim the catch-all (both nullable to the end) stay rejected, as does an
  // end-of-find branch that shares a first character with a sibling.
  @Test
  public void stillRejected() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a*|b*"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("a*|a"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("b|a?|a"));
  }
}

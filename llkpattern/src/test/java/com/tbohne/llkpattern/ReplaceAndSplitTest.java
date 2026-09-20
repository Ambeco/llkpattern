package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Differential tests of replaceAll/replaceFirst/appendReplacement/appendTail/quoteReplacement/
 * toMatchResult/results/split/splitAsStream/quote against java.util.regex, over patterns that are
 * valid (unambiguous) for both engines.
 */
@RunWith(JUnit4.class)
public class ReplaceAndSplitTest {
  private static final String[][] PATTERN_AND_INPUT = {
    {"a", "banana"},
    {"a", ""},
    {"a", "xyz"},
    {"a", "aaa"},
    {"a+", "baaanaaa"},
    {"(a)|(b)", "abcab"},
    {"(?<x>a)b", "abab cab"},
    {"a*", "baaac"}, // empty matches interleaved with real ones
    {"x*", "abc"},
    {"\\d+", "12 345 6"},
    {"[ ,]+", "a, b ,c  d"},
    {",", ",a,,b,,"},
    {",", ",,,"},
    {"b", "abc😀b"},
    {"(a)(b)?c", "ac abc"},
  };

  private static final String[] REPLACEMENTS = {
    "", "X", "[$0]", "<$1>", "<$1$2>", "${x}!", "\\$", "\\\\", "a\\b", "$10", "$1" + "0", "\\{",
  };

  private static Object outcome(java.util.concurrent.Callable<Object> c) {
    try {
      return c.call();
    } catch (Exception e) {
      return e.getClass().getSimpleName();
    }
  }

  @Test
  public void replaceAllAndFirst_matchJavaUtilRegex() {
    for (String[] pi : PATTERN_AND_INPUT) {
      for (String replacement : REPLACEMENTS) {
        String ctx = pi[0] + " / " + pi[1] + " / " + replacement;
        Pattern jp = Pattern.compile(pi[0]);
        Ll1Pattern lp = Ll1Pattern.compile(pi[0]);
        assertThat(
            "replaceAll " + ctx,
            outcome(() -> lp.matcher(pi[1]).replaceAll(replacement)),
            is(outcome(() -> jp.matcher(pi[1]).replaceAll(replacement))));
        assertThat(
            "replaceFirst " + ctx,
            outcome(() -> lp.matcher(pi[1]).replaceFirst(replacement)),
            is(outcome(() -> jp.matcher(pi[1]).replaceFirst(replacement))));
      }
    }
  }

  @Test
  public void replaceAll_withFunction_matchesJavaUtilRegex() {
    for (String[] pi : PATTERN_AND_INPUT) {
      String ctx = pi[0] + " / " + pi[1];
      assertThat(
          ctx,
          Ll1Pattern.compile(pi[0]).matcher(pi[1]).replaceAll(r -> "<" + r.group() + "@" + r.start() + ">"),
          is(Pattern.compile(pi[0]).matcher(pi[1]).replaceAll(r -> "<" + r.group() + "@" + r.start() + ">")));
    }
  }

  @Test
  public void split_matchesJavaUtilRegex_forEveryLimit() {
    for (String[] pi : PATTERN_AND_INPUT) {
      for (int limit : new int[] {-1, 0, 1, 2, 3, 100}) {
        assertArrayEquals(
            pi[0] + " / " + pi[1] + " / limit " + limit,
            Pattern.compile(pi[0]).split(pi[1], limit),
            Ll1Pattern.compile(pi[0]).split(pi[1], limit));
      }
      assertArrayEquals(Pattern.compile(pi[0]).split(pi[1]), Ll1Pattern.compile(pi[0]).split(pi[1]));
      assertThat(
          pi[0] + " / " + pi[1],
          Ll1Pattern.compile(pi[0]).splitAsStream(pi[1]).collect(Collectors.toList()),
          is(Pattern.compile(pi[0]).splitAsStream(pi[1]).collect(Collectors.toList())));
    }
  }

  @Test
  public void appendReplacementAndTail_buildTheSameStringAsJavaUtilRegex() {
    java.util.regex.Matcher jm = Pattern.compile("(\\d)").matcher("a1b22c");
    Matcher lm = Ll1Pattern.compile("(\\d)").matcher("a1b22c");
    StringBuffer jsb = new StringBuffer();
    StringBuilder lsb = new StringBuilder();
    while (jm.find()) {
      jm.appendReplacement(jsb, "<$1>");
    }
    while (lm.find()) {
      lm.appendReplacement(lsb, "<$1>");
    }
    jm.appendTail(jsb);
    lm.appendTail(lsb);
    assertThat(lsb.toString(), is(jsb.toString()));
    assertThat(lsb.toString(), is("a<1>b<2><2>c"));
  }

  @Test
  public void appendReplacement_beforeAnyMatch_throwsIllegalState() {
    assertThrows(
        IllegalStateException.class,
        () -> Ll1Pattern.compile("a").matcher("a").appendReplacement(new StringBuffer(), "x"));
  }

  @Test
  public void reset_restartsAppendPosition() {
    Matcher m = Ll1Pattern.compile("b").matcher("abc");
    StringBuffer sb = new StringBuffer();
    m.find();
    m.appendReplacement(sb, "X");
    m.reset();
    StringBuffer again = new StringBuffer();
    m.find();
    m.appendReplacement(again, "X");
    assertThat(again.toString(), is("aX"));
  }

  @Test
  public void replacementErrors_matchJavaUtilRegexExceptionTypes() {
    for (String bad : new String[] {"\\", "$", "$x", "${", "${}", "${nope}", "${1a}", "$9", "${x"}) {
      Object expected = outcome(() -> Pattern.compile("(?<x>a)").matcher("a").replaceAll(bad));
      Object actual = outcome(() -> Ll1Pattern.compile("(?<x>a)").matcher("a").replaceAll(bad));
      assertThat("replacement " + bad, actual, is(expected));
    }
  }

  @Test
  public void quoteReplacement_matchesJavaUtilRegex() {
    for (String s : new String[] {"", "abc", "a$b", "a\\b", "$\\$\\", "\\"}) {
      assertThat(Matcher.quoteReplacement(s), is(java.util.regex.Matcher.quoteReplacement(s)));
    }
    assertThat(
        Ll1Pattern.compile("a").matcher("a").replaceAll(Matcher.quoteReplacement("$1\\")), is("$1\\"));
  }

  @Test
  public void quote_matchesJavaUtilRegexAndRoundTrips() {
    for (String s : new String[] {"a.b", "a\\Eb", "\\E", "\\E\\E", "x\\", "\\Q"}) {
      assertThat(Ll1Pattern.quote(s), is(Pattern.quote(s)));
      assertThat(Ll1Pattern.compile(Ll1Pattern.quote(s)).matcher(s).matches(), is(true));
    }
  }

  @Test
  public void toMatchResult_isASnapshotUnaffectedByLaterMatching() {
    Matcher m = Ll1Pattern.compile("(a)|(b)").matcher("ab");
    m.find();
    MatchResult first = m.toMatchResult();
    m.find();
    assertThat(first.group(), is("a"));
    assertThat(first.start(), is(0));
    assertThat(first.end(), is(1));
    assertThat(first.groupCount(), is(2));
    assertThat(first.group(1), is("a"));
    assertThat(first.group(2), is((String) null));
    assertThat(first.start(2), is(-1));
    assertThat(m.group(), is("b"));
    assertThrows(IndexOutOfBoundsException.class, () -> first.group(3));
  }

  @Test
  public void toMatchResult_withoutAMatch_throwsOnAccess() {
    MatchResult none = Ll1Pattern.compile("a").matcher("b").toMatchResult();
    assertThrows(IllegalStateException.class, none::group);
    assertThrows(IllegalStateException.class, none::start);
  }

  @Test
  public void results_yieldsEveryMatch() {
    List<String> texts =
        Ll1Pattern.compile("\\d+").matcher("1 22 333").results().map(MatchResult::group).collect(Collectors.toList());
    assertThat(texts, is(Arrays.asList("1", "22", "333")));
  }

  @Test
  public void replaceAll_withAPreviousMatchEndAnchor_matchesJavaUtilRegex() {
    assertThat(
        Ll1Pattern.compile("\\Ga").matcher("aab a").replaceAll("-"),
        is(Pattern.compile("\\Ga").matcher("aab a").replaceAll("-")));
  }
}

package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assume.assumeNoException;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** asPredicate/asMatchPredicate, namedGroups, hasMatch, toString and splitWithDelimiters. */
@RunWith(JUnit4.class)
public class ApiExtrasTest {
  private static final String[][] PATTERN_AND_INPUT = {
    {"a", "banana"}, {"a", "xyz"}, {"a+", "aabaaab"}, {"b", "abab"}, {"[ab]", "xaxbx"}, {"-", "a-b-c-"},
    {"-", "-a"}, {"-+", "a--b-c"}, {"a", ""}, {"a", "a"}, {"ab", "abab"},
  };

  @Test
  public void asPredicate_findsAnywhere_asMatchPredicate_needsWholeInput() {
    for (String[] pi : PATTERN_AND_INPUT) {
      String ctx = pi[0] + " / " + pi[1];
      assertThat(ctx, Ll1Pattern.compile(pi[0]).asPredicate().test(pi[1]),
          is(Pattern.compile(pi[0]).asPredicate().test(pi[1])));
      assertThat(ctx, Ll1Pattern.compile(pi[0]).asMatchPredicate().test(pi[1]),
          is(Pattern.compile(pi[0]).asMatchPredicate().test(pi[1])));
    }
  }

  @Test
  public void namedGroups_mapsNamesToOneBasedGroupNumbers() {
    Ll1Pattern p = Ll1Pattern.compile("(?<first>a)(b)(?<third>c)");
    Map<String, Integer> expected = new HashMap<>();
    expected.put("first", 1);
    expected.put("third", 3);
    assertThat(p.namedGroups(), is(expected));
    assertThat(p.matcher("abc").namedGroups(), is(expected));
    assertThat(Ll1Pattern.compile("a").namedGroups().isEmpty(), is(true));
    try {
      p.namedGroups().put("x", 9);
      throw new AssertionError("namedGroups() must be unmodifiable");
    } catch (UnsupportedOperationException expectedException) {
      // ok
    }
  }

  @Test
  public void hasMatch_tracksTheLastOperation() {
    Matcher m = Ll1Pattern.compile("a").matcher("aXa");
    assertThat(m.hasMatch(), is(false));
    assertThat(m.find(), is(true));
    assertThat(m.hasMatch(), is(true));
    assertThat(m.find(), is(true));
    assertThat(m.find(), is(false));
    assertThat(m.hasMatch(), is(false));
    m.find(0);
    assertThat(m.hasMatch(), is(true));
    m.reset();
    assertThat(m.hasMatch(), is(false));
  }

  @Test
  public void toString_describesPatternRegionAndLastMatch() {
    Matcher m = Ll1Pattern.compile("a+").matcher("xaay");
    assertThat(m.toString(), is(Matcher.class.getName() + "[pattern=a+ region=0,4 lastmatch=]"));
    m.find();
    assertThat(m.toString(), is(Matcher.class.getName() + "[pattern=a+ region=0,4 lastmatch=aa]"));
    m.region(1, 3);
    assertThat(m.toString(), is(Matcher.class.getName() + "[pattern=a+ region=1,3 lastmatch=]"));
  }

  @Test
  public void splitWithDelimiters_matchesJavaUtilRegex() throws Exception {
    java.lang.reflect.Method jdk;
    try {
      jdk = Pattern.class.getMethod("splitWithDelimiters", CharSequence.class, int.class);
    } catch (NoSuchMethodException e) {
      assumeNoException("needs a JDK 21+ runtime", e);
      return;
    }
    for (String[] pi : PATTERN_AND_INPUT) {
      for (int limit : new int[] {-1, 0, 1, 2, 3, 100}) {
        assertArrayEquals(
            pi[0] + " / " + pi[1] + " / limit " + limit,
            (String[]) jdk.invoke(Pattern.compile(pi[0]), pi[1], limit),
            Ll1Pattern.compile(pi[0]).splitWithDelimiters(pi[1], limit));
      }
    }
  }

  @Test
  public void splitWithDelimiters_includesDelimiters() {
    assertArrayEquals(new String[] {"a", "-", "b", "-", "c"}, Ll1Pattern.compile("-").splitWithDelimiters("a-b-c", 0));
    assertArrayEquals(new String[] {"a", "-", "b-c"}, Ll1Pattern.compile("-").splitWithDelimiters("a-b-c", 2));
  }
}

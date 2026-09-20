package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** find(int)'s implicit reset() and a failed find() staying failed, diffed against java.util.regex. */
@RunWith(JUnit4.class)
public class FindStateTest {

  /** Runs the same script of find()/find(int)/region calls on both engines and compares each result. */
  private static void assertSameAsJdk(String pattern, String input, String script) {
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(pattern).matcher(input);
    Matcher llk = Ll1Pattern.compile(pattern).matcher(input);
    for (String step : script.split(" ")) {
      boolean expected;
      boolean actual;
      if (step.equals("f")) {
        expected = jdk.find();
        actual = llk.find();
      } else if (step.startsWith("f")) {
        int from = Integer.parseInt(step.substring(1));
        expected = jdk.find(from);
        actual = llk.find(from);
      } else {
        String[] bounds = step.substring(1).split(",");
        jdk.region(Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]));
        llk.region(Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]));
        continue;
      }
      String label = pattern + " on \"" + input + "\", script \"" + script + "\", at step " + step;
      assertThat(label, actual, is(expected));
      if (expected) {
        assertThat(label + " start", llk.start(), is(jdk.start()));
        assertThat(label + " end", llk.end(), is(jdk.end()));
      }
      assertThat(label + " regionStart", llk.regionStart(), is(jdk.regionStart()));
      assertThat(label + " regionEnd", llk.regionEnd(), is(jdk.regionEnd()));
    }
  }

  @Test
  public void failedFind_staysFailed() {
    assertSameAsJdk("a", "aXa", "f f f f f");
    assertSameAsJdk("a", "xyz", "f f f");
    assertSameAsJdk("a+", "aabaa", "f f f f");
  }

  @Test
  public void failedFind_afterEmptyMatchAtEnd() {
    assertSameAsJdk("a*", "b", "f f f f");
    assertSameAsJdk("a*", "", "f f f");
    assertSameAsJdk("b*", "aab", "f f f f f");
  }

  @Test
  public void failedFind_thenFindWithStart_searchesAgain() {
    assertSameAsJdk("a", "aXa", "f f f f0 f f f");
  }

  @Test
  public void findWithStart_resetsRegion() {
    assertSameAsJdk("a", "XaXa", "r0,2 f f2 f f");
    assertSameAsJdk("a", "XaXa", "r2,4 f1 f");
  }

  @Test
  public void findWithStart_discardsPriorMatch() {
    Matcher m = Ll1Pattern.compile("a").matcher("aXa");
    assertThat(m.find(), is(true));
    assertThat(m.find(3), is(false));
    assertThrows(IllegalStateException.class, m::start);
    assertThrows(IllegalStateException.class, m::group);
  }

  @Test
  public void findWithStart_resetsAppendPosition() {
    Matcher m = Ll1Pattern.compile("a").matcher("XaXa");
    StringBuilder sb = new StringBuilder();
    assertThat(m.find(), is(true));
    m.appendReplacement(sb, "-");
    assertThat(m.find(0), is(true));
    sb.setLength(0);
    m.appendReplacement(sb, "-");
    assertThat(sb.toString(), is("X-"));
  }

  @Test
  public void findWithStart_outOfRange_throws() {
    Matcher m = Ll1Pattern.compile("a").matcher("aXa");
    assertThrows(IndexOutOfBoundsException.class, () -> m.find(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> m.find(4));
    assertThat(m.find(3), is(false));
  }

  @Test
  public void failedFind_withPreviousMatchEnd_staysFailed() {
    assertSameAsJdk("\\Ga", "aaXa", "f f f f");
    assertSameAsJdk("\\Ga", "aaXa", "f f f f0 f f f");
  }
}

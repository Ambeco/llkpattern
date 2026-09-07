package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Named capture groups ((?<name>...)) and non-capturing groups ((?:...)), beyond what
 * QuantifierAndCaptureTest already covers for plain "(...)" capture semantics.
 */
@RunWith(JUnit4.class)
public class GroupSyntaxTest {
  @Test
  public void namedGroup_capturesByName() {
    Ll1Pattern p = Ll1Pattern.compile("(?<word>[a-z]+)");
    Matcher m = p.matcher("hello");
    assertThat(m.matches(), is(true));
    assertThat(m.group("word"), is("hello"));
  }

  @Test
  public void namedGroup_alsoAccessibleByNumber() {
    // Trailing literal must be outside [a-z] -- an LL(1) engine can't disambiguate a loop that
    // could still consume the next character from a following literal that's also in-class.
    Ll1Pattern p = Ll1Pattern.compile("a(?<word>[a-z]+)!");
    Matcher m = p.matcher("axyz!");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("xyz"));
    assertThat(m.group("word"), is("xyz"));
  }

  @Test
  public void namedGroup_unmatchedOptional_isNull() {
    Ll1Pattern p = Ll1Pattern.compile("a(?<opt>b)?c");
    Matcher m = p.matcher("ac");
    assertThat(m.matches(), is(true));
    assertThat(m.group("opt"), is(nullValue()));
  }

  @Test
  public void namedGroup_unknownName_throws() {
    Ll1Pattern p = Ll1Pattern.compile("(?<word>[a-z]+)");
    Matcher m = p.matcher("hello");
    m.matches();
    org.junit.Assert.assertThrows(
        IllegalArgumentException.class, () -> m.group("nosuchname"));
  }

  @Test
  public void nonCapturingGroup_doesNotAppearInGroupCount() {
    Ll1Pattern p = Ll1Pattern.compile("(?:abc)(def)");
    assertThat(p.matcher("abcdef").groupCount(), is(1));
  }

  @Test
  public void nonCapturingGroup_matchesItsContent() {
    Ll1Pattern p = Ll1Pattern.compile("(?:abc)+");
    assertThat(p.matcher("abcabc").matches(), is(true));
  }

  @Test
  public void nonCapturingGroup_withAlternation() {
    Ll1Pattern p = Ll1Pattern.compile("(?:cat|dog)s");
    assertThat(p.matcher("cats").matches(), is(true));
    assertThat(p.matcher("dogs").matches(), is(true));
    assertThat(p.matcher("cows").matches(), is(false));
  }
}

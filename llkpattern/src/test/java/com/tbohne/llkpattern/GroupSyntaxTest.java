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
  // U+10000-U+10005 (LINEAR B SYLLABLE B008 A through B038 E), a contiguous supplementary range
  // standing in for [a-z] below -- same reasoning as SupplementaryPatternTextTest: exercises the
  // parser's own supplementary-code-point lookahead inside a captured, quantified group, not just
  // a bare literal.
  private static final String SUPP_RANGE_START = "𐀀"; // U+10000
  private static final String SUPP_RANGE_END = "𐀅"; // U+10005
  private static final String SUPP_HELLO =
      "𐀀𐀁𐀂𐀂𐀃"; // 5 code points in-range
  private static final String SUPP_XYZ = "𐀃𐀄𐀅";
  private static final String SUPP_BANG = "𐀆"; // U+10006, outside the range above

  @Test
  public void namedGroup_capturesByName() {
    Ll1Pattern p = Ll1Pattern.compile("(?<word>[" + SUPP_RANGE_START + "-" + SUPP_RANGE_END + "]+)");
    Matcher m = p.matcher(SUPP_HELLO);
    assertThat(m.matches(), is(true));
    assertThat(m.group("word"), is(SUPP_HELLO));
  }

  @Test
  public void namedGroup_alsoAccessibleByNumber() {
    // Trailing literal must be outside the range -- an LL(1) engine can't disambiguate a loop that
    // could still consume the next character from a following literal that's also in-class.
    Ll1Pattern p = Ll1Pattern.compile(
        SUPP_RANGE_START + "(?<word>[" + SUPP_RANGE_START + "-" + SUPP_RANGE_END + "]+)" + SUPP_BANG);
    Matcher m = p.matcher(SUPP_RANGE_START + SUPP_XYZ + SUPP_BANG);
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is(SUPP_XYZ));
    assertThat(m.group("word"), is(SUPP_XYZ));
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

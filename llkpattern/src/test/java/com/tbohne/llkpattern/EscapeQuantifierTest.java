package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** A quantifier after a single-character escape (\n+, \x41{2}, \.*) applies to that character alone. */
@RunWith(JUnit4.class)
public class EscapeQuantifierTest {
  private static final String[] ESCAPES = {"\\n", "\\t", "\\x41", "\\x{41}", "\\u0041", "\\0101", "\\cA", "\\.", "\\*", "\\\\", "\\-"};
  private static final String[] QUANTIFIERS = {"+", "*", "?", "{2}", "{1,2}", "{2,}", "*+"}; // no reluctant forms: they mean the same as greedy here
  private static final String[] SHAPES = {"%s", "x%s", "%sx", "x%sy", "xy%s"};
  private static final String[] INPUTS = {
    "", "A", "AA", "AAA", "xAy", "xAAy", "xy", "\n\n", "x\n", "\t\tx", "..", "**x", "\\\\", "--", "", "xAAAy",
  };

  private static String jdkFind(String p, String input) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(input);
    StringBuilder sb = new StringBuilder();
    while (m.find()) sb.append(m.start()).append('-').append(m.end()).append(' ');
    return sb.toString();
  }

  private static String llkFind(String p, String input) {
    Matcher m = Ll1Pattern.compile(p).matcher(input);
    StringBuilder sb = new StringBuilder();
    while (m.find()) sb.append(m.start()).append('-').append(m.end()).append(' ');
    return sb.toString();
  }

  @Test
  public void quantifiedEscapeMatchesLikeJdk() {
    StringBuilder diffs = new StringBuilder();
    for (String e : ESCAPES) {
      for (String q : QUANTIFIERS) {
        for (String shape : SHAPES) {
          String p = String.format(shape, e + q);
          for (String input : INPUTS) {
            String jdk = jdkFind(p, input);
            String llk;
            try {
              llk = llkFind(p, input);
            } catch (PatternSyntaxException ex) {
              llk = "REJECTED: " + ex.getMessage().split("\n")[0] + " ";
            }
            if (!jdk.equals(llk)) {
              diffs.append('/').append(p).append("/ on ").append(input.replace("\n", "\\n")).append(": jdk=")
                  .append(jdk).append("llk=").append(llk).append('\n');
            }
          }
        }
      }
    }
    assertThat("divergences:\n" + diffs, diffs.length(), is(0));
  }

  @Test
  public void quantifiedQuotedTextRepeatsOnlyItsLastCharacter() {
    Matcher m = Ll1Pattern.compile("\\Qab\\E+").matcher("abbb");
    assertThat(m.matches(), is(true));
  }
}

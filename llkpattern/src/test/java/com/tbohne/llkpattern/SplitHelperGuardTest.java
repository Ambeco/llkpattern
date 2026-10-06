package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Guards for helpers split out of larger methods: the loop-residual gate errors
 * ({@code QuantifiablePatternConstruct#narrowResidualGates}), replacement-reference expansion
 * ({@code Matcher#appendGroupReference}), and the backreference/boundary parsers. The behaviors
 * are compared against java.util.regex where both engines accept the input; error messages are
 * ours, so they are checked by key phrase.
 */
@RunWith(JUnit4.class)
public class SplitHelperGuardTest {

  // --- narrowResidualGates ---

  @Test
  public void residualGates_twoResidualClaimants_messageNamesBothIndicesAndSuggestsExclusion() {
    PatternSyntaxException e = assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile(".+."));
    assertThat(e.getMessage(), containsString("allows any other character"));
    assertThat(e.getMessage(), containsString("ambiguous"));
    assertThat(e.getMessage(), containsString("[^b]+b"));
  }

  @Test
  public void residualGates_possessiveLoopSwallowingLaterPart_messageExplainsAndSuggestsAlternatives() {
    PatternSyntaxException e = assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile(".++b"));
    assertThat(e.getMessage(), containsString("possessive loop part"));
    assertThat(e.getMessage(), containsString("greedy quantifier"));
    assertThat(e.getMessage(), containsString("[^b]++b"));
  }

  @Test
  public void residualGates_possessiveLoopWithSiblingPart_isRejected() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(?:.|a)*+b"));
  }

  @Test
  public void residualGates_greedyResidualTakesWhatTheExitLeaves() {
    String[][] cases = {{".+b", "xxb"}, {".*b", "b"}, {".+?b", "xxb"}, {"a.+b", "axxb"}};
    for (String[] c : cases) {
      assertThat(c[0] + " on " + c[1],
          Ll1Pattern.compile(c[0]).matcher(c[1]).matches(), is(Pattern.compile(c[0]).matcher(c[1]).matches()));
    }
  }

  // --- replacement references ---

  private static String outcome(String regex, String input, String replacement, boolean llk) {
    try {
      return llk
          ? Ll1Pattern.compile(regex).matcher(input).replaceAll(replacement)
          : Pattern.compile(regex).matcher(input).replaceAll(replacement);
    } catch (RuntimeException e) {
      return e.getClass().getSimpleName();
    }
  }

  @Test
  public void replacementReferences_agreeWithJdk() {
    String[] replacements = {
      "$0", "$1", "$2", "$10", "$1$2", "${n}", "${n}x", "$1x", "\\$1", "$", "x$", "${", "${}", "${n",
      "${1n}", "${missing}", "$x", "$-1", "\\", "a\\", "$9", "$12", "${n}${n}", "$$",
    };
    String[][] regexAndInput = {{"(?<n>a)(b)?", "ab a"}, {"(a)", "aa"}};
    for (String[] ri : regexAndInput) {
      for (String r : replacements) {
        assertThat("/" + ri[0] + "/ on " + ri[1] + " replacing with " + r,
            outcome(ri[0], ri[1], r, true), is(outcome(ri[0], ri[1], r, false)));
      }
    }
  }

  @Test
  public void replacementReferences_greedyDigitsStopAtTheGroupCount() {
    // "$10" with one group is group 1 then a literal '0'.
    assertThat(Ll1Pattern.compile("(a)").matcher("a").replaceAll("$10"), is("a0"));
    assertThat(Ll1Pattern.compile("(a)").matcher("a").replaceAll("$1$1"), is("aa"));
  }

  @Test
  public void replacementReferences_unmatchedGroupAppendsNothing() {
    assertThat(Ll1Pattern.compile("(a)|(b)").matcher("b").replaceAll("[$1|$2]"), is("[|b]"));
  }

  @Test
  public void replacementReferences_errorMessagesAreDescriptive() {
    Matcher m = Ll1Pattern.compile("(?<n>a)").matcher("a");
    String[][] cases = {
      {"x$", "group index is missing"},
      {"x\\", "character to be escaped is missing"},
      {"${", "0 length name"},
      {"${}", "0 length name"},
      {"${n", "missing trailing '}'"},
      {"${1n}", "starts with digit"},
      {"${nope}", "No group with name {nope}"},
      {"$x", "expected a digit or {name}"},
    };
    for (String[] c : cases) {
      IllegalArgumentException e = assertThrows(c[0], IllegalArgumentException.class,
          () -> Ll1Pattern.compile("(?<n>a)").matcher("a").replaceAll(c[0]));
      assertThat(c[0], e.getMessage(), containsString(c[1]));
    }
    assertThat(m.find(), is(true));
  }

  // --- backreferences ---

  @Test
  public void backReference_numericExtendsDigitsOnlyWhileTheGroupExists() {
    // Twelve groups open: \12 is group 12; with one group it is group 1 then the literal "2".
    StringBuilder twelve = new StringBuilder();
    for (int i = 0; i < 12; i++) {
      twelve.append("(").append((char) ('a' + i)).append(")");
    }
    String p12 = twelve + "\\12";
    assertThat(Ll1Pattern.compile(p12).matcher("abcdefghijkll").matches(),
        is(Pattern.compile(p12).matcher("abcdefghijkll").matches()));
    assertThat(Ll1Pattern.compile("(a)\\12").matcher("aa2").matches(),
        is(Pattern.compile("(a)\\12").matcher("aa2").matches()));
  }

  @Test
  public void backReference_errors() {
    String[][] cases = {
      {"(a)\\2", "refers to a group that either doesn't exist"},
      {"\\1(a)", "forward references"},
      {"(a\\1)", "hasn't been closed yet"},
      {"(?<n>a)\\k", "\\k must be followed by"},
      {"(?<n>a)\\k<n", "Expected '>'"},
      {"(?<n>a)\\k<nope>", "named group that either doesn't exist"},
      {"\\k<n>(?<n>a)", "named group that either doesn't exist"},
      {"(?<n>a\\k<n>)", "hasn't been closed yet"},
    };
    for (String[] c : cases) {
      PatternSyntaxException e = assertThrows(c[0], PatternSyntaxException.class, () -> Ll1Pattern.compile(c[0]));
      assertThat(c[0] + ": " + e.getMessage(), e.getMessage(), containsString(c[1]));
    }
  }

  @Test
  public void backReference_numericAndNamedMatchJdk() {
    String[][] cases = {{"(a)\\1", "aa"}, {"(a)\\1", "ab"}, {"(?<n>a)\\k<n>", "aa"}, {"(?<n>a)\\k<n>", "ab"},
        {"(a)\\1+", "aaa"}, {"(?i)(a)\\1", "aA"}};
    for (String[] c : cases) {
      assertThat(c[0] + " on " + c[1], Ll1Pattern.compile(c[0]).matcher(c[1]).matches(),
          is(Pattern.compile(c[0]).matcher(c[1]).matches()));
    }
  }

  // --- boundaries ---

  @Test
  public void boundaries_matchJdk() {
    String[][] cases = {
      {"\\ba", "a"}, {"a\\b", "a"}, {"a\\Bb", "ab"}, {"\\Aa", "a"}, {"a\\z", "a"}, {"a\\Z", "a\n"},
      {"a\\Z", "a"}, {"(?m)a\\Z", "a"}, {"a\\b ", "a "},
    };
    for (String[] c : cases) {
      for (String mode : new String[] {"matches", "find"}) {
        Pattern jdk = Pattern.compile(c[0]);
        Ll1Pattern llk = Ll1Pattern.compile(c[0]);
        boolean expected = mode.equals("matches") ? jdk.matcher(c[1]).matches() : jdk.matcher(c[1]).find();
        boolean actual = mode.equals("matches") ? llk.matcher(c[1]).matches() : llk.matcher(c[1]).find();
        assertThat("/" + c[0] + "/ " + mode + " on " + c[1], actual, is(expected));
      }
    }
  }

  @Test
  public void boundaries_unsupportedTypeIsRejectedWithAnExplanation() {
    PatternSyntaxException e = assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("\\b{w}"));
    assertThat(e.getMessage(), containsString("boundary type"));
    assertThat(e.getMessage(), containsString("\\b{g}"));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("\\B{g}"));
  }
}

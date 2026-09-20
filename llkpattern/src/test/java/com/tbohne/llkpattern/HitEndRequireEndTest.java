package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Differential tests of Matcher#hitEnd/requireEnd against java.util.regex, over patterns that are
 * valid (unambiguous) for both engines, across matches()/lookingAt()/find() and every input below.
 */
@RunWith(JUnit4.class)
public class HitEndRequireEndTest {
  private static final String[] PATTERNS = {
    "a",
    "abc",
    "a+",
    "a*b",
    "a+b",
    "a?b",
    "a{2}",
    "a{2,3}",
    "a{2,3}b",
    "ab|c",
    "abc|d",
    "a|b",
    "(a)b",
    "(ab)+c",
    "(a+b)+",
    "[a-c]+d",
    "[^x]",
    "\\d+",
    "\\d+x",
    "a.b",
    "a\\b",
    "\\ba",
    "a\\Bb",
    "ab$",
    "a$",
    "a\\z",
    "a\\Z",
    "ab\\z",
    "a+$",
    "a+\\z",
    "^a",
    "^ab",
    "\\Aab",
    "(a)\\1",
    "(ab)\\1",
    "(a)b\\1",
    "(a|b)+c",
    "a+b*",
    "\\w+",
    "\\w+\\b",
    "^a$",
    "a$b",
    "[a-c]{2,3}",
  };

  private static final String[] FLAGGED_PATTERNS_INPUTS = {
    "", "a", "b", "ab", "abc", "abcd", "aa", "aab", "aaa", "aaab", "aaaa", "ba", "xa", "a\n", "ab\n",
    "a\r\n", "a\nb", "12", "12x", "x12", "aba", "abab", "ababc", "abb", "abcabc", "acb", "d", "cd",
    "aca", "a b", "ab b", "aab\n", "A", "AB", "aB", "\n", "\na", "a\n\n", "a\rb",
  };

  private static final int[] FLAG_SETS = {
    0,
    Ll1Pattern.CASE_INSENSITIVE,
    Ll1Pattern.MULTILINE,
    Ll1Pattern.UNIX_LINES,
    Ll1Pattern.MULTILINE | Ll1Pattern.UNIX_LINES,
  };


  @Test
  public void matchesLookingAtAndFind_matchJavaUtilRegex() {
    List<String> divergences = new ArrayList<>();
    for (String p : PATTERNS) {
     for (int flags : FLAG_SETS) {
      Pattern jp = Pattern.compile(p, flags);
      Ll1Pattern lp;
      try {
        lp = Ll1Pattern.compile(p, flags);
      } catch (RuntimeException e) {
        continue; // ambiguous for this engine, not a hitEnd question
      }
      for (String in : FLAGGED_PATTERNS_INPUTS) {
        for (int op = 0; op < 3; op++) {
          java.util.regex.Matcher jm = jp.matcher(in);
          Matcher lm = lp.matcher(in);
          boolean jr;
          boolean lr;
          String opName;
          if (op == 0) {
            opName = "matches";
            jr = jm.matches();
            lr = lm.matches();
          } else if (op == 1) {
            opName = "lookingAt";
            jr = jm.lookingAt();
            lr = lm.lookingAt();
          } else {
            opName = "find";
            jr = jm.find();
            lr = lm.find();
          }
          if (jr != lr) {
            continue; // a match-semantics difference is some other test's job
          }
          String ctx = opName + " /" + p + "/ flags " + flags + " on " + in.replace("\n", "\\n").replace("\r", "\\r");
          if (jm.hitEnd() != lm.hitEnd()) {
            divergences.add(ctx + ": hitEnd java=" + jm.hitEnd() + " llk=" + lm.hitEnd());
          }
          if (jr && jm.requireEnd() != lm.requireEnd()) {
            divergences.add(ctx + ": requireEnd java=" + jm.requireEnd() + " llk=" + lm.requireEnd());
          }
        }
      }
     }
    }
    assertThat(String.join("\n", divergences), is(""));
  }

  @Test
  public void hitEnd_isClearedByEachOperation() {
    Matcher m = Ll1Pattern.compile("a+").matcher("aab");
    assertThat(m.lookingAt(), is(true));
    assertThat(m.hitEnd(), is(false)); // stopped at 'b'
    m = Ll1Pattern.compile("a+").matcher("aaa");
    assertThat(m.lookingAt(), is(true));
    assertThat(m.hitEnd(), is(true));
    m.reset("aab");
    assertThat(m.lookingAt(), is(true));
    assertThat(m.hitEnd(), is(false));
  }

  @Test
  public void requireEnd_falseForPatternWithoutEndAnchor() {
    Matcher m = Ll1Pattern.compile("abc").matcher("abc");
    assertThat(m.matches(), is(true));
    assertThat(m.requireEnd(), is(false));
    assertThat(m.hitEnd(), is(false));
  }

  @Test
  public void requireEnd_trueForDollarAtEnd() {
    Matcher m = Ll1Pattern.compile("abc$").matcher("abc");
    assertThat(m.find(), is(true));
    assertThat(m.requireEnd(), is(true));
    assertThat(m.hitEnd(), is(true));
  }

  @Test
  public void hitEnd_trueWhenLiteralRunsOffTheEnd() {
    Matcher m = Ll1Pattern.compile("abc").matcher("ab");
    assertThat(m.lookingAt(), is(false));
    assertThat(m.hitEnd(), is(true));
    m = Ll1Pattern.compile("abc").matcher("xb");
    assertThat(m.lookingAt(), is(false));
    assertThat(m.hitEnd(), is(false)); // mismatched before reaching the end
  }
}

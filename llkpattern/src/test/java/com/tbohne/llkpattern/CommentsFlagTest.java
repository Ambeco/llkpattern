package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * COMMENTS ({@code (?x)}) -- whitespace and {@code #}-to-end-of-line comments are insignificant
 * outside a character class. Implemented via {@code PatternParser.skipComments()}, called
 * wherever the parser is about to inspect {@code peek} to decide what comes next: the top of
 * {@code parseUnion}'s main loop, right after a plain character checks for an immediately-following
 * quantifier, and {@code parseQuantifiable}'s own entry (covering every other atom type -- bracket
 * classes, groups, "."). Found while triaging the scraped-corpus harness's UNEXPECTED rows -- see
 * remaining_work.md/notes.md.
 */
@RunWith(JUnit4.class)
public class CommentsFlagTest {
  @Test
  public void whitespaceBetweenLiterals_isInsignificant() {
    assertThat(Ll1Pattern.compile("(?x) a b c").matcher("abc").matches(), is(true));
    assertThat(Ll1Pattern.compile("(?x)a  b   c").matcher("abc").matches(), is(true));
  }

  @Test
  public void whitespaceBetweenAtomAndQuantifier_isInsignificant() {
    assertThat(Ll1Pattern.compile("(?x)a * b").matcher("aaab").matches(), is(true));
    assertThat(Ll1Pattern.compile("(?x)a * b").matcher("b").matches(), is(true));
  }

  @Test
  public void hashStartsACommentToEndOfLine() {
    assertThat(Ll1Pattern.compile("(?x)abc # this is a comment\ndef").matcher("abcdef").matches(), is(true));
  }

  @Test
  public void inlineCommentsFlag_scopesOnlyItsOwnGroup() {
    // "(?x:...)" scopes COMMENTS to just the group, unlike bare "(?x)" -- matches every other
    // inline flag's existing scoping (see CaseInsensitiveTest).
    Ll1Pattern p = Ll1Pattern.compile("(?x: a b )c d");
    assertThat(p.matcher("abc d").matches(), is(true)); // inside the group: whitespace insignificant
    assertThat(p.matcher("abcd").matches(), is(false)); // outside it: the space before "d" is literal
  }

  @Test
  public void withoutCommentsFlag_whitespaceIsLiteral() {
    assertThat(Ll1Pattern.compile("a b").matcher("a b").matches(), is(true));
    assertThat(Ll1Pattern.compile("a b").matcher("ab").matches(), is(false));
  }

  @Test
  public void whitespaceInsideCharacterClass_staysSignificant() {
    // COMMENTS never applies inside "[...]" -- a space there is an ordinary member character.
    assertThat(Ll1Pattern.compile("(?x)[a b]").matcher("a").matches(), is(true));
    assertThat(Ll1Pattern.compile("(?x)[a b]").matcher(" ").matches(), is(true));
    assertThat(Ll1Pattern.compile("(?x)[a b]").matcher("c").matches(), is(false));
  }
}

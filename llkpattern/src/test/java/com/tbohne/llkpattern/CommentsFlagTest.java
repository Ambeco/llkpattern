package com.tbohne.llkpattern;

import static com.tbohne.llkpattern.SupplementaryChars.A;
import static com.tbohne.llkpattern.SupplementaryChars.B;
import static com.tbohne.llkpattern.SupplementaryChars.C;
import static com.tbohne.llkpattern.SupplementaryChars.D;
import static com.tbohne.llkpattern.SupplementaryChars.F;
import static com.tbohne.llkpattern.SupplementaryChars.repeat;
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
 *
 * <p>Uses SupplementaryChars' A/B/C/D/F (supplementary/astral code points) in place of the plain
 * ASCII letters this file originally used -- {@code skipComments()} specifically is one of the
 * fixes from the 2026-09-14 peek/peek2 session (its whitespace- and comment-skipping loops used to
 * step one {@code char} unit at a time via {@code advance(1)}, not a full code point via {@code
 * advanceCodePoint()} -- see notes.md), so {@code hashStartsACommentToEndOfLine} below embeds a
 * supplementary character directly inside a comment body to exercise that fix specifically.
 */
@RunWith(JUnit4.class)
public class CommentsFlagTest {
  @Test
  public void whitespaceBetweenLiterals_isInsignificant() {
    assertThat(Ll1Pattern.compile("(?x) " + A + " " + B + " " + C).matcher(A + B + C).matches(), is(true));
    assertThat(Ll1Pattern.compile("(?x)" + A + "  " + B + "   " + C).matcher(A + B + C).matches(), is(true));
  }

  @Test
  public void whitespaceBetweenAtomAndQuantifier_isInsignificant() {
    assertThat(Ll1Pattern.compile("(?x)" + A + " * " + B).matcher(repeat(A, 3) + B).matches(), is(true));
    assertThat(Ll1Pattern.compile("(?x)" + A + " * " + B).matcher(B).matches(), is(true));
  }

  @Test
  public void hashStartsACommentToEndOfLine() {
    // The comment body itself contains a supplementary character (F) -- exercises skipComments()'s
    // own advanceCodePoint() fix, not just the whitespace-skipping path the other tests here cover.
    assertThat(
        Ll1Pattern.compile("(?x)" + A + B + C + " # this is a comment with " + F + " in it\n" + A + B + C)
            .matcher(A + B + C + A + B + C)
            .matches(),
        is(true));
  }

  @Test
  public void inlineCommentsFlag_scopesOnlyItsOwnGroup() {
    // "(?x:...)" scopes COMMENTS to just the group, unlike bare "(?x)" -- matches every other
    // inline flag's existing scoping (see CaseInsensitiveTest).
    Ll1Pattern p = Ll1Pattern.compile("(?x: " + A + " " + B + " )" + C + " " + D);
    // inside the group: whitespace insignificant
    assertThat(p.matcher(A + B + C + " " + D).matches(), is(true));
    // outside it: the space before D is literal
    assertThat(p.matcher(A + B + C + D).matches(), is(false));
  }

  @Test
  public void withoutCommentsFlag_whitespaceIsLiteral() {
    assertThat(Ll1Pattern.compile(A + " " + B).matcher(A + " " + B).matches(), is(true));
    assertThat(Ll1Pattern.compile(A + " " + B).matcher(A + B).matches(), is(false));
  }

  @Test
  public void whitespaceInsideCharacterClass_staysSignificant() {
    // COMMENTS never applies inside "[...]" -- a space there is an ordinary member character.
    assertThat(Ll1Pattern.compile("(?x)[" + A + " " + B + "]").matcher(A).matches(), is(true));
    assertThat(Ll1Pattern.compile("(?x)[" + A + " " + B + "]").matcher(" ").matches(), is(true));
    assertThat(Ll1Pattern.compile("(?x)[" + A + " " + B + "]").matcher(C).matches(), is(false));
  }
}

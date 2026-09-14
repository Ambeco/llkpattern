package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {n}/{n,} forms (not covered by QuantifierAndCaptureTest, which only covers {n,m}) and reluctant
 * ('?' suffix)/possessive ('+' suffix) quantifier modifiers on every quantifier kind. design.md
 * says these are accepted and parsed but are no-ops at match time (this engine has no backtracking
 * to make reluctant/possessive meaningfully different from greedy) -- these tests confirm that's
 * actually true end-to-end, per remaining_work.md's instruction not to just assume it.
 *
 * <p>Uses a supplementary (astral) code point, U+10000, as the quantified atom throughout -- see
 * SupplementaryPatternTextTest: a quantifier suffix must apply to the whole code point, and the
 * parser's own lookahead must correctly resume right after one to detect that suffix.
 */
@RunWith(JUnit4.class)
public class QuantifierModifierTest {
  // U+10000, standing in for 'a' below.
  private static final String A = "𐀀";

  private static String rep(int n) {
    // Not String.repeat() -- this project targets Java 8 (llkpattern/build.gradle), predating it.
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(A);
    }
    return sb.toString();
  }

  private static boolean matches(String pattern, String input) {
    return Ll1Pattern.compile(pattern).matcher(input).matches();
  }

  // --- {n}: exactly n ---

  @Test
  public void exactCount_matchesExactlyN() {
    assertThat(matches(A + "{3}", rep(3)), is(true));
  }

  @Test
  public void exactCount_rejectsFewer() {
    assertThat(matches(A + "{3}", rep(2)), is(false));
  }

  @Test
  public void exactCount_rejectsMore() {
    assertThat(matches(A + "{3}", rep(4)), is(false));
  }

  // --- {n,}: n or more ---

  @Test
  public void atLeast_rejectsFewer() {
    assertThat(matches(A + "{2,}", rep(1)), is(false));
  }

  @Test
  public void atLeast_matchesExactlyN() {
    assertThat(matches(A + "{2,}", rep(2)), is(true));
  }

  @Test
  public void atLeast_matchesMore() {
    assertThat(matches(A + "{2,}", rep(5)), is(true));
  }

  // --- Reluctant ('?' suffix): parses and behaves the same as greedy (no backtracking engine) ---

  @Test
  public void reluctant_star_behavesAsGreedyNoOp() {
    assertThat(matches(A + "*?", rep(3)), is(true));
    assertThat(matches(A + "*?", ""), is(true));
  }

  @Test
  public void reluctant_plus_behavesAsGreedyNoOp() {
    assertThat(matches(A + "+?", rep(3)), is(true));
    assertThat(matches(A + "+?", ""), is(false));
  }

  @Test
  public void reluctant_optional_behavesAsGreedyNoOp() {
    assertThat(matches(A + "??", A), is(true));
    assertThat(matches(A + "??", ""), is(true));
  }

  @Test
  public void reluctant_exactCount_behavesAsGreedyNoOp() {
    assertThat(matches(A + "{3}?", rep(3)), is(true));
  }

  @Test
  public void reluctant_atLeast_behavesAsGreedyNoOp() {
    assertThat(matches(A + "{2,}?", rep(5)), is(true));
  }

  @Test
  public void reluctant_bounded_behavesAsGreedyNoOp() {
    assertThat(matches(A + "{2,3}?", rep(3)), is(true));
    assertThat(matches(A + "{2,3}?", rep(4)), is(false));
  }

  // --- Possessive ('+' suffix): parses and behaves the same as greedy (no backtracking engine) ---

  @Test
  public void possessive_star_behavesAsGreedyNoOp() {
    assertThat(matches(A + "*+", rep(3)), is(true));
  }

  @Test
  public void possessive_plus_behavesAsGreedyNoOp() {
    assertThat(matches(A + "++", rep(3)), is(true));
    assertThat(matches(A + "++", ""), is(false));
  }

  @Test
  public void possessive_optional_behavesAsGreedyNoOp() {
    assertThat(matches(A + "?+", A), is(true));
  }

  @Test
  public void possessive_boundedQuantifier_behavesAsGreedyNoOp() {
    assertThat(matches(A + "{2,3}+", rep(3)), is(true));
    assertThat(matches(A + "{2,3}+", rep(4)), is(false));
  }
}

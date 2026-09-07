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
 */
@RunWith(JUnit4.class)
public class QuantifierModifierTest {
  private static boolean matches(String pattern, String input) {
    return Ll1Pattern.compile(pattern).matcher(input).matches();
  }

  // --- {n}: exactly n ---

  @Test
  public void exactCount_matchesExactlyN() {
    assertThat(matches("a{3}", "aaa"), is(true));
  }

  @Test
  public void exactCount_rejectsFewer() {
    assertThat(matches("a{3}", "aa"), is(false));
  }

  @Test
  public void exactCount_rejectsMore() {
    assertThat(matches("a{3}", "aaaa"), is(false));
  }

  // --- {n,}: n or more ---

  @Test
  public void atLeast_rejectsFewer() {
    assertThat(matches("a{2,}", "a"), is(false));
  }

  @Test
  public void atLeast_matchesExactlyN() {
    assertThat(matches("a{2,}", "aa"), is(true));
  }

  @Test
  public void atLeast_matchesMore() {
    assertThat(matches("a{2,}", "aaaaa"), is(true));
  }

  // --- Reluctant ('?' suffix): parses and behaves the same as greedy (no backtracking engine) ---

  @Test
  public void reluctant_star_behavesAsGreedyNoOp() {
    assertThat(matches("a*?", "aaa"), is(true));
    assertThat(matches("a*?", ""), is(true));
  }

  @Test
  public void reluctant_plus_behavesAsGreedyNoOp() {
    assertThat(matches("a+?", "aaa"), is(true));
    assertThat(matches("a+?", ""), is(false));
  }

  @Test
  public void reluctant_optional_behavesAsGreedyNoOp() {
    assertThat(matches("a??", "a"), is(true));
    assertThat(matches("a??", ""), is(true));
  }

  @Test
  public void reluctant_exactCount_behavesAsGreedyNoOp() {
    assertThat(matches("a{3}?", "aaa"), is(true));
  }

  @Test
  public void reluctant_atLeast_behavesAsGreedyNoOp() {
    assertThat(matches("a{2,}?", "aaaaa"), is(true));
  }

  @Test
  public void reluctant_bounded_behavesAsGreedyNoOp() {
    assertThat(matches("a{2,3}?", "aaa"), is(true));
    assertThat(matches("a{2,3}?", "aaaa"), is(false));
  }

  // --- Possessive ('+' suffix): parses and behaves the same as greedy (no backtracking engine) ---

  @Test
  public void possessive_star_behavesAsGreedyNoOp() {
    assertThat(matches("a*+", "aaa"), is(true));
  }

  @Test
  public void possessive_plus_behavesAsGreedyNoOp() {
    assertThat(matches("a++", "aaa"), is(true));
    assertThat(matches("a++", ""), is(false));
  }

  @Test
  public void possessive_optional_behavesAsGreedyNoOp() {
    assertThat(matches("a?+", "a"), is(true));
  }

  @Test
  public void possessive_boundedQuantifier_behavesAsGreedyNoOp() {
    assertThat(matches("a{2,3}+", "aaa"), is(true));
    assertThat(matches("a{2,3}+", "aaaa"), is(false));
  }
}

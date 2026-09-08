package com.tbohne.llkpattern;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Combinatorial coverage for the entry-point cycle guard (see design.md's "Entry-point
 * computation vs. matcher compilation" section): builds a bounded set of nested-quantifier
 * patterns -- an outer group containing one or two quantified atoms, itself quantified -- and
 * asserts, for every one, that {@code Ll1Pattern.compile()} either succeeds or throws {@code
 * PatternSyntaxException}. This does NOT assert match *results* combinatorially (that needs
 * {@code java.util.regex} as an oracle -- the scraped-corpus differential harness already does
 * that job); it only proves the guard added for "(a?)+"-style nullable loop bodies is reachable
 * for a wide range of shapes and never escapes as some other Throwable (a stack overflow, an NPE,
 * or the internal {@code PatternConstruct.EntryPointCycleException} itself leaking uncaught).
 *
 * <p>Kept deliberately small (low hundreds of patterns, each just a compile -- no matching) so it
 * stays fast enough to run "frequently" per the project owner's stated testing preference, rather
 * than growing into a general fuzzer.
 */
@RunWith(JUnit4.class)
public class NestedQuantifierCombinatorialTest {

  private static final String[] ATOMS = {"a", "[ab]", "."};
  private static final String[] QUANTIFIERS = {"", "?", "*", "+", "{1,2}"};

  // A smaller set for the two-atom inner body (structure B below), to keep the combinatorial
  // blowup bounded: 2 atoms x 4 quantifiers x 2 atoms x 4 quantifiers x 4 outer quantifiers = 256.
  private static final String[] SMALL_ATOMS = {"a", "."};
  private static final String[] SMALL_QUANTIFIERS = {"", "?", "*", "+"};

  @Test
  public void singleAtomInnerGroup_neverThrowsUnexpectedly() {
    // "(<atom><innerQ>)<outerQ>" -- e.g. "(a?)+", the exact shape of the reported bug.
    List<String> patterns = new ArrayList<>();
    for (String atom : ATOMS) {
      for (String innerQ : QUANTIFIERS) {
        for (String outerQ : QUANTIFIERS) {
          patterns.add("(" + atom + innerQ + ")" + outerQ);
        }
      }
    }
    assertTrue("expected a non-trivial combinatorial sweep", patterns.size() >= 50);
    compileAllExpectingOnlyPatternSyntaxException(patterns);
  }

  @Test
  public void twoAtomInnerGroup_neverThrowsUnexpectedly() {
    // "(<atom1><q1><atom2><q2>)<outerQ>" -- e.g. "(a(b)?)+"'s flat-sequence shape, and the
    // deeper "(a?b?)+" nullable-prefix shape.
    List<String> patterns = new ArrayList<>();
    for (String atom1 : SMALL_ATOMS) {
      for (String q1 : SMALL_QUANTIFIERS) {
        for (String atom2 : SMALL_ATOMS) {
          for (String q2 : SMALL_QUANTIFIERS) {
            for (String outerQ : SMALL_QUANTIFIERS) {
              patterns.add("(" + atom1 + q1 + atom2 + q2 + ")" + outerQ);
            }
          }
        }
      }
    }
    assertTrue("expected a non-trivial combinatorial sweep", patterns.size() >= 200);
    compileAllExpectingOnlyPatternSyntaxException(patterns);
  }

  @Test
  public void nestedGroupInnerGroup_neverThrowsUnexpectedly() {
    // "(<atom1><q1>(<atom2><q2>)<innerQ>)<outerQ>" -- the exact "(a(b)?)+" shape with a real
    // nested (capturing) group, not just a quantified atom, as the inner body.
    List<String> patterns = new ArrayList<>();
    for (String atom1 : SMALL_ATOMS) {
      for (String q1 : SMALL_QUANTIFIERS) {
        for (String atom2 : SMALL_ATOMS) {
          for (String q2 : SMALL_QUANTIFIERS) {
            for (String outerQ : SMALL_QUANTIFIERS) {
              patterns.add("(" + atom1 + q1 + "(" + atom2 + q2 + ")" + ")" + outerQ);
            }
          }
        }
      }
    }
    assertTrue("expected a non-trivial combinatorial sweep", patterns.size() >= 200);
    compileAllExpectingOnlyPatternSyntaxException(patterns);
  }

  private static void compileAllExpectingOnlyPatternSyntaxException(List<String> patterns) {
    int compiled = 0;
    int rejected = 0;
    List<String> unexpectedFailures = new ArrayList<>();
    for (String pattern : patterns) {
      try {
        Ll1Pattern.compile(pattern);
        compiled++;
      } catch (PatternSyntaxException expected) {
        rejected++;
      } catch (Throwable unexpected) {
        unexpectedFailures.add(pattern + " -> " + unexpected);
      }
    }
    if (!unexpectedFailures.isEmpty()) {
      fail(
          unexpectedFailures.size()
              + " of "
              + patterns.size()
              + " patterns threw something other than PatternSyntaxException:\n"
              + String.join("\n", unexpectedFailures));
    }
    System.out.println(
        "NestedQuantifierCombinatorialTest: "
            + compiled
            + " compiled, "
            + rejected
            + " rejected (PatternSyntaxException), "
            + patterns.size()
            + " total.");
  }
}

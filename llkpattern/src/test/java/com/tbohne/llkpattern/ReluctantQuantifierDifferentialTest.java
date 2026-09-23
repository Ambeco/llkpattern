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
 * Differential tests of reluctant (and possessive) quantifier modifiers against java.util.regex,
 * across matches()/lookingAt()/find() and a matrix of inputs/regions. See design.md's
 * "Quantifier/loop compilation" section (the {@code ReluctantLoopMatcherConstruct}/{@code
 * exitIsPureEnd} part) for what this is checking.
 */
@RunWith(JUnit4.class)
public class ReluctantQuantifierDifferentialTest {
  private static final String[] REAL_PATTERNS = {
    "a+?", "a*?", "a??", "a{2,4}?", "a{2,}?", "a{0,3}?",
    "(a+?)", "(a)+?", "(a)*?",
    "a+?b?", "a+?b", "a+?$", "a+?\\b",
    "(?:ab)+?", "[ab]+?c?", "x(a+?)", "(a+?|b)",
    "a++", "a*+", "a?+", "a{2,4}+",
    "a+?b*?", "a*?b+?",
    "(a+?)(b+?)",
    // `next` is itself a dispatch chain candidate (gated, not a plain pass-through) -- exercises
    // MatcherConstruct.exitIsPureEnd's entrySet-gating check. (`a+?(?:bc)?` and `a+?(?:b+|c)?` are
    // deliberately excluded here: both hit this engine's separate, pre-existing "a multi-character
    // optional that matches part of itself then fails isn't retried" limitation -- confirmed by
    // reproducing the same failure with a plain greedy `a+(?:bc)?`/`a+(?:b+|c)?` -- so they're not a
    // reluctant-quantifier question at all; see README's "Intentional differences" list.)
    "a+?(?:b|c)?", "a+?(b)?",
  };

  // Deliberately NOT in REAL_PATTERNS above: "\w+?\B", "a+?\B", "(?m)[a\n]+?$", "(?m)[a\n]+?^" --
  // a reluctant loop immediately followed only by a conditional zero-width construct that CAN
  // succeed mid-run (not just at end-of-string). MatcherConstruct.exitIsPureEnd conservatively
  // leaves these greedy (see its own doc) -- a known, NOT-yet-fixed gap (not a design choice; see
  // remaining_work.md), pinned in KnownDivergenceTest instead of asserted here.

  private static final String[] INPUTS = {
    "", "a", "aa", "aaa", "aaaa", "aaaaa", "b", "ab", "aab", "aaab", "aaaab",
    "ba", "aba", "abab", "xaaa", "aaax", "aaabbb", "abc", "aac", "aabc",
    "a\nb", "aa\naa", "\na",
  };

  private static final int[] FLAG_SETS = {
    0, Ll1Pattern.CASE_INSENSITIVE,
  };

  /** Compiled-pattern accounting: a pattern this engine rejects as ambiguous is silently skipped
   *  by the loops below, which would otherwise let a typo in REAL_PATTERNS pass by never actually
   *  being checked against anything -- see CLAUDE.md's differential-test workflow. */
  private static final java.util.Set<String> compiledPatterns = new java.util.TreeSet<>();
  private static final java.util.Set<String> rejectedPatterns = new java.util.TreeSet<>();

  @Test
  public void everyPatternCompilesForAtLeastOneFlagSet() {
    for (String p : REAL_PATTERNS) {
      boolean anyCompiled = false;
      for (int flags : FLAG_SETS) {
        try {
          Ll1Pattern.compile(p, flags);
          anyCompiled = true;
        } catch (RuntimeException e) {
          // fine -- ambiguous for this engine, checked below via compiledPatterns/rejectedPatterns
        }
      }
      assertThat("expected /" + p + "/ to compile under at least one flag set", anyCompiled, is(true));
    }
  }

  @Test
  public void matchesLookingAtAndFind_matchJavaUtilRegex() {
    List<String> divergences = new ArrayList<>();
    for (String p : REAL_PATTERNS) {
      for (int flags : FLAG_SETS) {
        Pattern jp = Pattern.compile(p, flags);
        Ll1Pattern lp;
        try {
          lp = Ll1Pattern.compile(p, flags);
          compiledPatterns.add(p);
        } catch (RuntimeException e) {
          rejectedPatterns.add(p + " (flags " + flags + "): " + e);
          continue; // ambiguous for this engine
        }
        for (String in : INPUTS) {
          for (int[] region : regionsOf(in)) {
            for (int op = 0; op < 3; op++) {
              java.util.regex.Matcher jm = jp.matcher(in);
              Matcher lm = lp.matcher(in);
              jm.region(region[0], region[1]);
              lm.region(region[0], region[1]);
              String opName;
              boolean jr;
              boolean lr;
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
              String ctx = opName + " /" + p + "/ flags " + flags + " on \"" + in + "\" region ["
                  + region[0] + "," + region[1] + ")";
              if (jr != lr) {
                divergences.add(ctx + ": result java=" + jr + " llk=" + lr);
                continue;
              }
              if (!jr) {
                continue;
              }
              if (jm.start() != lm.start() || jm.end() != lm.end()) {
                divergences.add(ctx + ": span java=[" + jm.start() + "," + jm.end() + ") llk=["
                    + lm.start() + "," + lm.end() + ")");
              }
              int groups = Math.min(jm.groupCount(), lm.groupCount());
              for (int g = 1; g <= groups; g++) {
                if (!java.util.Objects.equals(jm.group(g), lm.group(g))) {
                  divergences.add(ctx + ": group(" + g + ") java=" + jm.group(g) + " llk=" + lm.group(g));
                }
              }
              if (jm.hitEnd() != lm.hitEnd()) {
                divergences.add(ctx + ": hitEnd java=" + jm.hitEnd() + " llk=" + lm.hitEnd());
              }
              if (jm.requireEnd() != lm.requireEnd()) {
                divergences.add(ctx + ": requireEnd java=" + jm.requireEnd() + " llk=" + lm.requireEnd());
              }
            }
          }
        }
      }
    }
    assertThat(
        "compiled: " + compiledPatterns + "\nrejected: " + rejectedPatterns + "\n\ndivergences ("
            + divergences.size() + "):\n" + String.join("\n", divergences),
        divergences, is(java.util.Collections.emptyList()));
  }

  /** Every region {@code (s,e)} of {@code in} -- see CLAUDE.md's differential-test workflow ("Diff
   *  the full matrix ... every region (s,e) of short inputs"). This is what actually caught the
   *  \B-near-regionEnd bug (remaining_work.md): a whole-string-plus-a-few-samples approximation
   *  missed it entirely. */
  private static List<int[]> regionsOf(String in) {
    List<int[]> regions = new ArrayList<>();
    int len = in.length();
    for (int s = 0; s <= len; s++) {
      for (int e = s; e <= len; e++) {
        regions.add(new int[] {s, e});
      }
    }
    return regions;
  }

  @Test
  public void find_loop_findsAllMatches_matchJavaUtilRegex() {
    List<String> divergences = new ArrayList<>();
    for (String p : REAL_PATTERNS) {
      for (String in : INPUTS) {
        Pattern jp = Pattern.compile(p);
        Ll1Pattern lp;
        try {
          lp = Ll1Pattern.compile(p);
        } catch (RuntimeException e) {
          continue;
        }
        java.util.regex.Matcher jm = jp.matcher(in);
        Matcher lm = lp.matcher(in);
        List<String> jSpans = new ArrayList<>();
        List<String> lSpans = new ArrayList<>();
        while (jm.find()) {
          jSpans.add("[" + jm.start() + "," + jm.end() + ")");
        }
        while (lm.find()) {
          lSpans.add("[" + lm.start() + "," + lm.end() + ")");
        }
        if (!jSpans.equals(lSpans)) {
          divergences.add("find-loop /" + p + "/ on \"" + in + "\": java=" + jSpans + " llk=" + lSpans);
        }
      }
    }
    assertThat(divergences.size() + ":\n" + String.join("\n", divergences), is("0:\n"));
  }
}

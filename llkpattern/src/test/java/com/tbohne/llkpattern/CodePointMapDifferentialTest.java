package com.tbohne.llkpattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import com.tbohne.llkpattern.CodePointMap.Range;
import java.util.Random;
import java.util.TreeMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Randomized differential test: applies the same random sequence of {@code put}/{@code remove} to
 * a {@link TreeCodePointMap} (the existing, presumed-correct Guava-backed implementation) and an
 * {@link ArrayCodePointMap} (the new array-backed one), then checks they agree -- both at sampled
 * code points and, after normalizing away each implementation's own coalescing behavior (see
 * {@link ArrayCodePointMap}'s class doc), on their full entry sets. Code points are drawn from
 * across the whole domain, including near/at {@link CodePointMap#MAX_CODE_POINT} (which straddles
 * the plane-16 boundary that a naive signed-int packing of the code point would get wrong) and
 * ranges long enough to exceed {@code ArrayCodePointMap}'s per-entry packing limit.
 */
@RunWith(JUnit4.class)
public class CodePointMapDifferentialTest {

  @Test
  public void randomPutRemoveSequences_agreeWithTreeCodePointMap() {
    Random random = new Random(42);
    for (int trial = 0; trial < 200; trial++) {
      TreeCodePointMap<String> expected = new TreeCodePointMap<>();
      ArrayCodePointMap<String> actual = new ArrayCodePointMap<>();
      for (int op = 0; op < 50; op++) {
        int min = randomCodePoint(random);
        int span = 1 + random.nextInt(5000); // deliberately exceeds the 2048-per-entry limit
        int max = Math.min(CodePointMap.MAX_CODE_POINT + 1, min + span);
        if (max <= min) {
          continue;
        }
        if (random.nextBoolean()) {
          String value = "v" + random.nextInt(4);
          expected.put(min, max, value);
          actual.put(min, max, value);
        } else {
          expected.remove(min, max);
          actual.remove(min, max);
        }
      }
      assertAgree(trial, expected, actual);
    }
  }

  private static int randomCodePoint(Random random) {
    // Bias sampling toward the 0x0FFFxx-0x10xxxx boundary, since that's where a signed-int
    // packing bug (min << 11 setting the sign bit) would show up.
    int base = 0x100000 - 50 + random.nextInt(150);
    return Math.max(0, Math.min(CodePointMap.MAX_CODE_POINT, base));
  }

  private static void assertAgree(int trial, TreeCodePointMap<String> expected, ArrayCodePointMap<String> actual) {
    // Sample every code point actually touched by either map, plus their immediate neighbors.
    TreeMap<Integer, Boolean> toCheck = new TreeMap<>();
    for (java.util.Map.Entry<Range, String> e : expected.entrySet()) {
      toCheck.put(Math.max(0, e.getKey().min - 1), true);
      toCheck.put(e.getKey().min, true);
      toCheck.put(e.getKey().max - 1, true);
      toCheck.put(Math.min(CodePointMap.MAX_CODE_POINT, e.getKey().max), true);
    }
    for (java.util.Map.Entry<Range, String> e : actual.entrySet()) {
      toCheck.put(Math.max(0, e.getKey().min - 1), true);
      toCheck.put(e.getKey().min, true);
      toCheck.put(e.getKey().max - 1, true);
      toCheck.put(Math.min(CodePointMap.MAX_CODE_POINT, e.getKey().max), true);
    }
    for (int cp : toCheck.keySet()) {
      String expectedValue = expected.get(cp);
      String actualValue = actual.get(cp);
      if (!java.util.Objects.equals(expectedValue, actualValue)) {
        fail(
            "trial "
                + trial
                + ": disagreement at code point "
                + cp
                + " (0x"
                + Integer.toHexString(cp)
                + "): TreeCodePointMap="
                + expectedValue
                + ", ArrayCodePointMap="
                + actualValue);
      }
    }
    assertThat(
        "trial " + trial + ": normalized entry sets differ",
        normalize(actual.entrySet()),
        org.hamcrest.CoreMatchers.equalTo(normalize(expected.entrySet())));
  }

  /**
   * Re-splits every entry into individual code points and re-coalesces adjacent equal values, so
   * the two implementations' differing internal chunking (TreeCodePointMap never coalesces at
   * all; ArrayCodePointMap coalesces but caps each entry at 2048 code points) doesn't cause a
   * false mismatch -- only the logical mapping is compared.
   */
  private static java.util.List<Range> normalize(java.util.Set<java.util.Map.Entry<Range, String>> entries) {
    TreeMap<Integer, String> byCodePoint = new TreeMap<>();
    for (java.util.Map.Entry<Range, String> e : entries) {
      for (int cp = e.getKey().min; cp < e.getKey().max; cp++) {
        byCodePoint.put(cp, e.getValue());
      }
    }
    java.util.List<Range> result = new java.util.ArrayList<>();
    Integer runStart = null;
    Integer runEnd = null;
    String runValue = null;
    for (java.util.Map.Entry<Integer, String> e : byCodePoint.entrySet()) {
      if (runStart != null && runEnd == e.getKey() && runValue.equals(e.getValue())) {
        runEnd = e.getKey() + 1;
      } else {
        if (runStart != null) {
          result.add(new Range(runStart, runEnd));
        }
        runStart = e.getKey();
        runEnd = e.getKey() + 1;
        runValue = e.getValue();
      }
    }
    if (runStart != null) {
      result.add(new Range(runStart, runEnd));
    }
    return result;
  }
}

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

  @Test
  public void randomPutAll_agreesWithTreeCodePointMap() {
    // Dedicated coverage for ArrayCodePointMap.putAll's sorted-merge sweep (see its class doc) --
    // separate from the put()/remove() trials above, since it's a large enough algorithm on its
    // own to deserve its own randomized stress rather than only the tiny hand-written union_*
    // cases in CodePointMapTestBase.
    Random random = new Random(7);
    for (int trial = 0; trial < 200; trial++) {
      TreeCodePointMap<String> expectedBase = randomMap(random, new TreeCodePointMap<>());
      ArrayCodePointMap<String> actualBase = new ArrayCodePointMap<>();
      actualBase.putAll(expectedBase);
      TreeCodePointMap<String> expectedOther = randomMap(random, new TreeCodePointMap<>());
      ArrayCodePointMap<String> actualOther = new ArrayCodePointMap<>();
      actualOther.putAll(expectedOther);

      TreeCodePointMap<String> expected = new TreeCodePointMap<>(expectedBase);
      expected.putAll(expectedOther);
      ArrayCodePointMap<String> actual = new ArrayCodePointMap<>(actualBase);
      actual.putAll(actualOther);

      assertAgree(trial, expected, actual);
    }
  }

  @Test
  public void complement_agreesWithTreeCodePointMap() {
    // Covers the elseValue/"punched hole" machinery described on CodePointMap#getElseValue:
    // complement() of a random map, complement-of-a-complement (double negation should recover
    // the original, minus the else-value's own identity), and union/intersection mixing a
    // complement map with an ordinary one -- the "tricky bit" the elseValue design exists for.
    // Fewer trials than the other differential tests: normalize() below re-splits every entry
    // into individual code points, and an elseValue-bearing map's gap-fill entries can span
    // nearly the whole [0, MAX_CODE_POINT] domain, making each trial here far more expensive than
    // the small hand-built maps the other tests compare.
    Random random = new Random(99);
    for (int trial = 0; trial < 20; trial++) {
      TreeCodePointMap<String> expectedBase = randomMap(random, new TreeCodePointMap<>());
      ArrayCodePointMap<String> actualBase = new ArrayCodePointMap<>();
      actualBase.putAll(expectedBase);

      TreeCodePointMap<String> expectedComplement = (TreeCodePointMap<String>) expectedBase.complement("ELSE");
      ArrayCodePointMap<String> actualComplement = (ArrayCodePointMap<String>) actualBase.complement("ELSE");
      assertAgree(trial, expectedComplement, actualComplement);

      // Double complement: should land back on the original map (the outer else-value only
      // matters where the inner complement had none, i.e. nowhere, since it's total).
      TreeCodePointMap<String> expectedDouble =
          (TreeCodePointMap<String>) expectedComplement.complement("UNUSED");
      ArrayCodePointMap<String> actualDouble = (ArrayCodePointMap<String>) actualComplement.complement("UNUSED");
      assertAgree(trial, expectedDouble, actualDouble);

      // Union of a complement with an ordinary map.
      TreeCodePointMap<String> expectedOther = randomMap(random, new TreeCodePointMap<>());
      ArrayCodePointMap<String> actualOther = new ArrayCodePointMap<>();
      actualOther.putAll(expectedOther);
      TreeCodePointMap<String> expectedUnion = new TreeCodePointMap<>(expectedComplement);
      expectedUnion.putAll(expectedOther);
      ArrayCodePointMap<String> actualUnion = new ArrayCodePointMap<>(actualComplement);
      actualUnion.putAll(actualOther);
      assertAgree(trial, expectedUnion, actualUnion);

      // Intersection restricted to a window, and intersectionRejectingConflicts against a
      // non-conflicting slice of the same complement (itself, restricted) -- exercises
      // entriesOverlapping's elseValue fallback path in both implementations.
      int min = randomCodePoint(random);
      int max = Math.min(CodePointMap.MAX_CODE_POINT + 1, min + 1 + random.nextInt(5000));
      if (max > min) {
        TreeCodePointMap<String> expectedIx = (TreeCodePointMap<String>) expectedComplement.intersection(min, max);
        ArrayCodePointMap<String> actualIx = (ArrayCodePointMap<String>) actualComplement.intersection(min, max);
        assertAgree(trial, expectedIx, actualIx);

        TreeCodePointMap<String> expectedSelfIx =
            (TreeCodePointMap<String>) expectedComplement.intersectionRejectingConflicts(expectedIx);
        ArrayCodePointMap<String> actualSelfIx =
            (ArrayCodePointMap<String>) actualComplement.intersectionRejectingConflicts(actualIx);
        assertAgree(trial, expectedSelfIx, actualSelfIx);
      }
    }
  }

  private static TreeCodePointMap<String> randomMap(Random random, TreeCodePointMap<String> map) {
    for (int op = 0; op < 20; op++) {
      int min = randomCodePoint(random);
      int span = 1 + random.nextInt(3000);
      int max = Math.min(CodePointMap.MAX_CODE_POINT + 1, min + span);
      if (max > min) {
        map.put(min, max, "v" + random.nextInt(4));
      }
    }
    return map;
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

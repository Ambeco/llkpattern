package com.tbohne.llkpattern;

import java.util.Arrays;

/**
 * Accumulates code point ranges from possibly many sources without maintaining sort order or
 * coalescing as each one is added -- the set counterpart of {@link CodePointMapBuilder}, used
 * wherever the accumulated ranges are homogeneous membership (every entry the same "in the set",
 * with nothing to disagree on). Unlike {@link CodePointMapBuilder#build}, there's no conflict to
 * detect here: two overlapping ranges from different sources both just mean "these code points are
 * in the set" -- always mergeable, never a conflict -- so {@link #build} is a plain sort-and-coalesce,
 * no {@code ConflictHandler} needed at all.
 *
 * <p>Not itself a {@link CodePointSet} -- it has no query methods, only {@link #add}/{@link
 * #build}. Reusable for multiple {@link #build} calls (state isn't consumed), but there's normally
 * no reason to.
 */
final class CodePointSetBuilder {
  private static final int INITIAL_CAPACITY = 4;

  private int[] mins;
  private int[] maxs;
  private int size = 0;

  CodePointSetBuilder() {
    mins = new int[INITIAL_CAPACITY];
    maxs = new int[INITIAL_CAPACITY];
  }

  /** Records that {@code [min, max)} is in the set. Order doesn't matter -- see class doc. */
  void add(int min, int max) {
    if (mins.length == size) {
      int newCapacity = mins.length + (mins.length >> 1) + 1;
      mins = Arrays.copyOf(mins, newCapacity);
      maxs = Arrays.copyOf(maxs, newCapacity);
    }
    mins[size] = min;
    maxs[size] = max;
    size++;
  }

  void add(int codePoint) {
    add(codePoint, codePoint + 1);
  }

  /**
   * Adds every one of {@code source}'s ranges -- via {@link CodePointSet#forEachRange}, so no
   * {@code Range} is allocated per source entry.
   */
  void addAll(CodePointSet source) {
    source.forEachRange(this::add);
  }

  /**
   * Sorts and coalesces every range added so far into a single {@link CodePointSet}. Overlapping or
   * touching ranges always merge (see class doc -- there's no value to disagree on), so this never
   * throws.
   */
  CodePointSet.MutableCodePointSet build() {
    sortInPlaceByMin();
    // Merge pass, compacting forward over the SAME mins/maxs arrays -- see
    // CodePointMapBuilder#build's identical structure/reasoning, minus the value-conflict check.
    int outSize = 0;
    for (int i = 0; i < size; i++) {
      int min = mins[i];
      int max = maxs[i];
      if (outSize > 0) {
        int lastIdx = outSize - 1;
        if (min <= maxs[lastIdx]) { // overlaps or touches the last accepted range
          maxs[lastIdx] = Math.max(maxs[lastIdx], max);
          continue;
        }
      }
      mins[outSize] = min;
      maxs[outSize] = max;
      outSize++;
    }
    return new ArrayCodePointSet(mins, maxs, outSize);
  }

  /**
   * Insertion sort of {@code mins[0..size)}/{@code maxs} in lockstep -- see {@link
   * CodePointMapBuilder#sortInPlaceByMin}'s identical reasoning (small, near-sorted inputs at every
   * real call site).
   */
  private void sortInPlaceByMin() {
    for (int i = 1; i < size; i++) {
      int min = mins[i];
      int max = maxs[i];
      int j = i - 1;
      while (j >= 0 && mins[j] > min) {
        mins[j + 1] = mins[j];
        maxs[j + 1] = maxs[j];
        j--;
      }
      mins[j + 1] = min;
      maxs[j + 1] = max;
    }
  }
}

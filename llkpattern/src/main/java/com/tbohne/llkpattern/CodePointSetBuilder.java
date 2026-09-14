package com.tbohne.llkpattern;

import java.util.Arrays;

/**
 * Accumulates code point ranges from possibly many sources without maintaining sort order or
 * coalescing as each one is added, then sorts and coalesces them all at once in {@link #build} --
 * a plain O(1)-amortized append per {@link #add} instead of one sorted-insert each, for every
 * source whose ranges are homogeneous membership (every entry the same "in the set", with nothing
 * to disagree on). Two overlapping ranges from different sources both just mean "these code points
 * are in the set" -- always mergeable, never a conflict -- so {@link #build} never throws.
 *
 * <p>Not itself a {@link CodePointSet} -- it has no query methods, only {@link #add}/{@link
 * #build}. Build-once: {@link #build} hands its packed {@code keys} array off to the {@link
 * ArrayCodePointSet} it returns (no defensive copy), so calling it again would re-merge already-
 * compacted {@code mins}/{@code maxs} into a second, aliased set instead of a fresh one -- create a
 * new builder per {@link CodePointSet} instead of reusing one.
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
    // Merge pass, compacting forward over the SAME mins/maxs arrays.
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
    // Pack directly into ArrayCodePointSet's own key format here, into a single correctly-sized
    // array computed up front -- so the constructor it's handed to just takes ownership, with no
    // further allocation of its own. `mins`/`maxs` are left alone rather than reused as scratch --
    // fine, since build-once (see class doc) means there's no second call to pay for it.
    int chunkTotal = 0;
    for (int i = 0; i < outSize; i++) {
      chunkTotal += (maxs[i] - mins[i] + ArrayCodePointSet.MAX_COUNT) / (ArrayCodePointSet.MAX_COUNT + 1);
    }
    int[] keys = new int[chunkTotal];
    int w = 0;
    for (int i = 0; i < outSize; i++) {
      int min = mins[i];
      int max = maxs[i];
      for (int chunkMin = min; chunkMin < max; chunkMin += ArrayCodePointSet.MAX_COUNT + 1) {
        int chunkMax = Math.min(max, chunkMin + ArrayCodePointSet.MAX_COUNT + 1);
        keys[w] = ArrayCodePointSet.packKey(chunkMin, chunkMax - chunkMin - 1);
        w++;
      }
    }
    return new ArrayCodePointSet(keys, w);
  }

  /**
   * Insertion sort of {@code mins[0..size)}/{@code maxs} in lockstep -- worth it over {@code
   * Arrays.sort} despite its worse worst-case complexity, since every real call site's input is
   * small and near-sorted already, where insertion sort's low constant factor wins.
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

package com.tbohne.llkpattern;

import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.Nullable;

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
 * compacted {@code ranges} into a second, aliased set instead of a fresh one -- create a new
 * builder per {@link CodePointSet} instead of reusing one.
 */
final class CodePointSetBuilder {
  private static final int INITIAL_CAPACITY = 4;

  // One packed-long array (min in the high 32 bits, max in the low 32 bits) instead of two int[]
  // arrays -- half the array objects (and half the object-header overhead) for the same content,
  // same reasoning that makes ArrayCodePointSet itself a single array rather than parallel ones.
  // Unlike ArrayCodePointSet's own packed format, min/max here are NOT chunked to a MAX_COUNT-wide
  // span -- each holds a full 32-bit value, so an arbitrarily wide range never needs splitting
  // into multiple entries just to be stored (that would force #build's merge pass to re-chunk
  // combined runs into different boundaries than either original entry had, real complexity this
  // class's only real-world caller, a bracket expression's own literal members, never needs: those
  // are always individual characters or small explicit ranges, never spans over MAX_COUNT code
  // points). Left null until the first #add -- see the field's own former doc, preserved by the
  // lazy-init pattern: a bracket expression's operand run is often entirely named-escape/nested-
  // class members (e.g. "[\d]", "[\p{L}]"), which never call #add at all (see
  // PatternParser#mergeRun, which calls #build unconditionally and discards its -- empty -- result
  // whenever runUnion alone already covers the run).
  private long @Nullable [] ranges;
  private int size = 0;
  // Same meaning as ArrayCodePointSet#invert: whether the ranges added so far ARE the built set
  // (false, the default) or are EXCLUDED from it. Flipping this is just a boolean, exactly as
  // cheap here as ArrayCodePointSet#invert's in-place flip -- #build passes it straight through to
  // the ArrayCodePointSet it constructs, rather than making the caller build a normal set and then
  // pay a separate ArrayCodePointSet#complement array copy to invert it afterward.
  private boolean invert = false;

  private static long pack(int min, int max) {
    return ((long) min << 32) | (max & 0xFFFFFFFFL);
  }

  private static int packedMin(long range) {
    return (int) (range >>> 32);
  }

  private static int packedMax(long range) {
    return (int) range;
  }

  /** Records that {@code [min, max)} is in the set. Order doesn't matter -- see class doc. */
  void add(int min, int max) {
    if (ranges == null) {
      ranges = new long[INITIAL_CAPACITY];
    } else if (ranges.length == size) {
      ranges = Arrays.copyOf(ranges, ranges.length + (ranges.length >> 1) + 1);
    }
    ranges[size] = pack(min, max);
    size++;
  }

  void add(int codePoint) {
    add(codePoint, codePoint + 1);
  }

  /** Flips whether the built set means "these ranges" or "everything but these ranges". */
  void invert() {
    invert = !invert;
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
    // Merge pass, compacting forward over the SAME ranges array.
    int outSize = 0;
    for (int i = 0; i < size; i++) {
      int min = packedMin(ranges[i]);
      int max = packedMax(ranges[i]);
      if (outSize > 0) {
        int lastIdx = outSize - 1;
        int lastMax = packedMax(ranges[lastIdx]);
        if (min <= lastMax) { // overlaps or touches the last accepted range
          if (max > lastMax) {
            ranges[lastIdx] = pack(packedMin(ranges[lastIdx]), max);
          }
          continue;
        }
      }
      ranges[outSize] = pack(min, max);
      outSize++;
    }
    // Pack directly into ArrayCodePointSet's own chunked key format here, into a single
    // correctly-sized array computed up front -- so the constructor it's handed to just takes
    // ownership, with no further allocation of its own. `ranges` is left alone rather than reused
    // as scratch -- fine, since build-once (see class doc) means there's no second call to pay for
    // it. Each merged entry here may span more than ArrayCodePointSet's own MAX_COUNT-wide limit
    // (see this class's own field doc), unlike during accumulation above -- so, unlike #add, this
    // chunks each one into as many packed keys as it needs.
    int chunkTotal = 0;
    for (int i = 0; i < outSize; i++) {
      int min = packedMin(ranges[i]);
      int max = packedMax(ranges[i]);
      chunkTotal += (max - min + ArrayCodePointSet.MAX_COUNT) / (ArrayCodePointSet.MAX_COUNT + 1);
    }
    int[] keys = new int[chunkTotal];
    int w = 0;
    for (int i = 0; i < outSize; i++) {
      int min = packedMin(ranges[i]);
      int max = packedMax(ranges[i]);
      for (int chunkMin = min; chunkMin < max; chunkMin += ArrayCodePointSet.MAX_COUNT + 1) {
        int chunkMax = Math.min(max, chunkMin + ArrayCodePointSet.MAX_COUNT + 1);
        keys[w] = ArrayCodePointSet.packKey(chunkMin, chunkMax - chunkMin - 1);
        w++;
      }
    }
    return new ArrayCodePointSet(keys, w, invert);
  }

  /**
   * Insertion sort of {@code ranges[0..size)} by each entry's packed-in min -- worth it over {@code
   * Arrays.sort} despite its worse worst-case complexity, since every real call site's input is
   * small and near-sorted already, where insertion sort's low constant factor wins. Compares
   * extracted min, not the raw packed {@code long}s directly -- min occupies the packed value's
   * high bits, but a max near the top of the domain can still flip the sign of a lower entry's own
   * packed value, so raw numeric order isn't reliable (same reasoning as
   * ArrayCodePointSet#floorIndex's own comparisons, which never compare raw packed keys either).
   */
  private void sortInPlaceByMin() {
    for (int i = 1; i < size; i++) {
      long range = ranges[i];
      int min = packedMin(range);
      int j = i - 1;
      while (j >= 0 && packedMin(ranges[j]) > min) {
        ranges[j + 1] = ranges[j];
        j--;
      }
      ranges[j + 1] = range;
    }
  }
}

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
 * #build}. Build-once: {@link #build} hands its own {@code keys} array off to the {@link
 * ArrayCodePointSet} it returns (no copy, no separate final array -- the same array {@link #add}
 * appended into is sorted and compacted in place, then given away), nulling this builder's own
 * reference to it so a second {@link #build}/{@link #add} call can't silently corrupt an
 * already-returned, supposedly-immutable set instead of just failing loudly.
 */
final class CodePointSetBuilder {
  private static final int INITIAL_CAPACITY = 4;

  // Packed (min<<11)|count entries -- the exact same compact format ArrayCodePointSet itself
  // uses, not a separate raw-pair representation later packed in #build: #add chunks any range
  // wider than ArrayCodePointSet#MAX_COUNT into multiple packed entries immediately, the same way
  // ArrayCodePointSet#addRange does for an incremental insert. #build's merge pass can then hand
  // its own array straight to ArrayCodePointSet with no further packing/allocation needed -- one
  // array total, not one to accumulate into plus a second, separately-sized one to pack into.
  // Left null until the first #add -- a bracket expression's operand run is often entirely
  // named-escape/nested-class members (e.g. "[\d]", "[\p{L}]"), which never call #add at all (see
  // PatternParser#mergeRun, which calls #build unconditionally and discards its -- empty --
  // result whenever runUnion alone already covers the run).
  private int @Nullable [] keys;
  private int size = 0;
  // Same meaning as ArrayCodePointSet#invert: whether the ranges added so far ARE the built set
  // (false, the default) or are EXCLUDED from it. Flipping this is just a boolean, exactly as
  // cheap here as ArrayCodePointSet#invert's in-place flip -- #build passes it straight through to
  // the ArrayCodePointSet it constructs, rather than making the caller build a normal set and then
  // pay a separate ArrayCodePointSet#complement array copy to invert it afterward.
  private boolean invert = false;
  private boolean built = false;

  /** Records that {@code [min, max)} is in the set. Order doesn't matter -- see class doc. */
  void add(int min, int max) {
    checkNotBuilt();
    // Chunked immediately, not deferred to #build -- see the `keys` field's own doc.
    for (int chunkMin = min; chunkMin < max; chunkMin += ArrayCodePointSet.MAX_COUNT + 1) {
      int chunkMax = Math.min(max, chunkMin + ArrayCodePointSet.MAX_COUNT + 1);
      appendKey(ArrayCodePointSet.packKey(chunkMin, chunkMax - chunkMin - 1));
    }
  }

  void add(int codePoint) {
    add(codePoint, codePoint + 1);
  }

  private void appendKey(int key) {
    if (keys == null) {
      keys = new int[INITIAL_CAPACITY];
    } else if (keys.length == size) {
      keys = Arrays.copyOf(keys, keys.length + (keys.length >> 1) + 1);
    }
    keys[size] = key;
    size++;
  }

  /** Flips whether the built set means "these ranges" or "everything but these ranges". */
  void invert() {
    checkNotBuilt();
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
   * throws. Build-once -- see class doc.
   */
  CodePointSet.MutableCodePointSet build() {
    checkNotBuilt();
    built = true;
    if (keys == null) {
      return new ArrayCodePointSet(new int[0], 0, invert);
    }
    sortInPlaceByMin();
    // Single forward pass: for each maximal run of touching/overlapping entries (by real extent,
    // not by their individual pre-chunked boundaries), re-chunk the run's own full span into
    // however many packed entries it actually needs, writing them back into the SAME array. The
    // write cursor (`outSize`) can never run ahead of the read cursor (`i`): a run built from N
    // input entries can never need MORE than N output chunks (each input entry already covers up
    // to MAX_COUNT+1 code points, so covering the same combined span can't take more chunks than
    // that), so this is safe to compact in place.
    int outSize = 0;
    int i = 0;
    while (i < size) {
      int runMin = ArrayCodePointSet.keyMin(keys[i]);
      int runMax = ArrayCodePointSet.keyMax(keys[i]);
      i++;
      while (i < size && ArrayCodePointSet.keyMin(keys[i]) <= runMax) {
        runMax = Math.max(runMax, ArrayCodePointSet.keyMax(keys[i]));
        i++;
      }
      for (int chunkMin = runMin; chunkMin < runMax; chunkMin += ArrayCodePointSet.MAX_COUNT + 1) {
        int chunkMax = Math.min(runMax, chunkMin + ArrayCodePointSet.MAX_COUNT + 1);
        keys[outSize] = ArrayCodePointSet.packKey(chunkMin, chunkMax - chunkMin - 1);
        outSize++;
      }
    }
    int[] result = keys;
    keys = null;
    return new ArrayCodePointSet(result, outSize, invert);
  }

  private void checkNotBuilt() {
    if (built) {
      throw new IllegalStateException(
          "CodePointSetBuilder already built -- create a new builder per CodePointSet instead of "
              + "reusing one (see class doc)");
    }
  }

  /**
   * Insertion sort of {@code keys[0..size)} by each entry's real (unpacked) min -- worth it over
   * {@code Arrays.sort} despite its worse worst-case complexity, since every real call site's
   * input is small and near-sorted already, where insertion sort's low constant factor wins.
   * Compares extracted min, not the raw packed {@code int}s directly -- {@code min << 11} can
   * overflow into the sign bit well within the Unicode domain (as low as code point 0x100000), so
   * raw numeric order isn't reliable (same reasoning as {@code ArrayCodePointSet#floorIndex}'s own
   * comparisons, which never compare raw packed keys either).
   */
  private void sortInPlaceByMin() {
    for (int i = 1; i < size; i++) {
      int key = keys[i];
      int min = ArrayCodePointSet.keyMin(key);
      int j = i - 1;
      while (j >= 0 && ArrayCodePointSet.keyMin(keys[j]) > min) {
        keys[j + 1] = keys[j];
        j--;
      }
      keys[j + 1] = key;
    }
  }
}

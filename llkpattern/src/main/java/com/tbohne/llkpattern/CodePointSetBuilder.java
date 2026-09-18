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
 * <p>This interface declares only {@link #add}/{@link #addAll}/{@link #invert}/{@link #build} --
 * deliberately not a {@link CodePointSet}, so an in-progress (unsorted) accumulation can never be
 * queried by a caller holding this type and getting a wrong answer back. {@link #create}'s actual
 * implementation ({@code Impl}, below) IS an {@link ArrayCodePointSet} under the hood -- {@link
 * #build} sorts/coalesces its own inherited array in place and returns {@code this}, rather than
 * handing a finished array off to a SEPARATE, freshly-allocated {@link ArrayCodePointSet} -- so a
 * built {@link CodePointSetBuilder} costs exactly one object, the same as directly mutating an
 * {@link ArrayCodePointSet} would, while still getting {@link #add}'s O(1)-amortized append (measured
 * as a real win over {@link ArrayCodePointSet#add}'s binary-search-insert-with-shift for this
 * class's own real caller, a bracket expression's literal members -- see notes.md). Only {@code
 * Impl}'s own methods ever see it as the mutable {@link ArrayCodePointSet} it actually is; every
 * other caller sees only this narrow interface until {@link #build} hands back a plain {@link
 * CodePointSet} -- not even {@link CodePointSet.MutableCodePointSet} -- so `add` isn't reachable
 * post-build through ordinary typed use either.
 */
interface CodePointSetBuilder {
  static CodePointSetBuilder create() {
    return new Impl();
  }

  /** Records that {@code [min, max)} is in the set. Order doesn't matter -- see class doc. */
  void add(int min, int max);

  default void add(int codePoint) {
    add(codePoint, codePoint + 1);
  }

  /**
   * Adds every one of {@code source}'s ranges -- via {@link CodePointSet#forEachRange}, so no
   * {@code Range} is allocated per source entry.
   */
  void addAll(CodePointSet source);

  /** Flips whether the built set means "these ranges" or "everything but these ranges". */
  void invert();

  /**
   * Sorts and coalesces every range added so far into a single {@link CodePointSet}. Overlapping or
   * touching ranges always merge (see class doc -- there's no value to disagree on), so this never
   * throws. Build-once: calling {@link #add}/{@link #addAll}/{@link #invert}/{@link #build} again
   * afterward throws {@link IllegalStateException} instead of silently corrupting the set just
   * returned -- create a new builder per {@link CodePointSet} instead of reusing one.
   */
  CodePointSet build();

  /**
   * A {@link CodePointSetBuilder} that IS an {@link ArrayCodePointSet} -- see the interface's own
   * doc for why. Overrides {@link ArrayCodePointSet#add}, the one method whose semantics genuinely
   * differ while accumulating (unsorted append here, vs. {@link ArrayCodePointSet}'s own
   * sorted-insert-with-shift); {@link #addAll}/{@link #invert} are overridden only to add the
   * build-once guard, then delegate straight to the inherited implementation -- {@link
   * ArrayCodePointSet#addAll}'s own fast path (a plain array copy when this is still empty) and
   * general path (one {@link #add} per source range) both keep working unmodified, the latter
   * correctly reaching THIS class's overridden {@link #add} via ordinary virtual dispatch.
   */
  final class Impl extends ArrayCodePointSet implements CodePointSetBuilder {
    private boolean built = false;

    // Explicit override needed: CodePointSetBuilder#add(int) and MutableCodePointSet#add(int) (via
    // ArrayCodePointSet) both provide unrelated default implementations of the same signature --
    // javac can't pick one on its own. Both just forward to #add(int,int) anyway.
    @Override
    public void add(int codePoint) {
      add(codePoint, codePoint + 1);
    }

    @Override
    public void add(int min, int max) {
      checkNotBuilt();
      // Packed immediately in ArrayCodePointSet's own compact key format -- not deferred to
      // #build -- chunking any range wider than MAX_COUNT into multiple entries here, the same way
      // ArrayCodePointSet#addRange does for an incremental insert.
      for (int chunkMin = min; chunkMin < max; chunkMin += MAX_COUNT + 1) {
        int chunkMax = Math.min(max, chunkMin + MAX_COUNT + 1);
        appendKey(packKey(chunkMin, chunkMax - chunkMin - 1));
      }
    }

    private void appendKey(int key) {
      if (size == keys.length) {
        keys = Arrays.copyOf(keys, keys.length + (keys.length >> 1) + 1);
      }
      keys[size] = key;
      size++;
    }

    @Override
    public void addAll(CodePointSet source) {
      checkNotBuilt();
      super.addAll(source);
    }

    @Override
    public void invert() {
      checkNotBuilt();
      super.invert();
    }

    @Override
    public CodePointSet build() {
      checkNotBuilt();
      built = true;
      sortInPlaceByMin();
      // Single forward pass: for each maximal run of touching/overlapping entries (by real
      // extent, not by their individual pre-chunked boundaries), re-chunk the run's own full span
      // into however many packed entries it actually needs, writing them back into the SAME
      // array. The write cursor can never run ahead of the read cursor: a run built from N input
      // entries can never need MORE than N output chunks (each input entry already covers up to
      // MAX_COUNT+1 code points, so covering the same combined span can't take more chunks than
      // that), so this is safe to compact in place.
      int outSize = 0;
      int i = 0;
      while (i < size) {
        int runMin = keyMin(keys[i]);
        int runMax = keyMax(keys[i]);
        i++;
        while (i < size && keyMin(keys[i]) <= runMax) {
          runMax = Math.max(runMax, keyMax(keys[i]));
          i++;
        }
        for (int chunkMin = runMin; chunkMin < runMax; chunkMin += MAX_COUNT + 1) {
          int chunkMax = Math.min(runMax, chunkMin + MAX_COUNT + 1);
          keys[outSize] = packKey(chunkMin, chunkMax - chunkMin - 1);
          outSize++;
        }
      }
      size = outSize;
      return this;
    }

    private void checkNotBuilt() {
      if (built) {
        throw new IllegalStateException(
            "CodePointSetBuilder already built -- create a new builder per CodePointSet instead "
                + "of reusing one (see class doc)");
      }
    }

    /**
     * Insertion sort of {@code keys[0..size)} by each entry's real (unpacked) min -- worth it over
     * {@code Arrays.sort} despite its worse worst-case complexity, since every real call site's
     * input is small and near-sorted already, where insertion sort's low constant factor wins.
     * Compares extracted min, not the raw packed {@code int}s directly -- {@code min << 11} can
     * overflow the sign bit as low as code point 0x100000, so raw numeric order isn't reliable
     * (same reasoning as {@code ArrayCodePointSet#floorIndex}'s own comparisons, which never
     * compare raw packed keys either).
     */
    private void sortInPlaceByMin() {
      for (int i = 1; i < size; i++) {
        int key = keys[i];
        int min = keyMin(key);
        int j = i - 1;
        while (j >= 0 && keyMin(keys[j]) > min) {
          keys[j + 1] = keys[j];
          j--;
        }
        keys[j + 1] = key;
      }
    }
  }
}

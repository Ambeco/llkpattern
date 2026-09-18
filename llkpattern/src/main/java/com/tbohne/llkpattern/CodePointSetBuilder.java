package com.tbohne.llkpattern;

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
 * implementation ({@link ArrayCodePointSet.CodePointSetBuilderImpl}, nested in {@link
 * ArrayCodePointSet} rather than here since the two are tightly intertwined implementation details
 * of each other) IS an {@link ArrayCodePointSet} under the hood -- {@link #build} sorts/coalesces
 * its own inherited array in place and returns {@code this}, rather than handing a finished array
 * off to a SEPARATE, freshly-allocated {@link ArrayCodePointSet} -- so a built {@link
 * CodePointSetBuilder} costs exactly one object, the same as directly mutating an {@link
 * ArrayCodePointSet} would, while still getting {@link #add}'s O(1)-amortized append (measured as
 * a real win over {@link ArrayCodePointSet#add}'s binary-search-insert-with-shift for this
 * interface's own real caller, a bracket expression's literal members -- see notes.md). Only the
 * implementation's own methods ever see it as the mutable {@link ArrayCodePointSet} it actually is;
 * every other caller sees only this narrow interface until {@link #build} hands back a plain
 * {@link CodePointSet} -- not even {@link CodePointSet.MutableCodePointSet} -- so `add` isn't
 * reachable post-build through ordinary typed use either.
 */
interface CodePointSetBuilder {
  static CodePointSetBuilder create() {
    return new ArrayCodePointSet.CodePointSetBuilderImpl();
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
}

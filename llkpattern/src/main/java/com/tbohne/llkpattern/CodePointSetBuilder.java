package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointSet.MutableCodePointSet;
import org.checkerframework.checker.nullness.qual.Nullable;

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

  /**
   * Combines a run's literal-member builder with its (possibly null) lazily-unioned large sets.
   * The laziness in {@code runUnion} (see {@code PatternParser#parseComplexCharacterRanges}'s doc
   * on that field) only exists to avoid copying a large set's entries into the builder *while the
   * run is still being parsed* -- once the run is finished, the result must be a concrete {@link
   * ArrayCodePointSet} before it can go anywhere near a compiled matcher (as {@code
   * ComplexCharacter.ranges}, a chain node's own {@code entrySet}, etc.), since a {@link
   * UnionCodePointSet}'s {@code contains}/{@code containsAll}/{@code forEachRange} are all
   * measurably more expensive than {@code ArrayCodePointSet}'s -- see its own class doc. So this
   * materializes eagerly here, at the one point (a completed run) where the saved copy would
   * otherwise turn into a permanent cost on the match-time hot path instead of a one-time parse-time
   * saving.
   */
  static CodePointSet mergeRun(CodePointSetBuilder literals, @Nullable CodePointSet runUnion) {
    CodePointSet literalSet = literals.build();
    if (runUnion == null) {
      return literalSet;
    }
    // runUnion is a bare escape/nested-class result (already concrete -- see this method's own
    // recursive use) unless this run combined *multiple* large sets (e.g. "[\d\w]"), in which case
    // it's a UnionCodePointSet that must be materialized here too, same as when literalSet is
    // non-empty -- either way, nothing but a concrete ArrayCodePointSet may leave this method.
    if (literalSet.isEmpty() && !(runUnion instanceof UnionCodePointSet)) {
      return runUnion;
    }
    MutableCodePointSet result = new ArrayCodePointSet();
    result.addAll(runUnion);
    result.addAll(literalSet);
    return result;
  }
}

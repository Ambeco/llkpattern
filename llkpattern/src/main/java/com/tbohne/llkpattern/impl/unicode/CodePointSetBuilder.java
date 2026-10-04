package com.tbohne.llkpattern.impl.unicode;

import com.tbohne.llkpattern.impl.unicode.CodePointSet.MutableCodePointSet;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Accumulates code point ranges from possibly many sources without maintaining sort order or
 * coalescing as each one is added, then sorts and coalesces them all at once in {@link #build} --
 * a plain O(1)-amortized append per {@link #append} instead of one sorted-insert each, for every
 * source whose ranges are homogeneous membership (every entry the same "in the set", with nothing
 * to disagree on). Two overlapping ranges from different sources both just mean "these code points
 * are in the set" -- always mergeable, never a conflict -- so {@link #build} never throws.
 *
 * <p>This interface declares only {@link #append}/{@link #appendAll}/{@link #invert}/{@link #build} --
 * deliberately not a {@link CodePointSet}, so an in-progress (unsorted) accumulation can never be
 * queried by a caller holding this type and getting a wrong answer back. {@link #create}'s actual
 * implementation ({@link ArrayCodePointSet.CodePointSetBuilderImpl}, nested in {@link
 * ArrayCodePointSet} rather than here since the two are tightly intertwined implementation details
 * of each other) IS an {@link ArrayCodePointSet} under the hood -- {@link #build} sorts/coalesces
 * its own inherited array in place and returns {@code this}, rather than handing a finished array
 * off to a SEPARATE, freshly-allocated {@link ArrayCodePointSet} -- so a built {@link
 * CodePointSetBuilder} costs exactly one object, the same as directly mutating an {@link
 * ArrayCodePointSet} would, while still getting {@link #append}'s O(1)-amortized append (measured as
 * a real win over {@link ArrayCodePointSet#insert}'s binary-search-insert-with-shift for this
 * interface's own real caller, a bracket expression's literal members -- see notes.md). Only the
 * implementation's own methods ever see it as the mutable {@link ArrayCodePointSet} it actually is;
 * every other caller sees only this narrow interface until {@link #build} hands back a plain
 * {@link CodePointSet} -- not even {@link CodePointSet.MutableCodePointSet} -- so `append` isn't
 * reachable post-build through ordinary typed use either.
 */
public interface CodePointSetBuilder {
  static CodePointSetBuilder create() {
    return new ArrayCodePointSet.CodePointSetBuilderImpl();
  }

  /** Records that {@code [min, max)} is in the set. Order doesn't matter -- see class doc. */
  void append(int min, int max);

  default void add(int codePoint) {
    append(codePoint, codePoint + 1);
  }

  /** Adds each of {@code source}'s ranges, via {@code forEachRange} (no {@code Range} per entry). */
  void appendAll(CodePointSet source);

  /** Flips whether the built set means "these ranges" or "everything but these ranges". */
  void invert();

  /**
   * Sorts and coalesces every range added so far into one {@link CodePointSet}; never throws (overlapping ranges
   * always merge). Build-once: any further call throws {@link IllegalStateException}, so create a builder per set.
   */
  CodePointSet build();

  /**
   * Combines a run's literal-member builder with its (possibly null) lazily-unioned large sets, optionally
   * negating the result. {@code runUnion}'s laziness (see {@code PatternParser#parseComplexCharacterRanges}) only
   * avoids copying a large set while the run is still being parsed; the result must be a concrete {@link
   * ArrayCodePointSet} before reaching a compiled matcher, because {@link UnionCodePointSet}'s lookups are
   * measurably slower. So this materializes here, where the saved copy would otherwise become a permanent
   * match-time cost.
   *
   * <p>{@code negate} flips a fresh set's {@code invert} bit in place instead of making the caller call {@code
   * complement()} (always a copy). The exception is when {@code runUnion} itself is returned unchanged: it may be
   * a shared {@code NamedCharClass} constant (a plain {@code \d}), so it is copied via {@code complement()}. A
   * caller whose negation can't be pushed down (e.g. it applies after an enclosing {@code &&}) must pass {@code
   * false} and negate the result itself.
   */
  static CodePointSet mergeRun(
      CodePointSetBuilder literals, @Nullable CodePointSet runUnion, boolean negate) {
    if (runUnion == null) {
      if (negate) {
        literals.invert();
      }
      return literals.build();
    }
    CodePointSet literalSet = literals.build();
    // runUnion is a bare concrete set unless the run combined several large sets ("[\d\w]"), in which case it is
    // a UnionCodePointSet that must be materialized too: only a concrete ArrayCodePointSet may leave this method.
    if (literalSet.isEmpty() && !(runUnion instanceof UnionCodePointSet)) {
      return negate ? runUnion.complement() : runUnion;
    }
    // Pre-size when both are ArrayCodePointSets, so the first insertAll's fast-path copy doesn't hand the second a
    // keys array sized for only the first (as in mergeEntryPoints/unionLastCharSet, notes.md 2026-09-25). `size`
    // counts packed ints, the right unit for initialCapacity. A deterministic allocation probe shows ~55-60% fewer
    // bytes/op here (notes.md 2026-09-27), too small a share to show in the JMH ratio.
    int hint = literalSet instanceof ArrayCodePointSet && runUnion instanceof ArrayCodePointSet
        ? ((ArrayCodePointSet) runUnion).size + ((ArrayCodePointSet) literalSet).size
        : 0;
    MutableCodePointSet result = new ArrayCodePointSet(hint);
    result.insertAll(runUnion);
    result.insertAll(literalSet);
    if (negate) {
      result.invert();
    }
    return result;
  }
}

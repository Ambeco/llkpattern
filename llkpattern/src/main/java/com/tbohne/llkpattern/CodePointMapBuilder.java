package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointMap.ConflictingMappingException;
import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import com.tbohne.llkpattern.CodePointMap.Range;
import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Accumulates code point ranges from possibly many sources (e.g. a union's several branches, via
 * {@code PatternConstruct#addCodePointsTo}) without maintaining sort order or coalescing as each
 * one is added -- unlike {@link ArrayCodePointMap#appendSorted}, which requires ascending order
 * and does its coalescing work per call, {@link #add} here is a plain O(1)-amortized append
 * regardless of order, deferring the O(n log n) sort/coalesce/conflict-check to a single {@link
 * #build} call. This is what lets a caller that owns one builder for its whole scan (see
 * design.md's "CodePointMapBuilder / addCodePointsTo" section) avoid allocating an intermediate
 * {@link ArrayCodePointMap} per source.
 *
 * <p>Not itself a {@link CodePointMap} -- it has no query methods, only {@link #add}/{@link
 * #setElseValue}/{@link #build}. Reusable for multiple {@link #build} calls (state isn't
 * consumed), but there's normally no reason to.
 */
final class CodePointMapBuilder<V> {
  private static final int INITIAL_CAPACITY = 4;

  private int[] mins;
  private int[] maxs;
  private Object[] values;
  private int size = 0;
  private @Nullable V elseValue;

  CodePointMapBuilder() {
    this(INITIAL_CAPACITY);
  }

  /**
   * As the no-arg constructor, but starting from {@code initialCapacity} instead of the default --
   * for a caller that can cheaply/accurately estimate how many ranges it's about to {@link #add},
   * to skip {@link #add}'s {@code Arrays.copyOf} growth entirely rather than paying for it on the
   * way to that size. Currently unused (a same-idea two-pass estimate for {@code
   * mergeEntryPoints}'s candidates was tried and reverted -- see notes.md's 2026-09-10 entry: the
   * estimation walk itself cost more than the growth it avoided) -- kept as reusable infrastructure
   * for a future caller with a genuinely cheap size hint in hand, e.g. one that already knows an
   * exact or near-exact count without a dedicated second pass. {@code initialCapacity <= 0} is
   * clamped to 1 (an estimate can legitimately come out 0 for a degenerate/empty input; this class
   * still needs a real backing array either way).
   */
  CodePointMapBuilder(int initialCapacity) {
    int capacity = Math.max(initialCapacity, 1);
    mins = new int[capacity];
    maxs = new int[capacity];
    values = new Object[capacity];
  }

  /** Records that {@code [min, max)} maps to {@code value}. Order doesn't matter -- see class doc. */
  void add(int min, int max, V value) {
    if (value == null) {
      throw new NullPointerException("CodePointMapBuilder does not support null values");
    }
    if (mins.length == size) {
      int newCapacity = mins.length + (mins.length >> 1) + 1;
      mins = Arrays.copyOf(mins, newCapacity);
      maxs = Arrays.copyOf(maxs, newCapacity);
      values = Arrays.copyOf(values, newCapacity);
    }
    mins[size] = min;
    maxs[size] = max;
    values[size] = value;
    size++;
  }

  void add(int codePoint, V value) {
    add(codePoint, codePoint + 1, value);
  }

  /**
   * Adds every one of {@code source}'s entries -- via {@link CodePointMap#forEachRange}, so no
   * {@code Entry}/{@code Range} is allocated per source entry, same reasoning as {@link
   * ArrayCodePointMap#putAll} preferring a raw sweep over {@code entrySet()} where it can.
   */
  void addAll(CodePointMap<V> source) {
    source.forEachRange(this::add);
  }

  /** As {@link CodePointMap.MutableCodePointMap#setElseValue}, applied to the map {@link #build} returns. */
  void setElseValue(@Nullable V value) {
    elseValue = value;
  }

  /**
   * Sorts and coalesces every range added so far into a single {@link CodePointMap}. Two added
   * ranges that overlap and disagree on value are a conflict -- reported via {@code onConflict}
   * (passed the two disagreeing {@code (range, value)} entries, in the order {@link #add} saw
   * them) rather than a hardcoded exception, since callers want different messages (see {@code
   * PatternConstruct.mergeEntryMapRejectingAmbiguity}'s branch-description-aware wording); the
   * default ({@link #build()}) throws a generic {@link ConflictingMappingException}.
   *
   * <p>Two overlapping ranges that agree on value are not a conflict -- they're coalesced (or, if
   * only partially overlapping, the overlap is deduplicated) same as {@link ArrayCodePointMap}'s
   * own {@code put} semantics.
   */
  MutableCodePointMap<V> build(ConflictHandler<V> onConflict) {
    sortInPlaceByMin();

    // Merge/conflict-check pass, compacting forward over the SAME mins/maxs/values arrays --
    // `outSize` is both "how many accepted entries so far" and the index the next accepted entry
    // (if any) gets written to, so this never needs a separate output array. Correct to compare
    // only against the immediately-preceding accepted entry (index outSize - 1), not every prior
    // one: after sorting, mins[i] is non-decreasing, and an accepted entry's own min never
    // decreases past the min of whichever sorted entry first started it -- so entry i can only
    // possibly overlap the LATEST accepted entry, never an earlier one (that one's max is already
    // behind the latest accepted entry's min by construction, since they were disjoint when
    // accepted). This also fixes the old code's O(n^2) worst case, not just its extra allocations.
    int outSize = 0;
    for (int i = 0; i < size; i++) {
      int min = mins[i];
      int max = maxs[i];
      @SuppressWarnings("unchecked")
      V value = (V) values[i];
      if (outSize > 0) {
        int lastIdx = outSize - 1;
        @SuppressWarnings("unchecked")
        V lastValue = (V) values[lastIdx];
        int loMax = Math.min(max, maxs[lastIdx]);
        int hiMin = Math.max(min, mins[lastIdx]);
        if (hiMin < loMax) {
          // Genuine overlap (not just adjacency) -- a real conflict only when the values disagree.
          if (!lastValue.equals(value)) {
            onConflict.onConflict(new Range(hiMin, loMax), lastValue, new Range(min, max), value);
            // onConflict is expected to throw; if a caller's handler doesn't, skip this range
            // rather than silently letting it clobber the earlier one.
            continue;
          }
          maxs[lastIdx] = Math.max(maxs[lastIdx], max); // mins[lastIdx] is already <= min (sorted).
          continue;
        }
        if (hiMin == loMax && lastValue.equals(value)) {
          // Merely adjacent (touching, no overlap) with the same value: also coalesce, same as
          // ArrayCodePointMap.put's own adjacent-equal-value merging.
          maxs[lastIdx] = Math.max(maxs[lastIdx], max);
          continue;
        }
      }
      mins[outSize] = min;
      maxs[outSize] = max;
      values[outSize] = value;
      outSize++;
    }

    // Hand the merged, sorted, disjoint arrays directly to the map -- one allocation (correctly
    // sized up front), not the old code's separate output-array-then-appendSorted-copy.
    MutableCodePointMap<V> result = new ArrayCodePointMap<>(mins, maxs, values, outSize);
    if (elseValue != null) {
      result.setElseValue(elseValue);
    }
    return result;
  }

  /**
   * Insertion sort of {@code mins[0..size)}, {@code maxs}, and {@code values} in lockstep. Plain
   * insertion sort (not {@code Arrays.sort} on boxed indices) because {@code size} is small at
   * every real call site (a bracket expression's members, a union's branches, a loop's
   * candidates) and its inputs tend to already be close to sorted -- and this avoids boxing
   * {@code size} `Integer`s just to sort by a derived key.
   */
  private void sortInPlaceByMin() {
    for (int i = 1; i < size; i++) {
      int min = mins[i];
      int max = maxs[i];
      Object value = values[i];
      int j = i - 1;
      while (j >= 0 && mins[j] > min) {
        mins[j + 1] = mins[j];
        maxs[j + 1] = maxs[j];
        values[j + 1] = values[j];
        j--;
      }
      mins[j + 1] = min;
      maxs[j + 1] = max;
      values[j + 1] = value;
    }
  }

  MutableCodePointMap<V> build() {
    return build(
        (range, value1, otherRange, value2) -> {
          throw new ConflictingMappingException(
              "code points " + range + " map to both " + value1 + " and " + value2);
        });
  }

  @FunctionalInterface
  interface ConflictHandler<V> {
    void onConflict(Range conflictRange, V value1, Range otherRange, V value2);
  }
}

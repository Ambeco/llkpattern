package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointMap.ConflictingMappingException;
import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import com.tbohne.llkpattern.CodePointMap.Range;
import java.util.Arrays;
import java.util.Comparator;
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

  private int[] mins = new int[INITIAL_CAPACITY];
  private int[] maxs = new int[INITIAL_CAPACITY];
  private Object[] values = new Object[INITIAL_CAPACITY];
  private int size = 0;
  private @Nullable V elseValue;

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
  CodePointMap<V> build(ConflictHandler<V> onConflict) {
    // Sort by min; ties broken by original add() order (Integer::compareTo composed with index is
    // overkill for the typically-tiny inputs here, so a plain stable sort on boxed indices is
    // used instead of hand-rolling one).
    Integer[] order = new Integer[size];
    for (int i = 0; i < size; i++) {
      order[i] = i;
    }
    Arrays.sort(order, Comparator.comparingInt(i -> mins[i]));

    // Accepted, already-coalesced-where-possible output ranges, built up in ascending order.
    int[] outMin = new int[size];
    int[] outMax = new int[size];
    @SuppressWarnings("unchecked")
    V[] outValue = (V[]) new Object[size];
    int outSize = 0;

    for (int idx : order) {
      int min = mins[idx];
      int max = maxs[idx];
      @SuppressWarnings("unchecked")
      V value = (V) values[idx];
      // Same O(output size) nested scan PatternConstruct.findFirstOverlap already did per branch
      // -- output is small in practice (a handful of union branches/loop candidates), and this
      // replaces that plus a per-branch ArrayCodePointMap allocation with none.
      boolean absorbed = false;
      for (int i = 0; i < outSize; i++) {
        int loMax = Math.min(max, outMax[i]);
        int hiMin = Math.max(min, outMin[i]);
        if (hiMin < loMax) {
          // Genuine overlap (not just adjacency) -- a real conflict only when the values disagree.
          if (!outValue[i].equals(value)) {
            onConflict.onConflict(new Range(hiMin, loMax), outValue[i], new Range(min, max), value);
            // onConflict is expected to throw; if a caller's handler doesn't, skip this range
            // rather than silently letting it clobber the earlier one.
            absorbed = true;
            break;
          }
          // Same value: merge into the existing accepted entry rather than pushing a second,
          // possibly-overlapping one -- appendSorted below requires disjoint, ascending entries.
          outMin[i] = Math.min(outMin[i], min);
          outMax[i] = Math.max(outMax[i], max);
          absorbed = true;
          break;
        }
        if (hiMin == loMax && outValue[i].equals(value)) {
          // Merely adjacent (touching, no overlap) with the same value: also coalesce, same as
          // ArrayCodePointMap.put's own adjacent-equal-value merging.
          outMin[i] = Math.min(outMin[i], min);
          outMax[i] = Math.max(outMax[i], max);
          absorbed = true;
          break;
        }
      }
      if (!absorbed) {
        outMin[outSize] = min;
        outMax[outSize] = max;
        outValue[outSize] = value;
        outSize++;
      }
    }

    MutableCodePointMap<V> result = new ArrayCodePointMap<>();
    result.ensureCapacity(outSize);
    // outMin/outMax/outValue aren't necessarily disjoint yet where two accepted ranges partially
    // overlap with equal values (allowed above) -- re-sort once more by min so appendSorted's
    // ascending-order contract holds, then let its own merge-adjacent-equal-values logic collapse
    // any resulting overlap or adjacency.
    Integer[] finalOrder = new Integer[outSize];
    for (int i = 0; i < outSize; i++) {
      finalOrder[i] = i;
    }
    Arrays.sort(finalOrder, Comparator.comparingInt(i -> outMin[i]));
    for (int i : finalOrder) {
      int min = outMin[i];
      if (min < 0) {
        continue; // skipped above
      }
      result.appendSorted(min, outMax[i], outValue[i]);
    }
    if (elseValue != null) {
      result.setElseValue(elseValue);
    }
    return result;
  }

  CodePointMap<V> build() {
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

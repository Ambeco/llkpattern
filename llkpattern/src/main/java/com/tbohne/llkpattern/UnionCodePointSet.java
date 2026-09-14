package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointSet.Range;

/**
 * A read-only {@link CodePointSet} that's the union of two delegate sets, computed on the fly --
 * no entries are ever copied out of either delegate into a backing array of its own. Lets a caller
 * combine a small, locally-built set with a large shared one (e.g. a {@code NamedCharClass}
 * constant with hundreds of ranges) by reference, instead of paying to copy the shared set's
 * entries in just to build one combined set -- see {@code ComplexCharacter}'s own doc for the
 * motivating case this was built for.
 *
 * <p>{@link #contains}/{@link #isEmpty} are O(1) delegate calls. {@link #forEachRange} does a real
 * merge of both delegates' sorted range streams (coalescing where they touch or overlap), but only
 * ever holds one pending range from each side at a time -- no intermediate set is built. Every
 * other method ({@link #intersection}, {@link #complement}, {@link #union}, {@link #difference})
 * has no cheap lazy form worth the complexity here, so it just materializes a concrete {@link
 * ArrayCodePointSet} first (via {@link #forEachRange}) and delegates to that -- this class's whole
 * point is avoiding the copy on construction/lookup, not on every possible operation.
 */
final class UnionCodePointSet implements CodePointSet {
  private final CodePointSet a;
  private final CodePointSet b;

  UnionCodePointSet(CodePointSet a, CodePointSet b) {
    this.a = a;
    this.b = b;
  }

  @Override
  public boolean isEmpty() {
    return a.isEmpty() && b.isEmpty();
  }

  @Override
  public boolean contains(int codePoint) {
    return a.contains(codePoint) || b.contains(codePoint);
  }

  @Override
  public boolean containsAll(int min, int max) {
    // Bounding both delegates to [min, max) first keeps this cheap even when one side is a huge
    // (or inverted, effectively-infinite-looking) set -- forEachRange below only ever walks the
    // window, never either delegate's full domain.
    CodePointSet aWindow = a.intersection(min, max);
    CodePointSet bWindow = b.intersection(min, max);
    boolean[] fullyCovered = {true};
    int[] cursor = {min};
    aWindow.forEachRange((rMin, rMax) -> {
      if (!fullyCovered[0]) {
        return;
      }
      if (rMin > cursor[0] && !bWindow.containsAll(cursor[0], rMin)) {
        fullyCovered[0] = false;
      }
      cursor[0] = Math.max(cursor[0], rMax);
    });
    if (fullyCovered[0] && cursor[0] < max) {
      fullyCovered[0] = bWindow.containsAll(cursor[0], max);
    }
    return fullyCovered[0];
  }

  /**
   * A real sorted-merge of {@code a}'s and {@code b}'s own {@link #forEachRange} streams -- both
   * are already ascending and internally disjoint (see {@link CodePointSet#rangeSet}'s own ordering
   * doc). {@link #forEachRange} is push-based on both sides, so
   * there's no way to directly compare "a's next range" against "b's next range" the way a pull-based
   * iterator merge would -- each side is first drained into a small {@code RangeCursor} (two flat
   * {@code int[]} arrays, sized to that delegate's own range count) so the merge below can freely
   * look at, and advance, either side independently. This is still far cheaper than materializing a
   * full {@link ArrayCodePointSet} (no packed-key encoding, capacity growth, or coalescing pass --
   * just two small arrays and a linear scan), and neither delegate's own backing storage is ever
   * touched or copied.
   */
  @Override
  public void forEachRange(RangeConsumer action) {
    RangeCursor aCursor = new RangeCursor(a);
    RangeCursor bCursor = new RangeCursor(b);
    int pendingMin = -1;
    int pendingMax = -1;
    while (aCursor.hasCurrent() || bCursor.hasCurrent()) {
      RangeCursor next;
      if (!bCursor.hasCurrent() || (aCursor.hasCurrent() && aCursor.currentMin() <= bCursor.currentMin())) {
        next = aCursor;
      } else {
        next = bCursor;
      }
      int min = next.currentMin();
      int max = next.currentMax();
      next.advance();
      if (pendingMin == -1) {
        pendingMin = min;
        pendingMax = max;
      } else if (min <= pendingMax) {
        pendingMax = Math.max(pendingMax, max);
      } else {
        action.accept(pendingMin, pendingMax);
        pendingMin = min;
        pendingMax = max;
      }
    }
    if (pendingMin != -1) {
      action.accept(pendingMin, pendingMax);
    }
  }

  /**
   * A delegate's ranges, drained once via {@link #forEachRange} into two flat {@code int[]} arrays
   * (grown by doubling -- the range count isn't known up front, but every real caller here has a
   * small delegate: a {@code NamedCharClass} constant or a locally-built bracket accumulator), then
   * walked with an index -- lets {@link #forEachRange} above compare and advance {@code a}'s and
   * {@code b}'s next candidate range independently, which two push-based callbacks alone can't do.
   */
  private static final class RangeCursor {
    private int[] mins = new int[4];
    private int[] maxs = new int[4];
    private int size;
    private int index;

    RangeCursor(CodePointSet source) {
      source.forEachRange((min, max) -> {
        if (size == mins.length) {
          int newCapacity = mins.length * 2;
          mins = java.util.Arrays.copyOf(mins, newCapacity);
          maxs = java.util.Arrays.copyOf(maxs, newCapacity);
        }
        mins[size] = min;
        maxs[size] = max;
        size++;
      });
    }

    boolean hasCurrent() {
      return index < size;
    }

    int currentMin() {
      return mins[index];
    }

    int currentMax() {
      return maxs[index];
    }

    void advance() {
      index++;
    }
  }

  @Override
  public CodePointSet intersection(int min, int max) {
    return materialize().intersection(min, max);
  }

  @Override
  public CodePointSet complement() {
    return materialize().complement();
  }

  @Override
  public CodePointSet union(CodePointSet other) {
    return new UnionCodePointSet(this, other);
  }

  @Override
  public CodePointSet difference(CodePointSet other) {
    return materialize().difference(other);
  }

  private ArrayCodePointSet materialize() {
    ArrayCodePointSet result = new ArrayCodePointSet();
    forEachRange(result::appendSorted);
    return result;
  }

  @Override
  public String toString() {
    return materialize().toString();
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof CodePointSet)) {
      return false;
    }
    java.util.Set<Range> mine = new java.util.LinkedHashSet<>();
    forEachRange((min, max) -> mine.add(new Range(min, max)));
    java.util.Set<Range> theirs = new java.util.LinkedHashSet<>();
    ((CodePointSet) other).forEachRange((min, max) -> theirs.add(new Range(min, max)));
    return mine.equals(theirs);
  }

  @Override
  public int hashCode() {
    int[] hash = {0};
    forEachRange((min, max) -> hash[0] += new Range(min, max).hashCode());
    return hash[0];
  }
}

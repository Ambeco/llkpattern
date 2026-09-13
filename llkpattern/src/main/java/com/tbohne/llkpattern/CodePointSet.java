package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointMap.Range;
import java.util.LinkedHashSet;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A set of Unicode code points -- the pure-membership counterpart to {@link CodePointMap}, for the
 * (now overwhelming majority) of call sites in this codebase that only ever needed {@code
 * CodePointMap<Boolean>} -- every value was always {@code Boolean.TRUE}, carrying zero information
 * beyond "is this code point in the set" (see {@code PatternConstruct.entryMap}'s own historical
 * doc). Dropping the value entirely -- rather than keeping a generic map and just fixing {@code V}
 * to {@code Boolean} -- removes a whole array ({@link ArrayCodePointMap}'s parallel {@code values}
 * array) and, more importantly, the null-valued "punched hole" trick {@code CodePointMap}'s
 * generic {@link CodePointMap#complement} needs: since a set's only two states at any code point
 * are "in" and "out", a negated set is just this same set with the two states swapped -- see {@link
 * #invert()}. {@link ArrayCodePointMap}/{@link CodePointMap} remain in use wherever a real,
 * multi-valued map is still needed (candidate identities during compile-time ambiguity checking --
 * see {@code PatternConstruct.mergeEntryPointsRaw} -- and the general-purpose differential-test
 * oracle, {@code TreeCodePointMap}).
 *
 * <p>Same {@code [min, max)} range convention as {@link CodePointMap}; see its own doc.
 *
 * <p>{@link ArrayCodePointSet} is the implementation real callers should use.
 */
public interface CodePointSet {
  int MAX_CODE_POINT = CodePointMap.MAX_CODE_POINT;

  boolean isEmpty();

  boolean contains(int codePoint);

  /** Returns true if every code point in {@code [min, max)} is in this set. */
  boolean containsAll(int min, int max);

  /**
   * In ascending order by {@link Range#min} -- see {@link CodePointMap}'s ordering contract, which
   * applies here identically. Default implementation eagerly builds a {@link LinkedHashSet} via
   * {@link #forEachRange}; {@link ArrayCodePointSet} overrides {@link #forEachRange} directly
   * instead of this, the same split {@link CodePointMap} uses.
   */
  default Set<Range> rangeSet() {
    Set<Range> result = new LinkedHashSet<>();
    forEachRange((min, max) -> result.add(new Range(min, max)));
    return result;
  }

  /** A callback for {@link #forEachRange}: one {@code [min, max)} range. */
  @FunctionalInterface
  interface RangeConsumer {
    void accept(int min, int max);
  }

  /**
   * Visits every range this set contains as a plain {@code (int min, int max)} callback -- no
   * boxed {@link Range} allocated per range, and (for {@link ArrayCodePointSet}) reads the raw
   * backing array directly. See {@link CodePointMap#forEachRange}'s own doc for why this matters on
   * a hot path. Default falls back to {@link #rangeSet()}.
   */
  default void forEachRange(RangeConsumer action) {
    for (Range r : rangeSet()) {
      action.accept(r.min, r.max);
    }
  }

  /** A callback for {@link #first}: one {@code [min, max)} range. */
  @FunctionalInterface
  interface RangePredicate {
    boolean test(int min, int max);
  }

  /**
   * Like {@link #forEachRange}, but a short-circuiting search: stops at (and returns {@code true}
   * from) the first range {@code predicate} accepts. See {@link CodePointMap#first}'s own doc.
   */
  default boolean first(RangePredicate predicate) {
    for (Range r : rangeSet()) {
      if (predicate.test(r.min, r.max)) {
        return true;
      }
    }
    return false;
  }

  /** Returns the portion of this set restricted to {@code [min, max)}. */
  CodePointSet intersection(int min, int max);

  /** Returns the complement of this set: every code point NOT in this set, and vice versa. */
  CodePointSet complement();

  /** Returns a new set holding this set's members plus {@code other}'s. */
  CodePointSet union(CodePointSet other);

  /** Returns a new set holding this set's members, minus any also in {@code other}. */
  CodePointSet difference(CodePointSet other);

  @Override
  boolean equals(@Nullable Object other);

  @Override
  int hashCode();

  interface MutableCodePointSet extends CodePointSet {
    default void add(int codePoint) {
      add(codePoint, codePoint + 1);
    }

    /** Adds every code point in {@code [min, max)} to this set. */
    void add(int min, int max);

    /**
     * Bulk-adds a single range, skipping whatever overlap-checking/coalescing work {@link #add}
     * normally does -- see {@link CodePointMap.MutableCodePointMap#appendSorted}'s identical
     * contract (ascending {@code min} order, building up from empty). Default just forwards to
     * {@link #add}; {@link ArrayCodePointSet} provides the real O(1)-amortized override.
     */
    default void appendSorted(int min, int max) {
      add(min, max);
    }

    /** As {@link CodePointMap.MutableCodePointMap#ensureCapacity}. No-op by default. */
    default void ensureCapacity(int minEntries) {}

    /**
     * Flips which side of this set's own entries is "in": every currently-included code point
     * becomes excluded and vice versa. Unlike {@link #complement()}, mutates in place with no copy
     * -- see {@link ArrayCodePointSet}'s own doc for why this is safe (there's no third state, so
     * nothing needs to be materialized to flip).
     */
    void invert();

    /** Adds every one of {@code other}'s members to this set. */
    void addAll(CodePointSet other);

    default void remove(int codePoint) {
      remove(codePoint, codePoint + 1);
    }

    /** Removes every code point in {@code [min, max)} from this set. */
    void remove(int min, int max);

    default void removeAll(CodePointSet other) {
      other.forEachRange(this::remove);
    }

    default void clear() {
      remove(0, MAX_CODE_POINT + 1);
    }
  }
}

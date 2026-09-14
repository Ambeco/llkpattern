package com.tbohne.llkpattern;

import java.util.LinkedHashSet;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A set of Unicode code points. This engine's entry-point/dispatch machinery
 * ({@code PatternConstruct}/{@code MatcherConstruct}) and every {@code NamedCharClass}/{@code
 * UnicodePredicates} constant only ever need pure membership -- "is this code point in the set",
 * nothing more -- so there's no generic value-carrying map underneath this any more (an earlier
 * design had one, {@code CodePointMap<V>}, generic over an arbitrary value type fixed to {@code
 * Boolean} almost everywhere it was used; see notes.md's 2026-09-12/2026-09-14 entries for that
 * migration and its later cleanup). Dropping the value entirely -- rather than keeping a generic
 * map and just fixing {@code V} to {@code Boolean} -- removes a whole parallel values array and,
 * more importantly, the null-valued "punched hole" trick a generic map's {@code complement} would
 * need: since a set's only two states at any code point are "in" and "out", a negated set is just
 * this same set with the two states swapped -- see {@link #invert()}.
 *
 * <p>{@link ArrayCodePointSet} is the implementation real callers should use.
 */
public interface CodePointSet {
  int MAX_CODE_POINT = 0x10FFFF;

  boolean isEmpty();

  boolean contains(int codePoint);

  /** Returns true if every code point in {@code [min, max)} is in this set. */
  boolean containsAll(int min, int max);

  /**
   * In ascending order by {@link Range#min} -- every implementation maintains this already (it
   * falls straight out of being a set of disjoint ranges over an ordered domain), so this is a
   * formal guarantee, not an incidental detail. Default implementation eagerly builds a {@link
   * LinkedHashSet} via {@link #forEachRange}; {@link ArrayCodePointSet} overrides {@link
   * #forEachRange} directly instead of this.
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
   * backing array directly. Worth using over {@link #rangeSet()} on any hot path that just wants to
   * visit ranges and has no actual use for a {@code Range} object. Default falls back to {@link
   * #rangeSet()}.
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
   * from) the first range {@code predicate} accepts, instead of visiting every remaining range
   * after the answer is already known.
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
     * normally does. Callers must supply ranges in ascending {@code min} order, building this set
     * up from empty. Default just forwards to {@link #add}; {@link ArrayCodePointSet} provides the
     * real O(1)-amortized override.
     */
    default void appendSorted(int min, int max) {
      add(min, max);
    }

    /**
     * Optional capacity hint for implementations backed by a resizable array (see {@link
     * ArrayCodePointSet}): preallocate room for {@code minEntries} upcoming entries, to avoid
     * incremental array growth when the eventual size is known ahead of time. No-op by default.
     */
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

  /**
   * An inclusive-min, exclusive-max range of code points: {@code [min, max)}. Immutable. Formerly
   * nested under the now-removed generic {@code CodePointMap<V>} (see this file's own class doc);
   * lives here now since {@link CodePointSet} is this range convention's only remaining owner.
   */
  final class Range {
    public final int min; // inclusive
    public final int max; // exclusive

    public Range(int codePoint) {
      this(codePoint, codePoint + 1);
    }

    public Range(int min, int max) {
      if (max <= min) {
        throw new IllegalArgumentException("max (" + max + ") must be greater than min (" + min + ")");
      }
      this.min = min;
      this.max = max;
    }

    @Override
    public boolean equals(@Nullable Object other) {
      if (!(other instanceof Range)) {
        return false;
      }
      Range rhs = (Range) other;
      return min == rhs.min && max == rhs.max;
    }

    @Override
    public int hashCode() {
      return 31 * min + max;
    }

    @Override
    public String toString() {
      return "[" + min + "," + max + ")";
    }
  }
}

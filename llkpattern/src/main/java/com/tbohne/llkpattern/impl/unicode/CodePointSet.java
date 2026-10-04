package com.tbohne.llkpattern.impl.unicode;

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

  // Ascending by Range#min (a formal guarantee). The default eagerly builds a LinkedHashSet; ArrayCodePointSet
  // overrides forEachRange instead.
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

  // Visits every range without boxing a Range (ArrayCodePointSet reads its backing array directly); prefer over
  // rangeSet() on hot paths.
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

  /** Like {@link #forEachRange}, but stops at, and returns true from, the first range {@code predicate} accepts. */
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

  // The default is a nested range scan (one temporary set per range of this one); ArrayCodePointSet overrides it
  // with the allocation-light sweep that parse-time-hot callers should get.
  default CodePointSet intersection(CodePointSet other) {
    CodePointSetBuilder result = CodePointSetBuilder.create();
    forEachRange((min, max) -> other.intersection(min, max).forEachRange(result::append));
    return result.build();
  }

  // Boolean-only, short-circuiting counterpart to intersection(), for callers (PatternConstruct#checkDisjoint) that
  // only need "do these overlap". The default is a quadratic nested first() scan, correct for any implementation;
  // ArrayCodePointSet overrides it allocation-free.
  default boolean intersects(CodePointSet other) {
    return other.first((oMin, oMax) -> first((min, max) -> min < oMax && oMin < max));
  }

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
    default void set(int codePoint) {
      insert(codePoint, codePoint + 1);
    }

    /** Adds every code point in {@code [min, max)} to this set. */
    void insert(int min, int max);

    // Capacity hint for array-backed implementations: preallocate for minEntries upcoming entries. No-op by default.
    default void ensureCapacity(int minEntries) {}

    // Flips which side of this set's entries is "in", in place with no copy (unlike complement()); safe because
    // there is no third state (see ArrayCodePointSet).
    void invert();

    /** Adds every one of {@code other}'s members to this set. */
    void insertAll(CodePointSet other);

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

  /** An inclusive-min, exclusive-max range of code points: {@code [min, max)}. Immutable. */
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

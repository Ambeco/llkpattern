package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;

/**
 * The (currently only) {@link CodePointMap} implementation, delegating to Guava's {@code
 * TreeRangeMap}. See {@link CodePointMap} for the {@code [min, max)} range convention this class
 * follows.
 *
 * <p>This is intentionally not optimized for the Unicode code point domain specifically — it's a
 * straightforward adapter over a general-purpose range map, meant to get the rest of the compiler
 * working against a stable {@link CodePointMap} interface. A more specialized implementation may
 * replace this later without needing to change any caller.
 *
 * <p>Note: {@code CodePointMap.Range} is inherited unqualified from the interface, so this class
 * refers to Guava's range type by its fully-qualified name throughout to avoid shadowing it.
 */
public final class TreeCodePointMap<V> implements MutableCodePointMap<V> {
  private final com.google.common.collect.RangeMap<Integer, V> rangeMap;

  // Punched holes for the complement constructor below -- see CodePointMap#getElseValue and
  // ArrayCodePointMap's analogous (array-backed) elseValue field doc for the full rationale. Kept
  // as a separate RangeSet rather than reusing rangeMap with a null-sentinel value, since Guava's
  // RangeMap rejects null values outright and this class doesn't need the packed-array tricks
  // that made a sentinel-in-values worthwhile for ArrayCodePointMap.
  private final com.google.common.collect.RangeSet<Integer> excludedRanges =
      com.google.common.collect.TreeRangeSet.create();
  private @Nullable V elseValue;

  public TreeCodePointMap() {
    rangeMap = com.google.common.collect.TreeRangeMap.create();
  }

  public TreeCodePointMap(CodePointMap<V> other) {
    this();
    putAll(other);
  }

  /** Builds the complement of {@code source} -- see {@link ArrayCodePointMap}'s analogous constructor. */
  TreeCodePointMap(CodePointMap<V> source, V value) {
    this();
    elseValue = value;
    for (Entry<Range, V> e : source.entrySet()) {
      excludedRanges.add(toGuavaRange(e.getKey().min, e.getKey().max));
    }
  }

  @Override
  public @Nullable V getElseValue() {
    return elseValue;
  }

  @Override
  public boolean isEmpty() {
    return elseValue == null && rangeMap.asMapOfRanges().isEmpty();
  }

  @Override
  public boolean containsKeys(int min, int max) {
    com.google.common.collect.RangeSet<Integer> uncoveredByRangeMap =
        com.google.common.collect.TreeRangeSet.create();
    uncoveredByRangeMap.add(toGuavaRange(min, max));
    for (com.google.common.collect.Range<Integer> covered :
        rangeMap.subRangeMap(toGuavaRange(min, max)).asMapOfRanges().keySet()) {
      uncoveredByRangeMap.remove(covered);
    }
    if (uncoveredByRangeMap.isEmpty()) {
      return true; // rangeMap alone already covers [min, max)
    }
    if (elseValue == null) {
      return false; // some code points are uncovered and there's no elseValue to fill them
    }
    uncoveredByRangeMap.removeAll(excludedRanges);
    return uncoveredByRangeMap.isEmpty(); // elseValue fills every remaining gap iff none are excluded
  }

  @Override
  public Set<Entry<Range, V>> entrySet() {
    Set<Entry<Range, V>> result = new java.util.LinkedHashSet<>();
    for (Entry<com.google.common.collect.Range<Integer>, V> e : rangeMap.asMapOfRanges().entrySet()) {
      result.add(new ImmutableEntry<>(fromGuavaRange(e.getKey()), e.getValue()));
    }
    if (elseValue != null) {
      // The gaps not covered by rangeMap's real entries and not explicitly excluded are elseValue
      // too -- always finite since the code point domain itself is bounded (see
      // CodePointMap#getElseValue).
      com.google.common.collect.RangeSet<Integer> covered = com.google.common.collect.TreeRangeSet.create();
      covered.addAll(rangeMap.asMapOfRanges().keySet());
      covered.addAll(excludedRanges);
      com.google.common.collect.RangeSet<Integer> gaps =
          covered.complement().subRangeSet(toGuavaRange(0, MAX_CODE_POINT + 1));
      for (com.google.common.collect.Range<Integer> gap : gaps.asRanges()) {
        result.add(new ImmutableEntry<>(fromGuavaRange(gap), elseValue));
      }
    }
    return result;
  }

  @Override
  public @PolyNull V getOrDefault(int codePoint, @Nullable V defaultValue) {
    V found = rangeMap.get(codePoint);
    if (found != null) {
      return found;
    }
    if (elseValue != null && !excludedRanges.contains(codePoint)) {
      return elseValue;
    }
    return defaultValue;
  }

  /** This map's entries overlapping {@code [min, max)} -- see {@link ArrayCodePointMap#entriesOverlapping}. */
  private java.util.List<Entry<Range, V>> entriesOverlapping(int min, int max) {
    java.util.List<Entry<Range, V>> result = new java.util.ArrayList<>();
    for (Entry<Range, V> e : entrySet()) {
      int lo = Math.max(min, e.getKey().min);
      int hi = Math.min(max, e.getKey().max);
      if (lo < hi) {
        result.add(new ImmutableEntry<>(new Range(lo, hi), e.getValue()));
      }
    }
    return result;
  }

  @Override
  public CodePointMap<V> intersection(int min, int max) {
    TreeCodePointMap<V> result = new TreeCodePointMap<>();
    for (Entry<Range, V> e : entriesOverlapping(min, max)) {
      result.put(e.getKey().min, e.getKey().max, e.getValue());
    }
    return result;
  }

  @Override
  public CodePointMap<V> intersectionRejectingConflicts(CodePointMap<V> other) {
    TreeCodePointMap<V> result = new TreeCodePointMap<>();
    for (Entry<Range, V> otherEntry : other.entrySet()) {
      for (Entry<Range, V> mineEntry :
          entriesOverlapping(otherEntry.getKey().min, otherEntry.getKey().max)) {
        V mine = mineEntry.getValue();
        if (!mine.equals(otherEntry.getValue())) {
          throw new CodePointMap.ConflictingMappingException(
              "this map has value "
                  + mine
                  + " for code points "
                  + mineEntry.getKey()
                  + ", but other map has value "
                  + otherEntry.getValue()
                  + " for code points "
                  + otherEntry.getKey());
        }
        result.put(mineEntry.getKey().min, mineEntry.getKey().max, mine);
      }
    }
    return result;
  }

  @Override
  public CodePointMap<V> complement(V value) {
    return new TreeCodePointMap<>(this, value);
  }

  @Override
  public void put(int min, int max, V value) {
    if (value == null) {
      // See ArrayCodePointMap.put's identical guard -- null is reserved for complement()'s
      // internal punched-hole bookkeeping, not a value ordinary callers should ever pass.
      throw new NullPointerException(
          "TreeCodePointMap does not support null values; did you mean complement(value) to "
              + "build the set of code points this map doesn't contain?");
    }
    rangeMap.put(toGuavaRange(min, max), value);
    excludedRanges.remove(toGuavaRange(min, max)); // a real value here overrides any prior exclusion
  }

  @Override
  public @Nullable V compute(int codePoint, CodePointRemapFunction<V> remappingFunction) {
    V before = rangeMap.get(codePoint);
    V after = remappingFunction.apply(codePoint, before);
    if (after == null) {
      rangeMap.remove(toGuavaRange(codePoint, codePoint + 1));
    } else {
      rangeMap.put(toGuavaRange(codePoint, codePoint + 1), after);
    }
    return before;
  }

  @Override
  public void putAll(CodePointMap<V> other) {
    other.forEach((range, value) -> put(range.min, range.max, value));
  }

  @Override
  public void remove(int min, int max) {
    rangeMap.remove(toGuavaRange(min, max));
  }

  private static com.google.common.collect.Range<Integer> toGuavaRange(int min, int max) {
    return com.google.common.collect.Range.closedOpen(min, max);
  }

  private static Range fromGuavaRange(com.google.common.collect.Range<Integer> range) {
    return new Range(range.lowerEndpoint(), range.upperEndpoint());
  }

  @Override
  public String toString() {
    return rangeMap.toString();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    // Compares logical entrySet() content, not raw rangeMap shape, so this agrees with
    // ArrayCodePointMap's coalesced notion of equality (see its class doc) rather than being
    // sensitive to how many separate put() calls built up an equivalent set of mappings.
    return (other instanceof CodePointMap) && entrySet().equals(((CodePointMap<?>) other).entrySet());
  }

  @Override
  public int hashCode() {
    return entrySet().hashCode();
  }
}

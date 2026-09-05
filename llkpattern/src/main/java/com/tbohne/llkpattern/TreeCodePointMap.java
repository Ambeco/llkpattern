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

  public TreeCodePointMap() {
    rangeMap = com.google.common.collect.TreeRangeMap.create();
  }

  public TreeCodePointMap(CodePointMap<V> other) {
    this();
    putAll(other);
  }

  @Override
  public boolean isEmpty() {
    return rangeMap.asMapOfRanges().isEmpty();
  }

  @Override
  public boolean containsKeys(int min, int max) {
    int covered =
        rangeMap.subRangeMap(toGuavaRange(min, max)).asMapOfRanges().keySet().stream()
            .mapToInt(r -> r.upperEndpoint() - r.lowerEndpoint())
            .sum();
    return covered == max - min;
  }

  @Override
  public Set<Entry<Range, V>> entrySet() {
    Set<Entry<Range, V>> result = new java.util.LinkedHashSet<>();
    for (Entry<com.google.common.collect.Range<Integer>, V> e : rangeMap.asMapOfRanges().entrySet()) {
      result.add(new ImmutableEntry<>(fromGuavaRange(e.getKey()), e.getValue()));
    }
    return result;
  }

  @Override
  public @PolyNull V getOrDefault(int codePoint, @Nullable V defaultValue) {
    V found = rangeMap.get(codePoint);
    return found != null ? found : defaultValue;
  }

  @Override
  public CodePointMap<V> intersection(int min, int max) {
    TreeCodePointMap<V> result = new TreeCodePointMap<>();
    result.rangeMap.putAll(rangeMap.subRangeMap(toGuavaRange(min, max)));
    return result;
  }

  @Override
  public CodePointMap<V> intersectionRejectingConflicts(CodePointMap<V> other) {
    TreeCodePointMap<V> result = new TreeCodePointMap<>();
    for (Entry<Range, V> otherEntry : other.entrySet()) {
      com.google.common.collect.RangeMap<Integer, V> overlap =
          rangeMap.subRangeMap(toGuavaRange(otherEntry.getKey().min, otherEntry.getKey().max));
      for (Map.Entry<com.google.common.collect.Range<Integer>, V> overlapEntry :
          overlap.asMapOfRanges().entrySet()) {
        if (!overlapEntry.getValue().equals(otherEntry.getValue())) {
          throw new CodePointMap.ConflictingMappingException(
              "this map has value "
                  + overlapEntry.getValue()
                  + " for code points "
                  + fromGuavaRange(overlapEntry.getKey())
                  + ", but other map has value "
                  + otherEntry.getValue()
                  + " for code points "
                  + otherEntry.getKey());
        }
        result.rangeMap.put(overlapEntry.getKey(), overlapEntry.getValue());
      }
    }
    return result;
  }

  @Override
  public void put(int min, int max, V value) {
    rangeMap.put(toGuavaRange(min, max), value);
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
    return (other instanceof TreeCodePointMap) && rangeMap.equals(((TreeCodePointMap<?>) other).rangeMap);
  }

  @Override
  public int hashCode() {
    return rangeMap.hashCode();
  }
}

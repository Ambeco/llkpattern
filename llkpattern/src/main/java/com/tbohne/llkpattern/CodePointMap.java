package com.tbohne.llkpattern;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;

/**
 * A map of Unicode code points to other things.
 *
 * <p>Conceptually the union of {@link com.google.common.collect.RangeMap} and {@link
 * java.util.concurrent.ConcurrentMap}, specialized to {@code int} keys in the Unicode code point
 * space ({@code [0, MAX_CODE_POINT]}). No implementation supports concurrent access.
 *
 * <p><b>Range convention:</b> every {@link Range} (and every {@code (min, max)} parameter pair)
 * is <i>min-inclusive, max-exclusive</i> — i.e. {@code [min, max)} — matching {@link
 * com.google.common.collect.Range#closedOpen}. A single code point {@code cp} is represented as
 * {@code [cp, cp+1)}.
 *
 * <p>{@link TreeCodePointMap} is the (currently only) implementation, delegating to Guava's
 * {@code TreeRangeMap}. The interface exists so that implementation can later be swapped for
 * something more specialized to the Unicode code point space, without disturbing callers.
 *
 * @param <V> the value type. This interface does not support {@code null} values: a {@code null}
 *     result from a query method means "no mapping", matching {@link Map#get}.
 */
public interface CodePointMap<V> {
  int MAX_CODE_POINT = 0x10FFFF;

  boolean isEmpty();

  default boolean containsKey(int codePoint) {
    return get(codePoint) != null;
  }

  /** Returns true if every code point in {@code [min, max)} has a mapping. */
  boolean containsKeys(int min, int max);

  Set<Entry<Range, V>> entrySet();

  default @Nullable V get(int codePoint) {
    return getOrDefault(codePoint, null);
  }

  @PolyNull
  V getOrDefault(int codePoint, @Nullable V defaultValue);

  default void forEach(BiConsumer<Range, ? super V> action) {
    entrySet().forEach(entry -> action.accept(entry.getKey(), entry.getValue()));
  }

  default Map<Range, V> asMapOfRanges() {
    return entrySet().stream().collect(Collectors.toMap(Entry::getKey, Entry::getValue));
  }

  default Iterator<Entry<Range, V>> iterator() {
    return entrySet().iterator();
  }

  default Stream<Entry<Range, V>> stream() {
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false);
  }

  /** Returns the portion of this map restricted to {@code [min, max)}. */
  CodePointMap<V> intersection(int min, int max);

  /**
   * Returns a map holding only the entries whose ranges overlap between this map and {@code
   * other}, keeping this map's values. Where the two maps overlap but disagree on the value,
   * throws {@link ConflictingMappingException} — this is intended for the LL(1) "these two
   * branches must not both claim the same code point" check, not general-purpose intersection.
   */
  CodePointMap<V> intersectionRejectingConflicts(CodePointMap<V> other);

  /** Returns a view of the code points *not* present as keys in this map, mapped to {@code value}. */
  default CodePointMap<V> complement(V value) {
    return new ComplementCodePointMap<>(this, value);
  }

  /** Returns a new map holding this map's entries overlaid with {@code other}'s (other wins on overlap). */
  default CodePointMap<V> union(CodePointMap<V> other) {
    TreeCodePointMap<V> result = new TreeCodePointMap<>(this);
    result.putAll(other);
    return result;
  }

  /** Returns a new map holding this map's entries, minus any code point also present in {@code other}. */
  default CodePointMap<V> difference(CodePointMap<V> other) {
    TreeCodePointMap<V> result = new TreeCodePointMap<>(this);
    result.removeAll(other);
    return result;
  }

  @Override
  boolean equals(@Nullable Object other);

  @Override
  int hashCode();

  interface MutableCodePointMap<V> extends CodePointMap<V> {

    /** Computes a replacement value for the single code point {@code codePoint}. */
    @FunctionalInterface
    interface CodePointRemapFunction<V> {
      @Nullable
      V apply(int codePoint, @Nullable V oldValue);
    }

    default void put(int codePoint, V value) {
      put(codePoint, codePoint + 1, value);
    }

    /** Maps every code point in {@code [min, max)} to {@code value}, replacing any prior mapping. */
    void put(int min, int max, V value);

    @Nullable
    V compute(int codePoint, CodePointRemapFunction<V> remappingFunction);

    default @Nullable V computeIfAbsent(int codePoint, Function<Integer, ? extends @Nullable V> mappingFunction) {
      return compute(codePoint, (cp, oldValue) -> oldValue == null ? mappingFunction.apply(cp) : oldValue);
    }

    default @Nullable V computeIfPresent(int codePoint, CodePointRemapFunction<V> remappingFunction) {
      return compute(codePoint, (cp, oldValue) -> oldValue == null ? null : remappingFunction.apply(cp, oldValue));
    }

    void putAll(CodePointMap<V> other);

    default void putIfAbsent(int codePoint, V value) {
      compute(codePoint, (cp, old) -> old == null ? value : old);
    }

    default void remove(int codePoint) {
      remove(codePoint, codePoint + 1);
    }

    /** Removes any mapping for every code point in {@code [min, max)}. */
    void remove(int min, int max);

    default void removeAll(CodePointMap<V> other) {
      other.forEach((range, value) -> remove(range.min, range.max));
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

  final class ImmutableEntry<V> implements Map.Entry<Range, V> {
    private final Range key;
    private final V value;

    ImmutableEntry(Range key, V value) {
      this.key = key;
      this.value = value;
    }

    @Override
    public Range getKey() {
      return key;
    }

    @Override
    public V getValue() {
      return value;
    }

    @Override
    public V setValue(V v) {
      throw new UnsupportedOperationException("ImmutableEntry doesn't support setValue");
    }

    // Map.Entry's contract defines equals/hashCode in terms of getKey()/getValue(), which the
    // default Object identity behavior doesn't satisfy -- needed so that entrySet().equals()
    // (used by CodePointMap implementations' own equals()) does real content comparison.
    @Override
    public boolean equals(@Nullable Object other) {
      if (!(other instanceof Map.Entry)) {
        return false;
      }
      Map.Entry<?, ?> rhs = (Map.Entry<?, ?>) other;
      return key.equals(rhs.getKey()) && value.equals(rhs.getValue());
    }

    @Override
    public int hashCode() {
      return key.hashCode() ^ value.hashCode();
    }
  }

  final class ComplementCodePointMap<V> implements CodePointMap<V> {
    private final CodePointMap<?> other;
    private final V value;

    ComplementCodePointMap(CodePointMap<?> other, V value) {
      this.other = other;
      this.value = value;
    }

    @Override
    public boolean isEmpty() {
      return !other.isEmpty() && other.containsKeys(0, MAX_CODE_POINT + 1);
    }

    @Override
    public boolean containsKeys(int min, int max) {
      for (int cp = min; cp < max; cp++) {
        if (!other.containsKey(cp)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public Set<Entry<Range, V>> entrySet() {
      throw new UnsupportedOperationException("TODO: complement enumeration not yet implemented");
    }

    @Override
    public @PolyNull V getOrDefault(int codePoint, @Nullable V defaultValue) {
      return other.get(codePoint) == null ? value : defaultValue;
    }

    @Override
    public CodePointMap<V> intersection(int min, int max) {
      throw new UnsupportedOperationException("TODO: complement enumeration not yet implemented");
    }

    @Override
    public CodePointMap<V> intersectionRejectingConflicts(CodePointMap<V> otherMap) {
      throw new UnsupportedOperationException("TODO: complement enumeration not yet implemented");
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (!(o instanceof ComplementCodePointMap)) {
        return false;
      }
      ComplementCodePointMap<?> rhs = (ComplementCodePointMap<?>) o;
      return other.equals(rhs.other) && value.equals(rhs.value);
    }

    @Override
    public int hashCode() {
      return 31 * other.hashCode() + value.hashCode();
    }
  }

  class ConflictingMappingException extends IllegalArgumentException {
    ConflictingMappingException(String message) {
      super(message);
    }
  }
}

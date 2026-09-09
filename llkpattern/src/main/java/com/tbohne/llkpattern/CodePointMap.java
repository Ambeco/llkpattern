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
 * <p>{@link ArrayCodePointMap} is the implementation real callers should use; {@link
 * TreeCodePointMap} (a Guava {@code TreeRangeMap} adapter) exists only as its differential-test
 * oracle -- see {@code CodePointMapDifferentialTest}.
 *
 * <p><b>Ordering contract:</b> {@link #entrySet()} (and everything built on it -- {@link
 * #forEach}, {@link #iterator()}, {@link #stream()}) always yields entries in ascending order by
 * {@link Range#min}. Every implementation maintains this already (it falls straight out of being
 * a map of disjoint ranges over an ordered domain), so this is a formal guarantee, not an
 * incidental detail: it's what lets {@link MutableCodePointMap#putAll} do a linear sorted-merge
 * instead of one insertion per source entry, and any future implementation must preserve it.
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

  /** In ascending order by {@link Range#min} -- see the class doc's ordering contract. */
  Set<Entry<Range, V>> entrySet();

  default @Nullable V get(int codePoint) {
    return getOrDefault(codePoint, null);
  }

  @PolyNull
  V getOrDefault(int codePoint, @Nullable V defaultValue);

  /**
   * The value implicitly mapped to every code point this map has no explicit entry (or explicit
   * exclusion -- see {@link #complement}) for, or {@code null} for an ordinary map (the common
   * case) where an absent code point simply has no mapping.
   *
   * <p>This is what lets {@link #complement} be a genuinely finite, O(entry count) map rather
   * than needing to enumerate an unbounded range: since the code point domain is itself bounded
   * ({@code [0, MAX_CODE_POINT]}), "every code point not in this set" is always representable as
   * a normal map with an else-value, never an actually-infinite structure the way Guava's {@code
   * RangeSet#complement()} is over all of {@code Integer}.
   */
  default @Nullable V getElseValue() {
    return null;
  }

  /**
   * Like {@link #get}, but ignores {@link #getElseValue}: returns non-null only for a code point
   * with a real, explicit entry, {@code null} for both "no mapping" and "covered only by the
   * else-value fill." Useful for callers doing their own layered fallback lookup (see {@link
   * MatcherConstruct.MultiDispatchingMatcherConstruct#getNext}'s case-folding) that needs to tell
   * those two apart before falling back to {@link #getElseValue} itself.
   */
  @Nullable
  V getExplicit(int codePoint);

  default void forEach(BiConsumer<Range, ? super V> action) {
    entrySet().forEach(entry -> action.accept(entry.getKey(), entry.getValue()));
  }

  /** A callback for {@link #forEachRange}: {@code [min, max)} plus the value mapped there. */
  @FunctionalInterface
  interface RangeConsumer<V> {
    void accept(int min, int max, V value);
  }

  /**
   * Like {@link #forEach}, but takes {@code min}/{@code max} as primitive {@code int}s instead of
   * a boxed {@link Range}, and (for {@link ArrayCodePointMap}, the implementation real callers use)
   * visits this map's raw backing arrays directly rather than going through {@link #entrySet()} --
   * no {@code Range}, {@code Entry}, or {@code Iterator} allocated per entry. Worth using over
   * {@code entrySet()}/{@code forEach} on any hot path that just wants to visit ranges (a merge
   * into another map, a bulk copy) and has no actual use for a {@code Range}/{@code Entry} object.
   * Default implementation just falls back to {@link #entrySet()}, for implementations (like {@link
   * TreeCodePointMap}) that don't have a cheaper representation to expose.
   */
  default void forEachRange(RangeConsumer<? super V> action) {
    for (Entry<Range, V> e : entrySet()) {
      action.accept(e.getKey().min, e.getKey().max, e.getValue());
    }
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
   * Returns a map holding {@code value} for every code point this map has no entry for, and no
   * mapping for every code point this map does -- the elsewhere-value trick described on {@link
   * #getElseValue}. Default falls back to {@link TreeCodePointMap} the same way {@link #union}
   * and {@link #difference} do; {@link ArrayCodePointMap} overrides this to stay in its own
   * concrete type.
   */
  default CodePointMap<V> complement(V value) {
    return new TreeCodePointMap<>(this, value);
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

    /**
     * Bulk-loads a single entry, skipping whatever overlap-checking/coalescing work {@link #put}
     * normally does. Callers must supply entries for a given map in ascending {@code min} order
     * (per the class doc's ordering contract), building that map up from empty. The default here
     * just forwards to {@link #put}, for implementations (like {@link TreeCodePointMap}, which
     * exists only as a differential-test oracle) that don't need the optimization; {@link
     * ArrayCodePointMap} provides the real O(1)-amortized override.
     */
    default void appendSorted(int min, int max, V value) {
      put(min, max, value);
    }

    /**
     * Optional capacity hint for implementations backed by a resizable array (see {@link
     * ArrayCodePointMap}): preallocate room for {@code minEntries} upcoming entries, to avoid
     * incremental array growth when the eventual size is known ahead of time. No-op by default.
     */
    default void ensureCapacity(int minEntries) {}

    /**
     * Sets the value {@link #getElseValue} returns -- the fill for any code point without an
     * explicit entry. Unlike {@link #complement}, this doesn't touch this map's existing entries
     * at all (no punched holes are added or removed); it just changes what "otherwise unmapped"
     * means going forward. Pass {@code null} to go back to an ordinary (no else-value) map.
     */
    void setElseValue(@Nullable V value);

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

  class ConflictingMappingException extends IllegalArgumentException {
    ConflictingMappingException(String message) {
      super(message);
    }
  }
}

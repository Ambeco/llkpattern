package com.tbohne.llkpattern;

import static com.google.common.collect.Range.closedOpen;
import static com.google.common.collect.Range.range;

import androidx.annotation.NonNull;
import androidx.collection.ArraySet;
import com.google.common.collect.ImmutableRangeMap;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import com.sun.org.apache.bcel.internal.classfile.Code;
import com.tbohne.llkpattern.CodePointMap.Range;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import jdk.internal.org.objectweb.asm.commons.Remapper;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;

/**
 * A map of Unicode Code Points to other things.
 *
 * The interface(s) are effectively union of com.google.common.collect.RangeMap, and java.util.concurrent.ConcurrentMap.
 * However, no implementation supports concurrent access.
 *
 *
 * CodePointMap has the "query" methods. Most code will probably use ImmutableCodePointMap, or MutableCodePointMap,
 * instead of the base interface.
 * @param <V>
 */
@SuppressWarnings({"UnusedReturnValue", "BooleanMethodIsAlwaysInverted"})
public interface CodePointMap<V> {
	int MAX_CODE_POINT = 0x10FFFF;

	boolean isEmpty();

	default boolean containsKey(int codePoint) { return getOrDefault(codePoint, null) != null; }

	boolean containsKeys(int min, int max);

	Set<Entry<Range, V>> entrySet();

	default Set<Range> keySet() { return stream().map(Entry::getKey).collect(Collectors.toSet()); }

	default @Nullable V get(int codePoint) { return getOrDefault(codePoint, null); }

	@PolyNull V getOrDefault(int codePoint, @Nullable V defaultValue);

	default void forEach(BiConsumer<Range, ? super V> action) {
		stream().forEach(entry -> action.accept(entry.getKey(), entry.getValue()));
	}

	CodePointMap<V> intersection(int min, int max);

	default CodePointMap<V> intersection(CodePointMap<V> otherMap) {
		TreeRangeMapCodePointMap<V> result = new TreeRangeMapCodePointMap<>();
		if (isEmpty() || otherMap.isEmpty()) {
			return result;
		}
		Iterator<Entry<Range, V>> self = iterator();
		Iterator<Entry<Range, V>> other = otherMap.iterator();
		Entry<Range, V> otherEntry = other.next();
		while (self.hasNext()) {
			Entry<Range, V> selfEntry = self.next();
			while (otherEntry != null && selfEntry.getKey().max <= otherEntry.getKey().min) {
				if (otherEntry.getKey().max <= selfEntry.getKey().min) {
					// other fully below self. skip this other range
				} else if (otherEntry.getKey().min <= selfEntry.getKey().max) {
					break; // advance the self iterator
				} else {
					int min = Math.max(selfEntry.getKey().min, otherEntry.getKey().min);
					int max = Math.max(selfEntry.getKey().min, otherEntry.getKey().min);
					assertSameValuesInRange(min, max, selfEntry.getValue(), otherEntry.getValue());
					result.put(min, max, selfEntry.getValue());
				}
				if (other.hasNext()) {
					otherEntry = other.next();
				} else {
					return result;
				}
			}
		}
		return result;
	}

	default CodePointMap<V> subRangeMap(CodePointMap<V> other) { return intersection(other); }

	default CodePointMap<V> compliment(V value) { return new ComplimentCodePointMap<>(this, value); }

	default Map<Range, V> asMapOfRanges() {
		return stream().collect(Collectors.toMap(e-> new Range(e.getKey()), Entry::getValue));
	}

	default CodePointMap<V> union(CodePointMap<V> other) {
		TreeRangeMapCodePointMap<V> result = new TreeRangeMapCodePointMap<>(this);
		result.putAll(other);
		return result;
	}

	default CodePointMap<V> difference(CodePointMap<V> other) {
		TreeRangeMapCodePointMap<V> result = new TreeRangeMapCodePointMap<>(this);
		result.removeAll(other);
		return result;
	}

	@NonNull Iterator<Entry<Range, V>> iterator(); // The iterator modifies the entry and range. Do not keep references

	default @NonNull Stream<Entry<Range, V>> stream() {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false);
	}

	@Override
	boolean equals(@Nullable Object other);

	@Override
	int hashCode();

	interface ImmutableCodePointMap<V> extends CodePointMap<V> {}

	interface MutableCodePointMap<V> extends CodePointMap<V> {

		@FunctionalInterface
		interface CodePointRemapFunction<V> {
			@Nullable V apply(int codePoint, int oldMin, int oldMax, @Nullable V oldValue);
		}

		@FunctionalInterface
		interface RangeRemapFunction<V> {
			@Nullable V apply(int min, int max, @Nullable V oldValue);
		}

		default void put(int codePoint, V value) { put(codePoint, codePoint+1, value); }

		void put(int min, int max, V value);

		@Nullable V compute(int codePoint, CodePointRemapFunction<V> remappingFunction);

		@Nullable V compute(int min, int max, RangeRemapFunction<V> remappingFunction);

		default @Nullable V computeIfAbsent(int codePoint, Function<Integer, ? extends @Nullable V> mappingFunction) {
			return compute(codePoint,
					(cp, min, max, oldValue) -> oldValue == null ? mappingFunction.apply(cp) : null);
		}

		default @Nullable V computeIfAbsent(
				int min, int max, BiFunction<Integer, Integer, ? extends @Nullable V> mappingFunction) {
			return compute(min, max, (oldMin, oldMax, old) -> old == null ? mappingFunction.apply(min, max) : null);
		}

		default @Nullable V computeIfPresent(int codePoint, CodePointRemapFunction<V> remappingFunction) {
			return compute(codePoint,
					(cp, min, max, oldValue) ->
							oldValue == null
									? null
									: remappingFunction.apply(cp, min, max, oldValue));
		}

		default @Nullable V computeIfPresent(
				int min, int max, RangeRemapFunction<V> remappingFunction) {
			return compute(min, max,
					(oldMin, oldMax, old) -> old == null ? null : remappingFunction.apply(oldMin, oldMax, old));
		}

		void putAll(CodePointMap<V> other);

		default void putIfAbsent(int codePoint, V value) {
			compute(codePoint, (cp, min, max, old) -> old == null ? value : old);
		}

		default void putIfAbsent(int min, int max, V value) {
			if (!containsKeys(min, max)) put(min, max, value);
		}

		default void replace(int codePoint, V value) {
			compute(codePoint, (cp, min, max, old) -> old == null ? null : value);
		}

		default void replace(int min, int max, V value) {
			compute(min, max, (oldMin, oldMax, old) -> old == null ? null : value);
		}

		default void replaceAll(RangeRemapFunction<V> function) {
			compute(0, MAX_CODE_POINT, (oldMin, oldMax, old) -> old == null ? null : function.apply(oldMin, oldMax, old));
		}

		default void remove(int codePoint) { compute(codePoint, (cp, min, max, old) -> null); }

		void remove(int min, int max);

		default void remove(int codePoint, V value) {
			compute(codePoint, (cp, min, max, old) -> old == value ? null : old);
		}

    default void remove(int min, int max, V value) {
      compute(min, max, (oldMin, oldMax, old) -> old == value ? null : old);
		}

		default void removeAll(CodePointMap<V> other) {
			other.forEach((range, value) -> { if (value != null) remove(range.min, range.max); });
		}

		default void clear() { remove(0, MAX_CODE_POINT); }
	}

	static void assertSameValuesInRange(int min, int max, Object selfValue, Object otherValue) {
		if (!selfValue.equals(otherValue)) {
			throw new ConflictingMappingException(
					"this map has value "
					+ selfValue
					+ " for Range "
					+ min
					+ "-"
					+ max
					+ ", but other map has value "
					+ otherValue);
		}
	}

	final class Range {
		public int min; //inclusive
		public int max; //exclusive
		public Range(int value) {
			min = value;
			max = value+1;
		}
		public Range(int min, int max) {
			this.min = min;
			this.max = max;
		}
		public Range(Range other) {
			this.min = other.min;
			this.max = other.max;
		}
		@Override
		public boolean equals(@Nullable Object other) {
			if (!(other instanceof Range)) {
				return false;
			}
			Range rhs = (Range) other;
			return min==rhs.min && max==rhs.max;
		}

		@Override
		public int hashCode() {
			return min * max;
		}

		@Override
		public String toString() {
			return "Range{" + min + "-" + max + "}";
		}
	}

	final class ImmutableEntry<V> implements Map.Entry<Range, V> {
		final Range key;
		final V value;

		ImmutableEntry(Range key, V value) {
			this.key = key;
			this.value = value;
		}

		@Override public Range getKey() {
			return key;
		}

		@Override public V getValue() {
			return value;
		}

		@Override public V setValue(V v) {
			throw new UnsupportedOperationException("ImmutableEntry doesn't support setValue");
		}
	}

	class ImmutableRangeMapCodePointMap<V> implements ImmutableCodePointMap<V> {
		private final ImmutableRangeMap<Integer, V> rangeMap;

		public ImmutableRangeMapCodePointMap() {
			rangeMap = ImmutableRangeMap.of();
		}
		public ImmutableRangeMapCodePointMap(CodePointMap<V> other) {
			ImmutableRangeMap.Builder<Integer, V> b = ImmutableRangeMap.builder();
			other.forEach(
					(range, value) -> b.put(closedOpen(range.min, range.max), value));
			rangeMap = b.build();
		}
		private ImmutableRangeMapCodePointMap(ImmutableRangeMap<Integer, V> rangeMap) {
			this.rangeMap = rangeMap;
		}

		@Override public boolean isEmpty() {
			return rangeMap.span().isEmpty();
		}

		@Override public boolean containsKeys(int min, int max) {
			return !rangeMap.subRangeMap(closedOpen(min, max)).span().isEmpty();
		}

		// TODO: Optimize other methods to not rely on this, like they currently do.
		@Override public Set<Entry<Range, V>> entrySet() {
			return rangeMap.asMapOfRanges().entrySet().stream().map(e -> new ImmutableEntry<>(
					new Range(e.getKey().lowerEndpoint(), e.getKey().upperEndpoint()),
					e.getValue())).collect(
					Collectors.toSet());
		}

		@Override public @PolyNull V getOrDefault(int codePoint, @Nullable V defaultValue) {
			V r = rangeMap.get(codePoint);
			return r != null ? r : defaultValue;
		}

		@Override public CodePointMap<V> intersection(int min, int max) {
			return new ImmutableRangeMapCodePointMap<>(rangeMap.subRangeMap(closedOpen(min, max)));
		}

		@NonNull @Override public Iterator<Entry<Range, V>> iterator() {
			return entrySet().iterator();
		}
	}

	class TreeRangeMapCodePointMap<V> implements MutableCodePointMap<V> {
		private final RangeMap<Integer, V> rangeMap;

		public TreeRangeMapCodePointMap() {
			rangeMap = ImmutableRangeMap.of();
		}
		public TreeRangeMapCodePointMap(CodePointMap<V> other) {
			rangeMap = TreeRangeMap.create();
			other.forEach(
					(range, value) -> rangeMap.put(closedOpen(range.min, range.max), value));
		}
		private TreeRangeMapCodePointMap(RangeMap<Integer, V> rangeMap) {
			this.rangeMap = rangeMap;
		}

		@Override public boolean isEmpty() {
			return rangeMap.span().isEmpty();
		}

		@Override public boolean containsKeys(int min, int max) {
			return false;
		}

		// TODO: Optimize other methods to not rely on this, like they currently do.
		@Override public Set<Entry<Range, V>> entrySet() {
			return rangeMap.asMapOfRanges().entrySet().stream().map(e -> new ImmutableEntry<>(
					new Range(e.getKey().lowerEndpoint(), e.getKey().upperEndpoint()),
					e.getValue())).collect(
					Collectors.toSet());
		}

		@Override public @PolyNull V getOrDefault(int codePoint, @Nullable V defaultValue) {
			V r = rangeMap.get(codePoint);
			return r != null ? r : defaultValue;
		}

		@Override public CodePointMap<V> intersection(int min, int max) {
			return new TreeRangeMapCodePointMap<>(rangeMap.subRangeMap(closedOpen(min, max)));
		}

		@NonNull @Override public Iterator<Entry<Range, V>> iterator() {
			return entrySet().iterator();
		}

		@Override public void put(int min, int max, V value) {
			rangeMap.put(closedOpen(min, max), value);

		}

		@Override public @Nullable V compute(int codePoint, CodePointRemapFunction<V> remappingFunction) {
			Entry<com.google.common.collect.Range<Integer>, V> oldRange = rangeMap.getEntry(codePoint);
			V newValue = remappingFunction.apply(
					codePoint,
					oldRange != null ? oldRange.getKey().lowerEndpoint() : codePoint,
					oldRange != null ? oldRange.getKey().upperEndpoint() : codePoint,
					oldRange.getValue());
			if (newValue == null) {
				rangeMap.remove(closedOpen(codePoint, codePoint+1));
			} else {
				rangeMap.put(closedOpen(codePoint, codePoint+1), newValue);
			}
			return oldRange == null ? null : oldRange.getValue();
		}

		@Override public @Nullable V compute(int min, int max, RangeRemapFunction<V> remappingFunction) {
			Map<com.google.common.collect.Range<Integer>, V> map = rangeMap.subRangeMap(closedOpen(min, max))
					.asMapOfRanges();
			throw new UnsupportedOperationException("TODO");
		}

		@Override public void putAll(CodePointMap<V> other) {
			other.forEach((range, value) -> rangeMap.put(closedOpen(range.min, range.max), value));
		}

		@Override public void remove(int min, int max) {
			rangeMap.remove(closedOpen(min, max));
		}
	}

	class ComplimentCodePointMap<V> implements CodePointMap<V> {
		private final CodePointMap<?> other;
		private final V value;

		ComplimentCodePointMap(CodePointMap<?> other, V value) {
			this.other = other;
			this.value = value;
		}


		@Override public boolean isEmpty() {
			return !other.isEmpty();
		}

		@Override public boolean containsKeys(int min, int max) {
			return !other.containsKeys(min, max);
		}

		@Override
    public Set<Entry<Range, V>> entrySet() {
      throw new UnsupportedOperationException("TODO");
		}

		@Override public @PolyNull V getOrDefault(int codePoint, @Nullable V defaultValue) {
			return other.get(codePoint) == null ? value : defaultValue;
		}

		@Override public CodePointMap<V> intersection(int min, int max) {
			throw new UnsupportedOperationException("TODO");
		}

		@NonNull @Override public Iterator<Entry<Range, V>> iterator() {
			throw new UnsupportedOperationException("TODO");
		}
	}

	class DuplicateCodePointException extends IllegalArgumentException {
		DuplicateCodePointException() {}
		DuplicateCodePointException(String message) {
			super(message);
		}
		DuplicateCodePointException(String message, Throwable cause) {
			super(message, cause);
		}
		DuplicateCodePointException(Throwable cause) {
			super(cause);
		}
	}

	class ConflictingMappingException extends IllegalArgumentException {
		ConflictingMappingException() {}
		ConflictingMappingException(String message) {
			super(message);
		}
		ConflictingMappingException(String message, Throwable cause) {
			super(message, cause);
		}
		ConflictingMappingException(Throwable cause) {
			super(cause);
		}
	}
}

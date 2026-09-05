package com.tbohne.llkpattern;

import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;

public class TreeCodePointMap<V> implements MutableCodePointMap<V> {
	// All Ranges are openClosed
	private final RangeMap<Integer, V> rangeMap;

	public TreeCodePointMap() {
		rangeMap = TreeRangeMap.create();
	}

	public TreeCodePointMap(TreeCodePointMap<V> other) {
		rangeMap = TreeRangeMap.create();
		rangeMap.putAll(other.rangeMap);
	}

	private TreeCodePointMap(RangeMap<Integer, V> rangeMap) {
		this.rangeMap = rangeMap;
	}

	@Override
	public void clear() {
		rangeMap.clear();
	}

	@Override
	public boolean isEmpty() {
		return rangeMap.asMapOfRanges().isEmpty();
	}

	@Override
	public boolean containsKey(int codePoint) {
		return rangeMap.get(codePoint) != null;
	}

	@Override
	public boolean containsKey(int min, int max) {
		RangeMap<Integer, V> subrange = rangeMap.subRangeMap(Range.closedOpen(min, max));
		return !subrange.asMapOfRanges().isEmpty();
	}

	@Override
	public Set<Entry<Range<Integer>, V>> entrySet() {
		return rangeMap.asMapOfRanges().entrySet();
	}

	@Override
	public Set<Range<Integer>> keySet() {
		return rangeMap.asMapOfRanges().keySet();
	}

	@Override
	public @Nullable V get(int codePoint) {
		return rangeMap.get(codePoint);
	}

	@Override
	public @PolyNull V getOrDefault(int codePoint, @Nullable V defaultValue) {
		V found = rangeMap.get(codePoint);
		if (found != null) {
			return found;
		}
		return defaultValue;
	}

	@Override
	public void forEach(
			BiConsumer<Range<Integer>, ? super V> action) {
		rangeMap.asMapOfRanges().forEach(action);
	}

	@Override
	public TreeCodePointMap<V> intersection(int min, int max) {
		return new TreeCodePointMap<>(rangeMap.subRangeMap(Range.closedOpen(min, max+1)));
	}

	@Override
	public TreeCodePointMap<V> intersection(CodePointMap<V> other) {
		TreeCodePointMap<V> r = new TreeCodePointMap<>();
		for (Range<Integer> otherRange : other.asMapOfRanges().keySet()) {
			r.rangeMap.putAll(rangeMap.subRangeMap(otherRange));
		}
		return r;
	}

	@Override
	public TreeCodePointMap<V> subRangeMap(CodePointMap<V> other) {
		return intersection(other);
	}

	@Override
	public ComplimentCodePointMap<V> compliment(V value) {
		return new ComplimentCodePointMap<>(this, value);
	}

	@Override
	public Map<Range<Integer>, V> asMapOfRanges() {
		return rangeMap.asMapOfRanges();
	}


	@Override
	public void put(int codePoint, V value) {
		V prior = rangeMap.get(codePoint);
		if (prior != null && !prior.equals(value)) {
			throw new DuplicateCodePointException("duplicate codePoint " + codePoint);
		}
		rangeMap.put(Range.closedOpen(codePoint, codePoint+1), value);
	}

	@Override
	public void put(int min, int max, V value) {
		Range<Integer> newRange = Range.closedOpen(min, max+1);
		RangeMap<Integer, V> overlapRangeMap = rangeMap.subRangeMap(newRange);
		Map<Range<Integer>, V> overlap =overlapRangeMap.asMapOfRanges();
		if (overlap.size() == 1) {
			Map.Entry<Range<Integer>, V> overlapRange
					= overlap.entrySet().stream().findFirst().get();
			if (!overlapRange.getValue().equals(value)) {
				throw new DuplicateCodePointException("duplicate codePoints " + overlapRange.getKey());
			}
		} else if (overlap.size() > 1) {
			Range<Integer> overlapRange = overlapRangeMap.span();
			throw new DuplicateCodePointException("duplicate codePoints " + overlapRange);
    }
		rangeMap.put(newRange, value);
	}

	@Override
	public V compute(int codePoint,
			BiFunction<Integer, ? super V, ? extends V> remappingFunction) {
		V before = rangeMap.get(codePoint);
		rangeMap.put(Range.closedOpen(codePoint, codePoint+1),
				remappingFunction.apply(codePoint, before));
		return before;
	}

	@Override
	@Deprecated
	public V compute(int min, int max,
			BiFunction<Integer, ? super V, ? extends V> remappingFunction) {
		throw new UnsupportedOperationException("TODO");
	}

	@Override
	public V computeIfAbsent(int codePoint,
			Function<Integer, ? extends V> mappingFunction) {
		V before = rangeMap.get(codePoint);
		if (before != null) {
			return before;
		}
		V after = mappingFunction.apply(codePoint);
		rangeMap.put(Range.closedOpen(codePoint, codePoint+1), after);
		return after;
	}

	@Override
	@Deprecated
	public V computeIfAbsent(int min, int max,
			Function<Integer, ? extends V> mappingFunction) {
		throw new UnsupportedOperationException("TODO");
	}

	@Override
	public V computeIfPresent(int codePoint,
			BiFunction<Integer, ? super V, ? extends V> remappingFunction) {
		V before = rangeMap.get(codePoint);
		if (before == null) {
			return null;
		}
		V after = remappingFunction.apply(codePoint, before);
		rangeMap.put(Range.closedOpen(codePoint, codePoint+1), after);
		return after;
	}

	@Override
	@Deprecated
	public V computeIfPresent(int min, int max,
			BiFunction<Integer, ? super V, ? extends V> remappingFunction) {
		throw new UnsupportedOperationException("TODO");
	}

	@Override
	public void putAll(CodePointMap<V> other) {
		for (Entry<Range<Integer>, V> entry : other.asMapOfRanges().entrySet()) {
			put(
					entry.getKey().lowerEndpoint(),
					entry.getKey().upperEndpoint() -1,
					entry.getValue());
		}
	}

	@Override
	public TreeCodePointMap<V> union(CodePointMap<V> other) {
		TreeCodePointMap<V> r = new TreeCodePointMap<>(this);
		r.putAll(other);
		return r;
	}

	@Override
	public void putIfAbsent(int codePoint, V value) {
		V prior = rangeMap.get(codePoint);
		if (prior == null) {
			rangeMap.put(Range.closedOpen(codePoint, codePoint+1), value);
		}
	}

	@Override
	public void putIfAbsent(int min, int max, V value) {
		Range<Integer> newRange = Range.closedOpen(min, max+1);
		Map<Range<Integer>, V> overlap = rangeMap.subRangeMap(newRange).asMapOfRanges();
    if (overlap.isEmpty()) {
      rangeMap.put(newRange, value);
		}
	}

	@Override
	public void replace(int codePoint, V value) {
		V before = rangeMap.get(codePoint);
		if (before == null) {
			return;
		}
		rangeMap.put(Range.closedOpen(codePoint, codePoint+1), value);
	}

	@Override
	@Deprecated
	public void replace(int min, int max, V value) {
		throw new UnsupportedOperationException("TODO");
	}

	@Override
	@Deprecated
	public void replaceAll(BiFunction<Integer, ? super V, ? extends V> function) {
		throw new UnsupportedOperationException("TODO");
	}

	@Override
	public void remove(int codePoint) {
		rangeMap.remove(Range.closedOpen(codePoint, codePoint+1));
	}

	@Override
	public void remove(int codePoint, V value) {
		if (rangeMap.get(codePoint) == value) {
			rangeMap.remove(Range.closedOpen(codePoint, codePoint+1));
		}
	}

	@Override
	@Deprecated
	public void remove(int min, int max, V value) {
		throw new UnsupportedOperationException("TODO");
	}

	@Override
	public void removeAll(CodePointMap<V> other) {
		for (Range<Integer> range : other.asMapOfRanges().keySet()) {
			rangeMap.remove(range);
		}
	}

	@Override
	public TreeCodePointMap<V> difference(CodePointMap<V> other) {
		TreeCodePointMap<V> r = new TreeCodePointMap<>(this);
		r.removeAll(other);
		return r;
	}

	@Override
	public String toString() {
		return rangeMap.toString();
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (other instanceof TreeCodePointMap) {
			return rangeMap.equals(((TreeCodePointMap<?>) other).rangeMap);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return rangeMap.hashCode();
	}
}

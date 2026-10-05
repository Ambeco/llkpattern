package com.tbohne.llkpattern.impl.constructs;

import java.util.Arrays;

/**
 * A minimal append-only array list of {@link PatternConstruct}s.
 *
 * <p>Replaces {@code ArrayList}/{@code List.of} on the compile path: on ART each {@code ArrayList.get} paid
 * {@code Objects.checkIndex} frames, and {@code List.of} is desugared to an ArrayList plus an unmodifiable
 * wrapper (Pixel 3a compile sampling, 2026-10-04). Hot loops may read {@link #items} directly up to {@link
 * #size}; slots at or beyond {@code size} are null.
 */
public final class ConstructList {
	static final ConstructList EMPTY = new ConstructList(0);

	public PatternConstruct[] items;
	public int size;

	public ConstructList(int initialCapacity) {
		items = new PatternConstruct[initialCapacity];
	}

	static ConstructList of(PatternConstruct only) {
		ConstructList list = new ConstructList(1);
		list.add(only);
		return list;
	}

	// Slots below `size` are always non-null; Arrays.copyOf's padding is what the checker objects to.
	@SuppressWarnings("nullness")
	public void add(PatternConstruct construct) {
		if (size == items.length) {
			items = Arrays.copyOf(items, Math.max(4, size * 2));
		}
		items[size++] = construct;
	}

	public PatternConstruct get(int index) {
		if (index >= size) {
			throw new IndexOutOfBoundsException("index " + index + " >= size " + size);
		}
		return items[index];
	}

	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size == 0;
	}
}

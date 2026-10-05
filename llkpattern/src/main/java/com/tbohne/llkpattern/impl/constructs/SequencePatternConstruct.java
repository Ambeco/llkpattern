package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class SequencePatternConstruct extends PatternConstruct {
	// Pre-sized to 4: 100% of sequences have <= 4 elements, mean 1.15 (corpus measurement, 2026-09-27).
	public final ConstructList patterns = new ConstructList(4);

	public SequencePatternConstruct(int startIndex) {
		super(startIndex);
	}

	// Wires every element's next tail-to-front (a plain assignment, not compile()), so a nullable element can
	// fold in what follows it when asked for its entry point. Idempotent: buildEntryMap or claimsEntryElse
	// may run first.
	private void wireElementNextPointers() {
		PatternConstruct tail = next();
		PatternConstruct[] parts = patterns.items;
		for (int i = patterns.size - 1; i >= 0; i--) {
			parts[i].next = tail;
			tail = parts[i];
		}
	}

	@Override
	boolean claimsEntryElse() {
		// A sequence's entry point is its first element's. patterns.get(0) is fixed, so delegating can't add a
		// cycle, but its own entry-point computation needs the wiring below to have run.
		wireElementNextPointers();
		return patterns.items[0].claimsEntryElse();
	}

	@Override
	boolean elseIsEndOfFind() {
		return patterns.get(0).elseIsEndOfFind();
	}

	@Override
	boolean elseIsResidual() {
		return patterns.get(0).elseIsResidual();
	}

	@Override
	boolean needsEntryPointBeforeMatcher() {
		// buildMatcher() wires `next` itself (via part.compile(tail)) and never reads entryMap, so this sequence
		// needs nothing buildEntryMap computes. That lets a leaf reached only through a sequence (e.g. a plain
		// "a" union branch) skip its own entryMap allocation; otherwise this compile() would force the pull.
		return false;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		// Entering the sequence means entering its first element. Wire every `next` FIRST but deliberately don't
		// compile() here: that split lets a loop at this sequence's tail ask an enclosing loop for ITS entry
		// point mid-construction without forcing that loop's in-progress matcher build (design.md
		// "Entry-point computation vs. matcher compilation").
		wireElementNextPointers();
		PatternConstruct first = patterns.items[0];
		// Aliased, not copied: entryMap is a plain set. (entryElse is re-keyed onto `this`; see
		// QuantifiedUnionPatternConstruct.)
		entryMap = first.getEntryPointMap();
		if (first.getEntryElse() != null) {
			entryElse = this;
		}
	}

	@Override
	void buildMatcher() {
		// No matching behavior of its own: it is whatever its first element compiled to, so our dispatch gating
		// goes on that element (safe to propagate directly: it is exclusively ours and not yet compiled).
		PatternConstruct first = patterns.items[0];
		first.dispatchEntrySet = dispatchEntrySet;
		first.dispatchFailedEntry = dispatchFailedEntry;
		// Compile tail-to-front: the last element's next is ours, each earlier one's is the element after it.
		PatternConstruct tail = next();
		PatternConstruct[] parts = patterns.items;
		for (int i = patterns.size - 1; i >= 0; i--) {
			PatternConstruct part = parts[i];
			if (part instanceof WordBoundaryPatternConstruct && i > 0) {
				((WordBoundaryPatternConstruct) part).priorCharSet = parts[i - 1].lastCharSet();
			}
			part.compile(tail);
			tail = part;
		}
		matcher = first.matcher();
	}

	@Override
	final CodePointSet skipZeroWidthEntrySet(boolean checkAssertions, @Nullable CodePointSet bodyLastCharSet) {
		return patterns.isEmpty()
				? getEntryPointMap()
				: patterns.get(0).skipZeroWidthEntrySet(checkAssertions, bodyLastCharSet);
	}

	@Override
	final @Nullable CodePointSet lastCharSet() {
		return patterns.isEmpty() ? null : patterns.get(patterns.size() - 1).lastCharSet();
	}

	@Override
	final @Nullable CodePointSet firstCharSet() {
		return patterns.isEmpty() ? null : patterns.get(0).firstCharSet();
	}

	@Override
	public final LookbehindPatternConstruct.@Nullable SingleCodePointBody resolveSingleCodePointBody() {
		return patterns.size() == 1 ? patterns.get(0).resolveSingleCodePointBody() : null;
	}
}

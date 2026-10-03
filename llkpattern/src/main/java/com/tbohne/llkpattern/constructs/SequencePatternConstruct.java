package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class SequencePatternConstruct extends PatternConstruct {
	// Pre-sized to 4, not the JDK default of 10 -- corpus measurement (2026-09-27) found 100% of
	// Sequences end up with <=4 elements (mean 1.15), so the default's first-`add` grow to 10 is
	// pure waste here.
	public final List<PatternConstruct> patterns = new ArrayList<>(4);

	public SequencePatternConstruct(int startIndex) {
		super(startIndex);
	}

	/**
	 * Wires every element's {@code next} pointer tail-to-front (a plain field assignment, not a
	 * {@code compile()} call) so a nullable element can still fold in what follows it when asked
	 * for its own entry point -- shared by {@link #buildEntryMap} and {@link #claimsEntryElse},
	 * since either one might run first (or, harmlessly, both -- this is idempotent). See
	 * design.md's "Entry-point computation vs. matcher compilation" section for why the split
	 * from compiling matters.
	 */
	private void wireElementNextPointers() {
		PatternConstruct tail = next();
		for (int i = patterns.size() - 1; i >= 0; i--) {
			patterns.get(i).next = tail;
			tail = patterns.get(i);
		}
	}

	@Override
	boolean claimsEntryElse() {
		// Same aliasing as buildEntryMap below: a sequence's own entry point is exactly its
		// first element's. patterns.get(0) is a fixed field (never reassigned the way `next`
		// is), so delegating straight through can't itself introduce a cycle -- but its OWN
		// entry-point computation still depends on the tail-to-front wiring below having run.
		wireElementNextPointers();
		return patterns.get(0).claimsEntryElse();
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
		// buildMatcher() below does its own tail-to-front `next` wiring independently (via each
		// part.compile(tail) call), and never reads entryMap/entryElse -- so, unlike buildEntryMap
		// above (whose wiring/entryMap-caching exists purely to answer an ANCESTOR's pull), this
		// SequencePatternConstruct's own matcher build needs nothing buildEntryMap() would have computed. This is
		// what lets a leaf branch reached only through a SequencePatternConstruct (e.g. a plain "a" union branch,
		// always parsed as a one-element SequencePatternConstruct) skip its own entryMap allocation too --
		// otherwise this SequencePatternConstruct's own compile() would force the pull right back regardless of
		// what the leaf itself does.
		return false;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		// A sequence's own entry point is exactly its first element's -- entering the sequence
		// means entering its first element, regardless of what the rest of the sequence looks
		// like. Wire every element's `next` pointer tail-to-front FIRST -- but deliberately don't
		// compile() (build matchers for) anything here: that's buildMatcher()'s job, below. This
		// split is what lets a loop nested at the tail of this sequence ask an enclosing loop
		// (this sequence's own `next`, if it's a loop) for ITS entry point mid-construction,
		// without forcing that enclosing loop's own (still in-progress) matcher build to finish
		// first -- see design.md's "Entry-point computation vs. matcher compilation" section.
		wireElementNextPointers();
		// Aliased directly, not re-keyed -- unlike `entryElse` (a genuinely PatternConstruct-valued
		// field, where re-keying onto `this` is load-bearing -- see QuantifiedUnionPatternConstruct's own doc for
		// the 2026-09-06 bug that motivated it), entryMap's values are always Boolean
		// `true` regardless of which construct built it (see entryMap's own doc), so this
		// SequencePatternConstruct's own entry point and its first element's are the exact same map, both in
		// content AND in every consumer's eyes -- there's no identity to lose by sharing the
		// object instead of copying its entries.
		entryMap = patterns.get(0).getEntryPointMap();
		if (patterns.get(0).getEntryElse() != null) {
			entryElse = this;
		}
	}

	@Override
	void buildMatcher() {
		// A SequencePatternConstruct has no matching behavior of its own -- it's exactly whatever its first
		// element compiled to, so any dispatch gating of our own (if this sequence is itself a
		// chain candidate) belongs on that first element's own node instead; safe to propagate
		// directly (no aliasOrPassThrough wrapper needed) since `patterns.get(0)` is exclusively
		// owned by this SequencePatternConstruct and hasn't been compiled by anyone else yet.
		patterns.get(0).dispatchEntrySet = dispatchEntrySet;
		patterns.get(0).dispatchFailedEntry = dispatchFailedEntry;
		// Compile tail-to-front: the last element's next is this sequence's own next, and each
		// earlier element's next is the element right after it (already compiled by the time we
		// get to it).
		PatternConstruct tail = next();
		for (int i = patterns.size() - 1; i >= 0; i--) {
			PatternConstruct part = patterns.get(i);
			if (part instanceof WordBoundaryPatternConstruct && i > 0) {
				((WordBoundaryPatternConstruct) part).priorCharSet = patterns.get(i - 1).lastCharSet();
			}
			part.compile(tail);
			tail = part;
		}
		matcher = patterns.get(0).matcher();
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

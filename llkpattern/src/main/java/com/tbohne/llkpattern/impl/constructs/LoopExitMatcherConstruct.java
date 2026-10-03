package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;

import java.util.List;

final class LoopExitMatcherConstruct extends MatcherConstruct {
	final int quantifiableIndex;
	final int min;
	// True iff the loop's TRUE min (before ReluctantLoopMatcherConstruct's own "+1" counting
	// shift, if this exit belongs to a reluctant-safe loop) is 0 -- kept as its own field, rather
	// than inferred from `min == 0` directly, because `min` itself may already be shifted (see
	// QuantifiablePatternConstruct.buildLoopMatcher), and exitIsPureEnd needs to ask about the real,
	// unshifted quantifier semantics regardless of which counting convention this exit's owning
	// loop happens to use for its own runtime check below.
	private final boolean minIsZero;

	LoopExitMatcherConstruct(int flags, int quantifiableIndex, int min, boolean minIsZero, MatcherConstruct next) {
		super(flags, next);
		this.quantifiableIndex = quantifiableIndex;
		this.min = min;
		this.minIsZero = minIsZero;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		if (matcher.quantifiableCounts[quantifiableIndex] < min) {
			return false;
		}
		// Never backtracks, so a failed attempt aborts the whole match rather than retrying
		// with stale counter state -- this reset (only on the successful exit path) is enough
		// to guarantee the slot is already 0 whenever this loop is next freshly (re-)entered.
		matcher.quantifiableCounts[quantifiableIndex] = 0;
		return next.match(matcher, peeked);
	}

	@Override
	final boolean collectExitAssertionChain(List<ZeroWidthAssertionGuard> chain) {
		return minIsZero && MatcherConstruct.collectExitAssertionChain(next, chain);
	}
}

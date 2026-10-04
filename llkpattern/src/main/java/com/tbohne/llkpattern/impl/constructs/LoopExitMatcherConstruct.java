package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;

import java.util.List;

/**
 * A loop's "stop iterating" node, reached when the body chain didn't match or {@link
 * LoopMatcherConstruct} forced a stop after {@code max}. Enforces {@code min} (failing if too few
 * iterations happened) and on success resets the shared counter before dispatching on. Never gated:
 * every code-point decision lives in the body chain's own entry checks. See design.md's
 * "Quantifier/loop compilation".
 */
final class LoopExitMatcherConstruct extends MatcherConstruct {
	final int quantifiableIndex;
	final int min;
	// Whether the loop's TRUE min is 0; `min` itself may be shifted by ReluctantLoopMatcherConstruct's
	// +1 counting, and collectExitAssertionChain needs the unshifted semantics.
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
		// Never backtracks, so a failed attempt aborts the whole match; resetting only on this
		// successful exit guarantees the slot is 0 whenever the loop is next entered.
		matcher.quantifiableCounts[quantifiableIndex] = 0;
		return next.match(matcher, peeked);
	}

	@Override
	final boolean collectExitAssertionChain(List<ZeroWidthAssertionGuard> chain) {
		return minIsZero && MatcherConstruct.collectExitAssertionChain(next, chain);
	}
}

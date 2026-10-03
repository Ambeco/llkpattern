package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;

import com.google.common.annotations.VisibleForTesting;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

final class LoopMatcherConstruct extends MatcherConstruct {
	final int quantifiableIndex;
	final int max;
	final PatternConstruct continuation;
	final MatcherConstruct exitNode;

	LoopMatcherConstruct(
			PatternConstruct owner, int quantifiableIndex, int max,
			PatternConstruct continuation, MatcherConstruct exitNode) {
		super(owner);
		this.quantifiableIndex = quantifiableIndex;
		this.max = max;
		this.continuation = continuation;
		this.exitNode = exitNode;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		// Unconditional: reaching this node at all means a body pass (the very first, or another
		// re-check after a prior successful one) has just finished, so this always represents one
		// more completed iteration -- regardless of which way the choice below then decides to go.
		int loopCount = ++matcher.quantifiableCounts[quantifiableIndex];
		return loopCount < max
				? castNonNull(continuation.matcher).match(matcher, peeked)
				: exitNode.match(matcher, peeked);
	}

	@VisibleForTesting
	MatcherConstruct getContinuation() { return castNonNull(continuation.matcher); }
}

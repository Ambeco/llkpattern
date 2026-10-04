package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;

import com.google.common.annotations.VisibleForTesting;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

/**
 * A loop's "continue or stop at max" node, used for a GREEDY loop and for a reluctant loop where
 * stopping early isn't provably safe (otherwise {@link ReluctantLoopMatcherConstruct}). Reached
 * only as a loop body's continuation (see {@code QuantifiablePatternConstruct.buildLoopMatcher}),
 * never as the loop's entry point: the body chain's head IS the entry point.
 *
 * <p>Conceptually: "one more body iteration just finished: retry the body if under {@code max},
 * else force an exit via {@link #exitNode}, which enforces {@code min}." Neither node tests
 * code-point membership; that is the body chain's own {@code entrySet}/{@code failedEntry} job (the
 * body defers to {@code exitNode} when it doesn't match). See design.md's "Quantifier/loop
 * compilation".
 *
 * <p>Its own class because its "continue" successor (the body chain head) isn't known until AFTER
 * this node has self-registered onto the {@link LoopBackPatternConstruct} it owns, breaking the
 * construction-time cycle every loop body creates. So {@code continuation} is a plain final
 * {@code PatternConstruct} and {@code matchBody()} reads {@code continuation.matcher}, reusing the
 * one assign-after-the-fact mechanism the codebase already relies on instead of a mutable field.
 */
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
		// Unconditional: reaching this node means a body pass just finished, one more completed
		// iteration whichever way the choice below goes.
		int loopCount = ++matcher.quantifiableCounts[quantifiableIndex];
		return loopCount < max
				? castNonNull(continuation.matcher).match(matcher, peeked)
				: exitNode.match(matcher, peeked);
	}

	@VisibleForTesting
	MatcherConstruct getContinuation() { return castNonNull(continuation.matcher); }
}

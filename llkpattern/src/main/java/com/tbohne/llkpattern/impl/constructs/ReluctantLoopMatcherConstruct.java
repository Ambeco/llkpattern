package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;

import com.google.common.annotations.VisibleForTesting;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

/**
 * The reluctant counterpart of {@link LoopMatcherConstruct}, used INSTEAD of it for a reluctant
 * loop where stopping early is provably safe: {@code min} is satisfied AND {@link #exitNode}'s
 * continuation is a zero-width path that unconditionally reaches {@link EndMatcherConstruct},
 * decided once at compile time by {@code QuantifiablePatternConstruct.buildLoopMatcher}. Unlike
 * {@code LoopMatcherConstruct}, this node is BOTH the loop's entry point and the body's loop-back
 * target (the same instance), so the "stop here" check runs before the first iteration too (needed
 * for {@code min == 0} loops like {@code a*?}/{@code a??}).
 *
 * <p>Because it is reached both fresh and after an iteration, every VISIT increments {@code
 * quantifiableCounts[idx]}, so it always holds {@code completedIterations + 1}. {@link
 * #shiftedMin}/{@link #shiftedMax} are min/max pre-shifted by that +1 (capped, not wrapped, for an
 * unbounded max). The loop's {@link LoopExitMatcherConstruct} (reached via the body's {@code
 * failedEntry}, bypassing this node) is built with the SAME shifted min.
 *
 * <p>Whether the exit succeeds also depends on {@link Matcher#requireFullMatch}, read at match time
 * since one compiled pattern serves {@code matches()}, {@code find()} and {@code lookingAt()}.
 * Never speculative: unlike a backtracking engine's "try shorter, undo on failure", {@code
 * exitNode} only runs once success is guaranteed, so its side effects (resetting the counter,
 * ending a capture) never need undoing.
 *
 * <p>{@link #exitAssertionChain} (possibly empty or null; see {@code
 * MatcherConstruct#exitAssertionChain}) covers an exit proof that runs through {@code \b}/{@code
 * \B}/{@code ^}/{@code $} assertions rather than reaching End directly. Each guard is evaluated
 * (side-effect-free) BEFORE committing, so a false result just continues greedily and nothing is
 * rolled back.
 */
final class ReluctantLoopMatcherConstruct extends MatcherConstruct {
	final int quantifiableIndex;
	final int shiftedMin;
	final int shiftedMax;
	final PatternConstruct continuation;
	final MatcherConstruct exitNode;
	final @Nullable List<ZeroWidthAssertionGuard> exitAssertionChain;

	ReluctantLoopMatcherConstruct(
			PatternConstruct owner, int quantifiableIndex, int shiftedMin, int shiftedMax,
			PatternConstruct continuation, MatcherConstruct exitNode,
			@Nullable List<ZeroWidthAssertionGuard> exitAssertionChain) {
		super(owner);
		this.quantifiableIndex = quantifiableIndex;
		this.shiftedMin = shiftedMin;
		this.shiftedMax = shiftedMax;
		this.continuation = continuation;
		this.exitNode = exitNode;
		this.exitAssertionChain = exitAssertionChain;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		// Unconditional on every visit: counts visits, not completed iterations (see class doc).
		int count = ++matcher.quantifiableCounts[quantifiableIndex];
		// Under requireFullMatch, exiting is still safe once pos reached regionEnd: the exit proof
		// guarantees the rest needs no more input, and stopping avoids a doomed extra body attempt
		// that would peek past the end and set hitEnd. Checked BEFORE the max comparison on purpose:
		// once count reaches shiftedMax both branches call the same exitNode (which re-derives any
		// unsatisfied guard), so checking this first just lets the loop stop before max when it can.
		if (count >= shiftedMin && (!matcher.requireFullMatch || matcher.pos == matcher.regionEnd)
				&& assertionChainHolds(matcher, peeked)) {
			return exitNode.match(matcher, peeked);
		}
		return count < shiftedMax
				? castNonNull(continuation.matcher).match(matcher, peeked)
				: exitNode.match(matcher, peeked);
	}

	private boolean assertionChainHolds(Matcher matcher, int peeked) {
		if (exitAssertionChain == null) {
			return true;
		}
		for (ZeroWidthAssertionGuard guard : exitAssertionChain) {
			if (!guard.holdsHere(matcher, peeked)) {
				return false;
			}
		}
		return true;
	}

	@Override
	final boolean collectExitAssertionChain(List<ZeroWidthAssertionGuard> chain) {
		return MatcherConstruct.collectExitAssertionChain(exitNode, chain);
	}

	@VisibleForTesting
	MatcherConstruct getContinuation() { return castNonNull(continuation.matcher); }
}

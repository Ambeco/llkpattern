package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;

import com.google.common.annotations.VisibleForTesting;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

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
		// Unconditional on every visit -- see class doc: this represents "one more visit to this
		// decision point", not "one more completed iteration" (that's what the +1 shift in
		// shiftedMin/shiftedMax corrects for).
		int count = ++matcher.quantifiableCounts[quantifiableIndex];
		// Under requireFullMatch (matches()), exiting is still safe once pos already reached
		// regionEnd -- exitAssertionChain(next) already guarantees the rest of the pattern needs no
		// further input once its own guards (if any) hold, so if there's none left to require,
		// stopping here is exactly what a backtracking engine's reluctant loop does too, and (unlike
		// letting the body run one more, doomed attempt) avoids spuriously peeking past the end and
		// setting Matcher#hitEnd. This condition, if true, is checked BEFORE the ordinary max
		// comparison below on purpose: since min <= max always holds (so shiftedMin <= shiftedMax
		// too), whenever count has already reached shiftedMax this condition is either already true
		// (exit either way) or blocked only by requireFullMatch/regionEnd/an unsatisfied assertion
		// guard, in which case the max comparison below forces exactly the same exitNode call anyway
		// (which then independently re-derives and correctly fails on the same unsatisfied guard, if
		// that's what's blocking) -- so the two branches always agree on the max-reached case, and
		// checking this one first just lets a reluctant loop stop before max when it can.
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

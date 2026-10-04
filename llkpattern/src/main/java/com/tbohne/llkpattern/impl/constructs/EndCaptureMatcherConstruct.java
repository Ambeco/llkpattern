package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;

import java.util.List;

final class EndCaptureMatcherConstruct extends MatcherConstruct {
	final int captureConstructIndex;

	EndCaptureMatcherConstruct(CaptureEndPatternConstruct owner, int captureConstructIndex, MatcherConstruct next) {
		super(owner, next);
		this.captureConstructIndex = captureConstructIndex;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		// Records only the end index: Matcher#group(int) builds the text lazily, and
		// BackReferenceMatcherConstruct compares against these indices directly (alloc sampling).
		matcher.captureGroups[captureConstructIndex * 2 + 1] = matcher.pos;
		return next.match(matcher, peeked);
	}

	@Override
	final boolean collectExitAssertionChain(List<ZeroWidthAssertionGuard> chain) {
		return MatcherConstruct.collectExitAssertionChain(next, chain);
	}
}

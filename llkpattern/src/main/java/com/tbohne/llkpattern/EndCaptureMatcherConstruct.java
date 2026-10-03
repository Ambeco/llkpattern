package com.tbohne.llkpattern;

import java.util.List;

final class EndCaptureMatcherConstruct extends MatcherConstruct {
	final int captureConstructIndex;

	EndCaptureMatcherConstruct(CaptureEndMarker owner, int captureConstructIndex, MatcherConstruct next) {
		super(owner, next);
		this.captureConstructIndex = captureConstructIndex;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		// Just records the end index -- no substring materialized here anymore. The captured
		// text is built lazily by Matcher#group(int), only if a caller actually asks for it (see
		// allocation sampling in benchmarks/Intel-i7-9750H_llkMatch_alloc_sampling.txt), and
		// BackReferenceMatcherConstruct above compares directly against these indices without
		// ever needing a String/CharSequence view at all.
		matcher.captureGroups[captureConstructIndex * 2 + 1] = matcher.pos;
		return next.match(matcher, peeked);
	}

	@Override
	final boolean collectExitAssertionChain(List<ZeroWidthAssertionGuard> chain) {
		return MatcherConstruct.collectExitAssertionChain(next, chain);
	}
}

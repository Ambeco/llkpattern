package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;



final class BeginCaptureMatcherConstruct extends MatcherConstruct {
	final int captureConstructIndex;

	BeginCaptureMatcherConstruct(PatternConstruct owner, int captureConstructIndex, MatcherConstruct next) {
		super(owner, next);
		this.captureConstructIndex = captureConstructIndex;
	}

	// Internal, non-self-registering variant for a capturing loop's shared "begin the next
	// iteration" node (see QuantifiablePatternConstruct.buildLoopMatcher).
	BeginCaptureMatcherConstruct(int captureConstructIndex, int flags, MatcherConstruct next) {
		super(flags, next);
		this.captureConstructIndex = captureConstructIndex;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		int base = captureConstructIndex * 2;
		matcher.captureGroups[base] = matcher.pos;
		// Reset the end slot too, so re-entering a capture in a loop fully overwrites the previous entry.
		matcher.captureGroups[base + 1] = -1;
		return next.match(matcher, peeked);
	}
}

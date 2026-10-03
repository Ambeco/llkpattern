package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import java.util.List;

public final class EndMatcherConstruct extends MatcherConstruct {
	EndMatcherConstruct(EndPatternConstruct owner) {
		super(owner);
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		return !matcher.requireFullMatch || matcher.pos == matcher.regionEnd;
	}

	@Override
	final boolean collectExitAssertionChain(List<ZeroWidthAssertionGuard> chain) {
		return true;
	}
}

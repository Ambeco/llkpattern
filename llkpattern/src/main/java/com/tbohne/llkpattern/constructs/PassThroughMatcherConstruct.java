package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import java.util.List;

final class PassThroughMatcherConstruct extends MatcherConstruct {
	PassThroughMatcherConstruct(PatternConstruct owner, MatcherConstruct next) {
		super(owner, next);
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		return next.match(matcher, peeked);
	}

	@Override
	final boolean collectExitAssertionChain(List<ZeroWidthAssertionGuard> chain) {
		return MatcherConstruct.collectExitAssertionChain(next, chain);
	}
}

package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import java.util.List;

abstract class ZeroWidthAssertionMatcherConstruct extends MatcherConstruct
		implements ZeroWidthAssertionGuard {
	ZeroWidthAssertionMatcherConstruct(PatternConstruct owner, MatcherConstruct next) {
		super(owner, next);
	}

	@Override
	final boolean collectExitAssertionChain(List<ZeroWidthAssertionGuard> chain) {
		chain.add(this);
		return MatcherConstruct.collectExitAssertionChain(next, chain);
	}
}

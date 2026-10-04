package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;

import java.util.List;

/**
 * A zero-width forwarding node, used instead of a plain alias when the owner has dispatch gating
 * of its own (see {@link MatcherConstruct#aliasOrPassThrough}).
 */
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

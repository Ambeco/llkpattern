package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;

import java.util.List;

/**
 * Reached once the whole pattern has matched. Whether that is a complete match depends on the
 * operation: {@code matches()} requires consuming the whole region, {@code lookingAt()}/{@code
 * find()} only a prefix; see {@link Matcher#requireFullMatch}, set before each attempt. This is the
 * one place that flag is read: every other node cares only whether the pattern's structure was
 * satisfied. Has no successor.
 */
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

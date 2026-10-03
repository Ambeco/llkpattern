package com.tbohne.llkpattern;

import org.checkerframework.checker.nullness.qual.Nullable;

final class EndOfFindGateMatcherConstruct extends MatcherConstruct {
	final CodePointSet explicit;
	final @Nullable MatcherConstruct fallback;

	EndOfFindGateMatcherConstruct(
			int flags, CodePointSet explicit, MatcherConstruct branch, @Nullable MatcherConstruct fallback) {
		super(flags, branch);
		this.explicit = explicit;
		this.fallback = fallback;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		if (peeked == -1 || !matcher.requireFullMatch || explicit.contains(peeked)) {
			return next.match(matcher, peeked);
		}
		return fallback != null && fallback.match(matcher, peeked);
	}
}

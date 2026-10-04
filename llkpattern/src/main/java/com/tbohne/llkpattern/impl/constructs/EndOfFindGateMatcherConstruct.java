package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.Matcher;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The gate for a union branch that can END the whole pattern without consuming anything (a
 * nullable tail, e.g. {@code a*} at the end; see {@code PatternConstruct#elseIsEndOfFind}).
 * Admits {@code peeked} if it is in {@code explicit} (the branch's real first characters) or if
 * end-of-find is satisfied: anywhere under {@code lookingAt()}/{@code find()}, only at end of input
 * under {@code matches()}, the same split as {@link EndMatcherConstruct}. Otherwise defers to
 * {@code fallback} (the next sibling), like an {@code entrySet} miss.
 *
 * <p>So {@code a*|b} on {@code "b"} is {@code ""} under {@code find()} and {@code "b"} under
 * {@code matches()}, like {@code java.util.regex}. Its own {@code entrySet} is null (this node does
 * the gating) and the branch behind it is compiled ungated.
 */
final class EndOfFindGateMatcherConstruct extends MatcherConstruct {
	final CodePointSet explicit;
	private final @Nullable MatcherConstruct fallback;

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

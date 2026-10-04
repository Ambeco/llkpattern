package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

/**
 * {@code \X} (extended grapheme cluster, UAX #29): consumes one full cluster via {@code
 * GraphemeCluster#nextBoundary}, a forward-only port of JDK 27's {@code Grapheme#nextBoundary}.
 * (A {@code \b{g}} BOUNDARY is different: it needs unbounded backward context, e.g. counting
 * regional indicators back to the last real boundary.)
 *
 * <p>Entry is universal like {@code .}, but {@code \X} claims EVERY code point explicitly, so it
 * can't coexist with any other branch or loop-exit candidate: {@code a|\X} and {@code \X*a} are
 * rejected as ambiguous, like {@code .+b} (README's "Intentional differences"). A cluster's width
 * isn't statically known, so "whatever \X wouldn't otherwise claim" can't be carved out the way
 * {@code .}'s residual claim is.
 */
public final class GraphemeClusterPatternConstruct extends PatternConstruct {
	public GraphemeClusterPatternConstruct(int startIndex, int endIndex) {
		super(startIndex, endIndex);
	}

	@Override
	boolean needsEntryPointBeforeMatcher() {
		// buildMatcher() below doesn't read entryMap/entryElse at all.
		return false;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		entryMap = universalCodePointSet();
	}

	@Override
	void buildMatcher() {
		new GraphemeClusterMatcherConstruct(this);
	}
}

package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

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

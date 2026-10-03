package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;

public final class GraphemeClusterConstruct extends PatternConstruct {
	public GraphemeClusterConstruct(int startIndex, int endIndex) {
		super(startIndex, endIndex);
	}

	@Override
	boolean needsEntryPointBeforeMatcher() {
		// buildMatcher() below doesn't read entryMap/entryElse at all.
		return false;
	}

	@Override
	protected void buildEntryMap(PatternConstruct next) {
		entryMap = universalCodePointSet();
	}

	@Override
	protected void buildMatcher() {
		new GraphemeClusterMatcherConstruct(this);
	}
}

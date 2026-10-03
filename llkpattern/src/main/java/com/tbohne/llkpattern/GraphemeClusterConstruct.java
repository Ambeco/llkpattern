package com.tbohne.llkpattern;

import com.tbohne.llkpattern.NamedCharClass.*;

final class GraphemeClusterConstruct extends PatternConstruct {
	GraphemeClusterConstruct(int startIndex, int endIndex) {
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

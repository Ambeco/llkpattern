package com.tbohne.llkpattern;

import com.tbohne.llkpattern.NamedCharClass.*;

final class LoopContinueMarker extends PatternConstruct {
	LoopContinueMarker(int startIndex) {
		super(startIndex);
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		throw new AssertionError("LoopContinueMarker's entry point is never queried");
	}

	@Override
	void buildMatcher() {
		throw new AssertionError("LoopContinueMarker's own matcher is assigned directly, not via buildMatcher()");
	}
}

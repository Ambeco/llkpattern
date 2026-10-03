package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;

final class LoopContinueMarker extends PatternConstruct {
	LoopContinueMarker(int startIndex) {
		super(startIndex);
	}

	@Override
	protected void buildEntryMap(PatternConstruct next) {
		throw new AssertionError("LoopContinueMarker's entry point is never queried");
	}

	@Override
	protected void buildMatcher() {
		throw new AssertionError("LoopContinueMarker's own matcher is assigned directly, not via buildMatcher()");
	}
}

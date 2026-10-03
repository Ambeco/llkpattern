package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;

final class LoopBodyPartGateMarker extends PatternConstruct {
	LoopBodyPartGateMarker(int startIndex) {
		super(startIndex);
	}

	@Override
	protected void buildEntryMap(PatternConstruct next) {
		throw new AssertionError("LoopBodyPartGateMarker's entry point is never queried");
	}

	@Override
	protected void buildMatcher() {
		throw new AssertionError("LoopBodyPartGateMarker's own matcher is built directly, not via buildMatcher()");
	}
}

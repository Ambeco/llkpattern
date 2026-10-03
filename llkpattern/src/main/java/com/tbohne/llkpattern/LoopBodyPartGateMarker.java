package com.tbohne.llkpattern;

import com.tbohne.llkpattern.NamedCharClass.*;

final class LoopBodyPartGateMarker extends PatternConstruct {
	LoopBodyPartGateMarker(int startIndex) {
		super(startIndex);
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		throw new AssertionError("LoopBodyPartGateMarker's entry point is never queried");
	}

	@Override
	void buildMatcher() {
		throw new AssertionError("LoopBodyPartGateMarker's own matcher is built directly, not via buildMatcher()");
	}
}

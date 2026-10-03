package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;

final class LoopBodyPartGatePatternConstruct extends PatternConstruct {
	LoopBodyPartGatePatternConstruct(int startIndex) {
		super(startIndex);
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		throw new AssertionError("LoopBodyPartGatePatternConstruct's entry point is never queried");
	}

	@Override
	void buildMatcher() {
		throw new AssertionError("LoopBodyPartGatePatternConstruct's own matcher is built directly, not via buildMatcher()");
	}
}

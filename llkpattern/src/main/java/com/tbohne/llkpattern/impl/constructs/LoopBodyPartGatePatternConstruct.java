package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

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

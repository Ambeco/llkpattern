package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

final class LoopContinuePatternConstruct extends PatternConstruct {
	LoopContinuePatternConstruct(int startIndex) {
		super(startIndex);
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		throw new AssertionError("LoopContinuePatternConstruct's entry point is never queried");
	}

	@Override
	void buildMatcher() {
		throw new AssertionError("LoopContinuePatternConstruct's own matcher is assigned directly, not via buildMatcher()");
	}
}

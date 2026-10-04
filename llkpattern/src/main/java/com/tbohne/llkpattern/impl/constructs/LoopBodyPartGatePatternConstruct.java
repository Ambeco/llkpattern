package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

/**
 * A zero-width vehicle for one capturing loop body part's entry gating. The gate lives here, one
 * level above the {@code BeginCaptureMatcherConstruct} it owns, rather than on the body part
 * itself, which is compiled ungated inside the capture. See {@code
 * QuantifiablePatternConstruct.buildLoopMatcher}.
 */
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

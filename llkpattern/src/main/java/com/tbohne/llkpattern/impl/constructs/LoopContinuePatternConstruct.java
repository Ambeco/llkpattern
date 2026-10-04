package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

/**
 * A second zero-width marker for {@code QuantifiablePatternConstruct.buildLoopMatcher}, distinct
 * from {@link LoopBackPatternConstruct} (whose {@code .matcher} the {@code LoopMatcherConstruct}
 * itself claims). Its {@code .matcher} receives that loop's "continue" successor (the body-part
 * chain, unresolvable until the body has compiled) by ordinary assignment.
 *
 * <p>This reuses {@code PatternConstruct.matcher}'s assign-once nature as the one place a forward
 * reference may resolve later, instead of a mutable field on {@code MatcherConstruct} (see {@code
 * LoopMatcherConstruct}). Nothing compiles it, so both build methods assert.
 */
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

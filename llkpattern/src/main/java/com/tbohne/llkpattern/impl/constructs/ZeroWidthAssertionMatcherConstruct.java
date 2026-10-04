package com.tbohne.llkpattern.impl.constructs;


import java.util.List;

/**
 * Shared base for the {@link ZeroWidthAssertionGuard} implementers whose {@code
 * collectExitAssertionChain} is identical: add {@code this} to the chain and keep recursing
 * through {@code next}. EXPERIMENTAL (2026-09-27): one shared {@code final} method instead of four
 * identical ones, to reduce that call site's megamorphism; see {@code
 * ZeroWidthAssertionPatternConstruct} for the rationale and caveats.
 */
abstract class ZeroWidthAssertionMatcherConstruct extends MatcherConstruct
		implements ZeroWidthAssertionGuard {
	ZeroWidthAssertionMatcherConstruct(PatternConstruct owner, MatcherConstruct next) {
		super(owner, next);
	}

	@Override
	final boolean collectExitAssertionChain(List<ZeroWidthAssertionGuard> chain) {
		chain.add(this);
		return MatcherConstruct.collectExitAssertionChain(next, chain);
	}
}

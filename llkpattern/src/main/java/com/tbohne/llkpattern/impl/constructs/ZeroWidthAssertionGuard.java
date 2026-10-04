package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;



/**
 * A zero-width, side-effect-free match-time predicate, implemented by the assertion matchers (word
 * boundary, line boundary, lookbehind, grapheme boundary) that also have a full {@code matchBody}
 * (which sets {@code hitEnd}/{@code requireEnd} and dispatches to {@code next}). {@code
 * MatcherConstruct#exitAssertionChain} evaluates these directly, in ADVANCE of committing to the
 * exit path they gate (see {@link ReluctantLoopMatcherConstruct}).
 *
 * <p>Never called from ordinary dispatch: each {@code matchBody} keeps its own independent,
 * differentially tested logic rather than being rewritten in terms of this, which keeps that hot
 * path untouched.
 */
interface ZeroWidthAssertionGuard {
	boolean holdsHere(Matcher matcher, int peeked);
}

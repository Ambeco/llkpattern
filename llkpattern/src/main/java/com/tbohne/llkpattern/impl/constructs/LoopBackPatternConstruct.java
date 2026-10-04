package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

/**
 * A zero-width marker standing in for the {@code LoopMatcherConstruct} as a loop body's compile
 * target, so that node can self-register onto it BEFORE the body compiles against it (see {@code
 * QuantifiablePatternConstruct.buildLoopMatcher}).
 *
 * <p>Its entry point IS queried: a nullable construct inside the body (e.g. {@code (a)?} in {@code
 * (a)?+}) looks past itself at "what comes after", which for a loop body is the loop itself, so
 * this delegates to {@code owner}'s cached entry point.
 */
final class LoopBackPatternConstruct extends PatternConstruct {
	final QuantifiablePatternConstruct owner;

	LoopBackPatternConstruct(int startIndex, QuantifiablePatternConstruct owner) {
		super(startIndex);
		this.owner = owner;
	}

	// Never wired by a parent: what follows a loop's back edge is the loop itself.
	@Override
	PatternConstruct next() {
		return owner;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		entryMap = owner.getEntryPointMap();
		if (owner.getEntryElse() != null) {
			entryElse = this;
		}
	}

	@Override
	void buildMatcher() {
		throw new AssertionError("LoopBackPatternConstruct's own matcher is built directly, not via buildMatcher()");
	}
}

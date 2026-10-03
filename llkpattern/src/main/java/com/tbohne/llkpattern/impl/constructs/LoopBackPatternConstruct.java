package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

final class LoopBackPatternConstruct extends PatternConstruct {
	final QuantifiablePatternConstruct owner;

	LoopBackPatternConstruct(int startIndex, QuantifiablePatternConstruct owner) {
		super(startIndex);
		this.owner = owner;
	}

	/** Never wired by a parent: what follows a loop's back edge is the loop itself. */
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

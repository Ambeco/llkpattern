package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;

final class LoopBackMarker extends PatternConstruct {
	final QuantifiableConstruct owner;

	LoopBackMarker(int startIndex, QuantifiableConstruct owner) {
		super(startIndex);
		this.owner = owner;
	}

	/** Never wired by a parent: what follows a loop's back edge is the loop itself. */
	@Override
	PatternConstruct next() {
		return owner;
	}

	@Override
	protected void buildEntryMap(PatternConstruct next) {
		entryMap = owner.getEntryPointMap();
		if (owner.getEntryElse() != null) {
			entryElse = this;
		}
	}

	@Override
	protected void buildMatcher() {
		throw new AssertionError("LoopBackMarker's own matcher is built directly, not via buildMatcher()");
	}
}

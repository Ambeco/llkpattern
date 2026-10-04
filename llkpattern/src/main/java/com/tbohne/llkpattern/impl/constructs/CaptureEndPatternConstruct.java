package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

/**
 * A zero-width marker used as a capturing group's branches' "next", so an {@code
 * EndCaptureMatcherConstruct} fires (recording the capture) as the group's content finishes,
 * before control reaches whatever follows. It has the same entry set as {@code realNext}, so it
 * doesn't change what the branches consider ambiguous.
 */
final class CaptureEndPatternConstruct extends PatternConstruct {
	final int captureConstructIndex;
	private final PatternConstruct realNext;

	CaptureEndPatternConstruct(int startIndex, int captureConstructIndex, PatternConstruct realNext) {
		super(startIndex);
		this.captureConstructIndex = captureConstructIndex;
		this.realNext = realNext;
	}

	// Never wired by a parent: it is constructed knowing its successor.
	@Override
	PatternConstruct next() {
		return realNext;
	}

	@Override
	boolean claimsEntryElse() {
		// realNext is fixed (never reassigned back at an ancestor), so it can't join the nullable-loop
		// cycle; bypassing the cycle guard is safe.
		return realNext.claimsEntryElse();
	}

	@Override
	boolean elseIsEndOfFind() {
		return realNext.elseIsEndOfFind();
	}

	@Override
	boolean elseIsResidual() {
		return realNext.elseIsResidual();
	}

	@Override
	boolean needsEntryPointBeforeMatcher() {
		// buildMatcher() below reads only realNext.matcher -- nothing buildEntryMap() sets.
		return false;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		// Aliased, not copied: entryMap is a plain set, so consumers only ask which code points are in it.
		entryMap = realNext.getEntryPointMap();
		if (realNext.getEntryElse() != null) {
			entryElse = this;
		}
	}

	@Override
	void buildMatcher() {
		new EndCaptureMatcherConstruct(this, captureConstructIndex, realNext.matcher());
	}
}

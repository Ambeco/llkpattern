package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class BoundaryPatternConstruct extends PatternConstruct {
	public enum BoundaryEnum {
		InputBegin,
		InputEndExceptTerminator,
		InputEnd
	}

	public final BoundaryEnum type;

	public BoundaryPatternConstruct(int startIndex, int endIndex, BoundaryEnum type) {
		super(startIndex, endIndex);
		this.type = type;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		buildZeroWidthEntryMap(this, next);
	}

	@Override
	boolean elseIsEndOfFind() {
		return next().elseIsEndOfFind();
	}

	@Override
	boolean elseIsResidual() {
		return next().elseIsResidual();
	}

	@Override
	boolean needsEntryPointBeforeMatcher() {
		return false;
	}

	@Override
	void buildMatcher() {
		new BoundaryMatcherConstruct(this, type);
	}

	@Override
	final CodePointSet skipZeroWidthEntrySet(boolean checkAssertions, @Nullable CodePointSet bodyLastCharSet) {
		return next().skipZeroWidthEntrySet(checkAssertions, bodyLastCharSet);
	}
}

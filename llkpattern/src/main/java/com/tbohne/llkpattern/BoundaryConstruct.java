package com.tbohne.llkpattern;

import com.tbohne.llkpattern.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

final class BoundaryConstruct extends PatternConstruct {
	enum BoundaryEnum {
		InputBegin,
		InputEndExceptTerminator,
		InputEnd
	}

	final BoundaryEnum type;

	BoundaryConstruct(int startIndex, int endIndex, BoundaryEnum type) {
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

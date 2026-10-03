package com.tbohne.llkpattern;

import com.tbohne.llkpattern.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

abstract class ZeroWidthAssertionConstruct extends PatternConstruct {
	ZeroWidthAssertionConstruct(int startIndex, int endIndex) {
		super(startIndex, endIndex);
	}

	@Override
	final void buildEntryMap(PatternConstruct next) {
		buildZeroWidthEntryMap(this, next);
	}

	@Override
	final boolean elseIsEndOfFind() {
		return next().elseIsEndOfFind();
	}

	@Override
	final boolean elseIsResidual() {
		return next().elseIsResidual();
	}

	@Override
	final boolean needsEntryPointBeforeMatcher() {
		// buildMatcher() never reads this construct's own entryMap/entryElse (see buildZeroWidthEntryMap).
		return false;
	}

	@Override
	final CodePointSet skipZeroWidthEntrySet(boolean checkAssertions, @Nullable CodePointSet bodyLastCharSet) {
		CodePointSet rest = next().skipZeroWidthEntrySet(checkAssertions, bodyLastCharSet);
		if (!checkAssertions) {
			return rest;
		}
		CodePointSet admitted = admittedInteriorExitPeekSet(bodyLastCharSet);
		return admitted == null ? rest : union(rest, admitted);
	}

	/**
	 * The set of peek code points for which a loop's interior exit through this assertion could
	 * be ambiguous with the loop body simply continuing on, given that the body's own
	 * last-consumed character is somewhere in {@code bodyLastCharSet} ({@code null} if that's
	 * not statically known, in which case this must also return {@code null} -- "not statically
	 * known" is always a safe fallback, just a missed optimization). See each override's own doc
	 * for its own construct-specific reasoning.
	 */
	abstract @Nullable CodePointSet admittedInteriorExitPeekSet(@Nullable CodePointSet bodyLastCharSet);
}

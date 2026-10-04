package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Shared base for the zero-width assertion types (word boundary, line boundary, lookbehind,
 * grapheme boundary) whose {@code skipZeroWidthEntrySet} is identical: see through to {@code
 * next}'s entry set, folding in {@link #admittedInteriorExitPeekSet}. {@code
 * BoundaryPatternConstruct} has no "admitted" concept, so it stays a direct {@code
 * PatternConstruct} subclass.
 *
 * <p>EXPERIMENTAL (2026-09-27): merges four identical overrides into one {@code final} method to
 * reduce that call site's megamorphism. Whether ART's inline caching benefits was to be measured;
 * see notes.md.
 */
abstract class ZeroWidthAssertionPatternConstruct extends PatternConstruct {
	ZeroWidthAssertionPatternConstruct(int startIndex, int endIndex) {
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

	// Peek code points for which a loop's interior exit through this assertion could be ambiguous
	// with the body continuing, given the body's last-consumed character is in bodyLastCharSet.
	// Null (unknown) in, null out: always a safe fallback, just a missed optimization.
	abstract @Nullable CodePointSet admittedInteriorExitPeekSet(@Nullable CodePointSet bodyLastCharSet);
}

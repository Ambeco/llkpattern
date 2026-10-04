package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * {@code \b{g}} (grapheme boundary). Only the positive form exists: JDK 27 doesn't treat {@code
 * \B{g}} as special syntax either (design.md). Always a real match-time check, like a lookbehind:
 * neither neighbor's grapheme-boundary-ness is statically known.
 */
public final class GraphemeBoundaryPatternConstruct extends ZeroWidthAssertionPatternConstruct {
	public GraphemeBoundaryPatternConstruct(int startIndex, int endIndex) {
		super(startIndex, endIndex);
	}

	@Override
	void buildMatcher() {
		new GraphemeBoundaryMatcherConstruct(this);
	}

	// Deliberately conservative: \b{g} can depend on a whole chain of prior code points
	// (GB9c/GB11/GB12-13, GraphemeCluster#isBoundary), which this check can't reason about, so
	// whenever the body could have consumed anything every peek is treated as ambiguous. This
	// over-rejects some fine loops (e.g. \X+\b{g}) but never under-rejects, the same tradeoff as \X
	// (README "Intentional differences").
	@Override
	final @Nullable CodePointSet admittedInteriorExitPeekSet(@Nullable CodePointSet bodyLastCharSet) {
		return bodyLastCharSet == null ? null : universalCodePointSet();
	}
}

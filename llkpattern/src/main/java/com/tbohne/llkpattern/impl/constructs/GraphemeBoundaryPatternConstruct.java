package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class GraphemeBoundaryPatternConstruct extends ZeroWidthAssertionPatternConstruct {
	public GraphemeBoundaryPatternConstruct(int startIndex, int endIndex) {
		super(startIndex, endIndex);
	}

	@Override
	void buildMatcher() {
		new GraphemeBoundaryMatcherConstruct(this);
	}

	/**
	 * Deliberately conservative rather than precise: unlike \b/\B (whose truth depends on a
	 * simple word/non-word classification of exactly one neighbor at a time) or a 1-code-point
	 * lookbehind, \b{g}'s truth can depend on a whole chain of prior code points (GB9c/GB11/
	 * GB12-13 -- see {@code GraphemeCluster#isBoundary}), which this loop-ambiguity check has no
	 * way to reason about precisely. So whenever the loop body could plausibly have just
	 * consumed ANY character at all ({@code bodyLastCharSet != null}), this treats \b{g} as
	 * potentially holding for every peek code point -- i.e. always ambiguous with continuing the
	 * loop. This over-rejects some loops that would actually be fine at match time (e.g. {@code
	 * \X+\b{g}}, since a loop of whole clusters can never stop mid-cluster) in exchange for never
	 * under-rejecting a genuinely ambiguous one -- the same tradeoff this project already accepts
	 * for {@code \X} itself (see README's "Intentional differences").
	 */
	@Override
	final @Nullable CodePointSet admittedInteriorExitPeekSet(@Nullable CodePointSet bodyLastCharSet) {
		return bodyLastCharSet == null ? null : universalCodePointSet();
	}
}

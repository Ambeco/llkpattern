package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.ArrayCodePointSet;
import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class LookbehindPatternConstruct extends ZeroWidthAssertionPatternConstruct {
	final String pattern;
	final boolean isPositive; // true: (?<=X), false: (?<!X)
	final CodePointSet lookSet;
	public final int captureConstructIndex; // -1 if the body wasn't wrapped in a capturing group

	public LookbehindPatternConstruct(
			String pattern, int startIndex, int endIndex, boolean isPositive,
			CodePointSet lookSet, int captureConstructIndex) {
		super(startIndex, endIndex);
		this.pattern = pattern;
		this.isPositive = isPositive;
		this.lookSet = lookSet;
		this.captureConstructIndex = captureConstructIndex;
	}

	@Override
	void buildMatcher() {
		// Always a real check -- unlike \b/\B, there's no "peek" side to statically classify
		// away: the previous character is never known at compile time, so this never collapses
		// to a no-op or a compile-time error the way WordBoundaryPatternConstruct sometimes does.
		new LookbehindMatcherConstruct(this, isPositive, lookSet, captureConstructIndex);
	}

	/** The result of {@link #resolveSingleCodePointBody}: the body's statically-known
	 *  code point set, plus which capturing group (if any) wraps the whole body. */
	public static final class SingleCodePointBody {
		public final CodePointSet codePoints;
		public final int captureConstructIndex; // -1 if none

		SingleCodePointBody(CodePointSet codePoints, int captureConstructIndex) {
			this.codePoints = codePoints;
			this.captureConstructIndex = captureConstructIndex;
		}
	}

	/**
	 * Loop-ambiguity helper only -- see {@code PatternConstruct#skipZeroWidthEntrySet}'s {@code
	 * checkAssertions} doc, and {@code WordBoundaryPatternConstruct#admittedInteriorExitPeekSet}'s own
	 * doc for why the coarse catch-all entry point ({@code entryElse = this}) isn't safe for a
	 * loop's own continue-vs-exit ambiguity check. Simpler than that method's version: a
	 * lookbehind's truth depends ONLY on the prior character, never on peek at all, so once {@code
	 * bodyLastCharSet} shows this assertion COULD hold right after a body iteration, exiting
	 * through it is ambiguous with continuing for literally every peek code point; otherwise it
	 * contributes nothing.
	 */
	@Override
	final @Nullable CodePointSet admittedInteriorExitPeekSet(@Nullable CodePointSet bodyLastCharSet) {
		if (bodyLastCharSet == null) {
			return null;
		}
		// first(), not entrySet(), so a violation short-circuits -- same technique as
		// WordBoundaryPatternConstruct's own isSubsetOf/isDisjointFrom helpers.
		boolean subsetOfLookSet = !bodyLastCharSet.first((min, max) -> !lookSet.containsAll(min, max));
		boolean couldHold = isPositive ? bodyLastCharSet.intersects(lookSet) : !subsetOfLookSet;
		return couldHold ? universalCodePointSet() : new ArrayCodePointSet();
	}
}

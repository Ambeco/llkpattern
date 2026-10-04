package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.ArrayCodePointSet;
import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * {@code (?<=X)}/{@code (?<!X)}, restricted to a body that always matches exactly one code point:
 * a generalization of {@code \b}/{@code \B}'s single-code-point {@code peekPrevious()} check
 * (design.md "Boundary matching"). Wider lookbehind and any lookahead are permanently out of scope
 * (they can't be evaluated in O(1) per position). {@code lookSet} and {@code captureConstructIndex}
 * are fully resolved at parse time by {@link #resolveSingleCodePointBody}, so no entry-map
 * classification step is needed.
 */
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
		// Always a real check: the previous character is never known at compile time.
		new LookbehindMatcherConstruct(this, isPositive, lookSet, captureConstructIndex);
	}

	/** The body's statically-known code point set, plus the capturing group (if any) wrapping it. */
	public static final class SingleCodePointBody {
		public final CodePointSet codePoints;
		public final int captureConstructIndex; // -1 if none

		SingleCodePointBody(CodePointSet codePoints, int captureConstructIndex) {
			this.codePoints = codePoints;
			this.captureConstructIndex = captureConstructIndex;
		}
	}

	// Loop-ambiguity helper (see PatternConstruct#skipZeroWidthEntrySet). A lookbehind depends ONLY
	// on the prior character: if it could hold right after a body iteration, exiting is ambiguous
	// with continuing for every peek code point; otherwise it contributes nothing.
	@Override
	final @Nullable CodePointSet admittedInteriorExitPeekSet(@Nullable CodePointSet bodyLastCharSet) {
		if (bodyLastCharSet == null) {
			return null;
		}
		// first() short-circuits on a violation, as in WordBoundaryPatternConstruct.isSubsetOf.
		boolean subsetOfLookSet = !bodyLastCharSet.first((min, max) -> !lookSet.containsAll(min, max));
		boolean couldHold = isPositive ? bodyLastCharSet.intersects(lookSet) : !subsetOfLookSet;
		return couldHold ? universalCodePointSet() : new ArrayCodePointSet();
	}
}

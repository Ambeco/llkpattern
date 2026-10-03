package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class WordBoundaryConstruct extends ZeroWidthAssertionConstruct {
	final String pattern;
	final boolean isWordBoundary; // true: \b, false: \B

	// The set of code points that could be the last one consumed by whatever immediately
	// precedes this boundary in its enclosing Sequence, if statically known -- set by
	// Sequence.buildEntryMap (via lastCharSet(), below) before compile() runs; null (the
	// default, e.g. when this boundary opens its Sequence, or isn't in one at all) means "not
	// statically known", which is always a safe fallback, just a missed optimization.
	@Nullable CodePointSet priorCharSet;

	public WordBoundaryConstruct(String pattern, int startIndex, int endIndex, boolean isWordBoundary) {
		super(startIndex, endIndex);
		this.pattern = pattern;
		this.isWordBoundary = isWordBoundary;
	}

	private enum Wordness {
		WORD,
		NON_WORD,
		UNKNOWN
	}

	/** True if every code point in {@code a} is also in {@code b}. */
	private static boolean isSubsetOf(CodePointSet a, CodePointSet b) {
		// first(), not entrySet(), so a violation short-circuits instead of scanning the rest of
		// `a` regardless -- see CodePointSet#first's own doc.
		return !a.first((min, max) -> !b.containsAll(min, max));
	}

	/** True if no code point in {@code a} is also in {@code b}. */
	private static boolean isDisjointFrom(CodePointSet a, CodePointSet b) {
		return !a.first((min, max) -> !b.intersection(min, max).isEmpty());
	}

	private static Wordness classify(@Nullable CodePointSet set, CodePointSet wordSet) {
		if (set == null) {
			return Wordness.UNKNOWN;
		}
		// Computed directly as subset/disjoint checks against wordSet, rather than via
		// wordSet.complement() the way the old RangeSet#enclosesAll version did -- no need to
		// materialize a complement just to test disjointness; it would still be correct here,
		// just wasted work for a query this cheap already.
		if (isSubsetOf(set, wordSet)) {
			return Wordness.WORD;
		}
		if (isDisjointFrom(set, wordSet)) {
			return Wordness.NON_WORD;
		}
		return Wordness.UNKNOWN;
	}

	/**
	 * Loop-ambiguity helper only -- see {@code PatternConstruct#skipZeroWidthEntrySet}'s
	 * {@code checkAssertions} doc. The set of peek code points for which a \b/\B sitting right
	 * after a loop body could hold, given that the body's own last-consumed character is
	 * somewhere in {@code bodyLastCharSet} -- i.e. the code points an interior exit through this
	 * assertion could be ambiguous with the loop simply continuing on. Returns {@code null}
	 * ("not statically known", same safe fallback as {@code lastCharSet}/{@code classify}) only
	 * when {@code bodyLastCharSet} itself is {@code null}; a non-null but WORD-ness-mixed
	 * {@code bodyLastCharSet} still resolves, to {@link PatternConstruct#universalCodePointSet}
	 * (since some prior character in it always matches whatever word-ness the peek character
	 * has, \b/\B can then hold for ANY peek).
	 */
	@Override
	final @Nullable CodePointSet admittedInteriorExitPeekSet(@Nullable CodePointSet bodyLastCharSet) {
		if (bodyLastCharSet == null) {
			return null;
		}
		CodePointSet wordSet = RegexCharacterClass.w.get(flags);
		Wordness prior = classify(bodyLastCharSet, wordSet);
		if (prior == Wordness.UNKNOWN) {
			return universalCodePointSet();
		}
		// Same "wantsWordPeek" formula buildMatcher() uses for its own statically-known-prior case.
		boolean priorIsWord = prior == Wordness.WORD;
		boolean wantsWordPeek = isWordBoundary != priorIsWord;
		return wantsWordPeek ? wordSet : wordSet.complement();
	}

	@Override
	protected void buildMatcher() {
		// See design.md's "Boundary matching" section and the class doc for
		// WordBoundaryMatcherConstruct for the full optimization rationale. In brief: both sides
		// of the boundary (the character just consumed, and the one about to be) are classified
		// as always-word/always-non-word/unknown at compile time; whichever side is statically
		// known doesn't need to be checked at match time at all.
		CodePointSet wordSet = RegexCharacterClass.w.get(flags);
		Wordness prior = classify(priorCharSet, wordSet);
		// next's own entry-point map is already exactly a plain CodePointSet -- no separate
		// RangeSet needs building here any more.
		CodePointSet peekRanges = next().getEntryElse() == null ? next().getEntryPointMap() : null;
		Wordness peek = classify(peekRanges, wordSet);

		if (prior != Wordness.UNKNOWN && peek != Wordness.UNKNOWN) {
			boolean isBoundaryHere = (prior != peek);
			if (isBoundaryHere != isWordBoundary) {
				throw PatternSyntaxException.throwWithReferences(
						pattern,
						startIndex,
						(isWordBoundary ? "\\b" : "\\B"),
						" at index ", startIndex,
						" can never match: the preceding and following characters are ",
						(isBoundaryHere ? "always different word-ness" : "always the same word-ness"),
						" here, which is the opposite of what ",
						(isWordBoundary ? "\\b" : "\\B"),
						" requires");
			}
			// Statically always satisfied, so nothing to check at match time -- except under
			// transparent bounds at regionEnd (see ElidedWordBoundaryMatcherConstruct's own doc).
			new ElidedWordBoundaryMatcherConstruct(this, wordSet, isWordBoundary);
			return;
		}

		WordBoundaryMatcherConstruct.PriorWordBoundaryMatchType priorMatchType;
		WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType peekMatchType;
		if (peek == Wordness.UNKNOWN && prior == Wordness.UNKNOWN) {
			// Neither side is statically known: fall back to comparing both at match time.
			priorMatchType = WordBoundaryMatcherConstruct.PriorWordBoundaryMatchType.Unchecked;
			peekMatchType = isWordBoundary
					? WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.PeekMustBeOppositePrior
					: WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.PeekMustBeSameAsPrior;
		} else if (peek == Wordness.UNKNOWN) {
			// prior is statically known -- fold it into a fixed direction for the (already
			// available, no extra call needed) peeked character; never need matcher.peekPrevious().
			boolean priorIsWord = (prior == Wordness.WORD);
			boolean wantsWordPeek = isWordBoundary != priorIsWord;
			priorMatchType = WordBoundaryMatcherConstruct.PriorWordBoundaryMatchType.Unchecked;
			peekMatchType = wantsWordPeek
					? WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.PeekMustBeWord
					: WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.PeekMustNotBeWord;
		} else {
			// peek is statically known -- fold it into a fixed direction for matcher.peekPrevious(),
			// which is the only case that still needs the extra backward-looking call.
			boolean peekIsWord = (peek == Wordness.WORD);
			boolean wantsWordPrior = isWordBoundary != peekIsWord;
			priorMatchType = wantsWordPrior
					? WordBoundaryMatcherConstruct.PriorWordBoundaryMatchType.PriorMustBeWord
					: WordBoundaryMatcherConstruct.PriorWordBoundaryMatchType.PriorMustBeNonWord;
			peekMatchType = WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.Unchecked;
		}
		new WordBoundaryMatcherConstruct(this, wordSet, priorMatchType, peekMatchType, isWordBoundary);
	}
}

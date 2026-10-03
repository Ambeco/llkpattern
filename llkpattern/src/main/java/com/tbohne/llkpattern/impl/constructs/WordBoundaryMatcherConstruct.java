package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.Matcher;



final class WordBoundaryMatcherConstruct extends ZeroWidthAssertionMatcherConstruct {
	/** Whether {@code matchBody()} needs to independently check {@code matcher.peekPrevious()}. */
	enum PriorWordBoundaryMatchType {
		Unchecked,
		PriorMustBeWord,
		PriorMustBeNonWord
	}

	/**
	 * Whether/how {@code matchBody()} needs to check {@code peeked} -- either against a fixed
	 * word-ness (when the OTHER side, the preceding character, is statically known instead), or
	 * against {@code matcher.peekPrevious()}'s actual word-ness (when neither side is statically
	 * known).
	 */
	enum PeekWordBoundaryMatchType {
		Unchecked,
		PeekMustBeWord,
		PeekMustNotBeWord,
		PeekMustBeSameAsPrior,
		PeekMustBeOppositePrior
	}

	final CodePointSet wordSet;
	private final PriorWordBoundaryMatchType priorMustBeWord;
	private final PeekWordBoundaryMatchType peekMustBeWord;
	final boolean isWordBoundary; // true: \b, false: \B

	WordBoundaryMatcherConstruct(
			PatternConstruct owner,
			CodePointSet wordSet,
			PriorWordBoundaryMatchType priorMustBeWord,
			PeekWordBoundaryMatchType peekMustBeWord,
			boolean isWordBoundary) {
		super(owner, owner.next().matcher());
		if (priorMustBeWord == PriorWordBoundaryMatchType.Unchecked
				&& peekMustBeWord == PeekWordBoundaryMatchType.Unchecked) {
			// WordBoundaryPatternConstruct.buildMatcher() never builds one of these with both sides
			// Unchecked -- that's the fully-statically-known case, resolved at compile time into
			// a compile error or a no-op pass-through instead of a WordBoundaryMatcherConstruct.
			throw new IllegalStateException(
					"WordBoundaryMatcherConstruct built with neither side checked");
		}
		this.wordSet = wordSet;
		this.priorMustBeWord = priorMustBeWord;
		this.peekMustBeWord = peekMustBeWord;
		this.isWordBoundary = isWordBoundary;
	}

	// Static, with `wordSet` passed as a parameter, rather than an instance method reading
	// `this.wordSet` -- part of the same experiment as ArrayCodePointSet#floorIndex (see its own
	// doc); no measurable difference found here either (see notes.md's dated entry).
	static boolean isWordChar(CodePointSet wordSet, int codePoint) {
		return codePoint >= 0 && wordSet.contains(codePoint);
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		// peekPrevious() is only actually called when some check below needs it -- checkPrior
		// is exactly that: either the prior side has a fixed target of its own, or the peek
		// side needs to compare against it. checkPeek is the mirror image, for symmetry/clarity
		// (peeked itself is already available for free, but isWordChar(peeked) is not free).
		int ahead = matcher.peekForBoundary();
		if (ahead == -1) {
			// java.util.regex's Bound looks at the character after the position even when this
			// engine's compile-time classification only needs the one before it.
			matcher.hitEnd = true;
			matcher.requireEnd = true;
		}
		if (peeked == -1 && ahead != -1) {
			// Transparent bounds, at regionEnd: the compile-time classification below assumes the
			// character next consumed is the one at pos, but nothing can consume past the region, so
			// test the real boundary here; whatever follows then fails (and flags hitEnd) on its own.
			boolean boundary = isWordChar(wordSet, matcher.peekPrevious()) != isWordChar(wordSet, ahead);
			return boundary == isWordBoundary && next.match(matcher, peeked);
		}
		boolean checkPrior = priorMustBeWord !=PriorWordBoundaryMatchType.Unchecked
				|| peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeSameAsPrior
				|| peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeOppositePrior;
		boolean priorIsWord = checkPrior && isWordChar(wordSet, matcher.peekPrevious());
		boolean checkPeek = peekMustBeWord != PeekWordBoundaryMatchType.Unchecked;
		boolean peekIsWord = checkPeek && isWordChar(wordSet, ahead);

		if (priorMustBeWord == PriorWordBoundaryMatchType.PriorMustBeWord && !priorIsWord) {
			return false;
		}
		if (priorMustBeWord == PriorWordBoundaryMatchType.PriorMustBeNonWord && priorIsWord) {
			return false;
		}
		if (peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeWord && !peekIsWord) {
			return false;
		}
		if (peekMustBeWord == PeekWordBoundaryMatchType.PeekMustNotBeWord && peekIsWord) {
			return false;
		}
		if (peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeSameAsPrior && peekIsWord != priorIsWord) {
			return false;
		}
		if (peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeOppositePrior && peekIsWord == priorIsWord) {
			return false;
		}
		return next.match(matcher, peeked);
	}

	/**
	 * As {@link #matchBody}, but only the "does \b/\B hold here" question -- no {@code hitEnd}/
	 * {@code requireEnd} side effects, no dispatch to {@code next}. See {@link
	 * ZeroWidthAssertionGuard}'s own doc for why this duplicates rather than shares matchBody's
	 * logic.
	 */
	@Override
	public boolean holdsHere(Matcher matcher, int peeked) {
		int ahead = matcher.peekForBoundary();
		if (peeked == -1 && ahead != -1) {
			boolean boundary = isWordChar(wordSet, matcher.peekPrevious()) != isWordChar(wordSet, ahead);
			return boundary == isWordBoundary;
		}
		boolean checkPrior = priorMustBeWord != PriorWordBoundaryMatchType.Unchecked
				|| peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeSameAsPrior
				|| peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeOppositePrior;
		boolean priorIsWord = checkPrior && isWordChar(wordSet, matcher.peekPrevious());
		boolean checkPeek = peekMustBeWord != PeekWordBoundaryMatchType.Unchecked;
		boolean peekIsWord = checkPeek && isWordChar(wordSet, ahead);

		if (priorMustBeWord == PriorWordBoundaryMatchType.PriorMustBeWord && !priorIsWord) {
			return false;
		}
		if (priorMustBeWord == PriorWordBoundaryMatchType.PriorMustBeNonWord && priorIsWord) {
			return false;
		}
		if (peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeWord && !peekIsWord) {
			return false;
		}
		if (peekMustBeWord == PeekWordBoundaryMatchType.PeekMustNotBeWord && peekIsWord) {
			return false;
		}
		if (peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeSameAsPrior && peekIsWord != priorIsWord) {
			return false;
		}
		return peekMustBeWord != PeekWordBoundaryMatchType.PeekMustBeOppositePrior || peekIsWord != priorIsWord;
	}
}

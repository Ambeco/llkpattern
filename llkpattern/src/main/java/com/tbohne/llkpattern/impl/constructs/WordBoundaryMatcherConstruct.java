package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.Matcher;



/**
 * {@code \b}/{@code \B}. Unlike other constructs, this depends on the character just BEFORE the
 * current position as well as the one at/after it (design.md "Boundary matching"). The general
 * case compares the word-ness of {@code peekPrevious()} and {@code peeked}, but \b/\B often sits
 * next to a statically always-word or always-non-word literal or class, so only ONE side needs
 * checking at match time.
 *
 * <p>{@code WordBoundaryPatternConstruct.buildMatcher()} does that classification, folding the
 * fully known case into a compile error or a no-op (never constructing one of these); this class
 * interprets whichever of the two enums below isn't {@code Unchecked}.
 */
final class WordBoundaryMatcherConstruct extends ZeroWidthAssertionMatcherConstruct {
	/** Whether {@code matchBody()} needs to independently check {@code matcher.peekPrevious()}. */
	enum PriorWordBoundaryMatchType {
		Unchecked,
		PriorMustBeWord,
		PriorMustBeNonWord
	}

	/** How {@code matchBody()} checks {@code peeked}: against a fixed word-ness (the preceding char is
	 *  statically known) or against {@code peekPrevious()}'s (neither is). */
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
			// buildMatcher() never builds one with both sides Unchecked (the fully known case is a
			// compile error or no-op).
			throw new IllegalStateException(
					"WordBoundaryMatcherConstruct built with neither side checked");
		}
		this.wordSet = wordSet;
		this.priorMustBeWord = priorMustBeWord;
		this.peekMustBeWord = peekMustBeWord;
		this.isWordBoundary = isWordBoundary;
	}

	// Static with wordSet as a parameter (the ArrayCodePointSet#floorIndex experiment): no
	// measurable difference, see notes.md.
	static boolean isWordChar(CodePointSet wordSet, int codePoint) {
		return codePoint >= 0 && wordSet.contains(codePoint);
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		// peekPrevious() is only called when a check below needs it.
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

	// Side-effect-free "does it hold here" predicate; see ZeroWidthAssertionGuard.
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

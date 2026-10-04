package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;
import com.tbohne.llkpattern.PatternSyntaxException;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * {@code \b} (word boundary) / {@code \B}. Separate from {@link BoundaryPatternConstruct} because
 * it is a real implementation with a compile-time optimization the other boundary types don't
 * need. See design.md's "Boundary matching".
 */
public final class WordBoundaryPatternConstruct extends ZeroWidthAssertionPatternConstruct {
	final String pattern;
	final boolean isWordBoundary; // true: \b, false: \B

	// Code points that could be the last one consumed just before this boundary, if statically
	// known; set by SequencePatternConstruct.buildEntryMap before compile(). Null means unknown
	// (always safe, just a missed optimization).
	@Nullable CodePointSet priorCharSet;

	public WordBoundaryPatternConstruct(String pattern, int startIndex, int endIndex, boolean isWordBoundary) {
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
		// first(), not entrySet(), so a violation short-circuits.
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
		if (isSubsetOf(set, wordSet)) {
			return Wordness.WORD;
		}
		if (isDisjointFrom(set, wordSet)) {
			return Wordness.NON_WORD;
		}
		return Wordness.UNKNOWN;
	}

	// Loop-ambiguity helper (see PatternConstruct#skipZeroWidthEntrySet): peek code points for
	// which a \b/\B right after a loop body could hold, given the body's last char is in
	// bodyLastCharSet. Null only if bodyLastCharSet is null; a word-ness-mixed set resolves to the
	// universal set, since some prior char always matches whatever word-ness peek has.
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
	void buildMatcher() {
		// Both sides are classified always-word/always-non-word/unknown at compile time, and a
		// statically known side needs no match-time check (design.md "Boundary matching").
		CodePointSet wordSet = RegexCharacterClass.w.get(flags);
		Wordness prior = classify(priorCharSet, wordSet);
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
			// prior is known: fold it into a fixed direction for the peeked char, so no peekPrevious().
			boolean priorIsWord = (prior == Wordness.WORD);
			boolean wantsWordPeek = isWordBoundary != priorIsWord;
			priorMatchType = WordBoundaryMatcherConstruct.PriorWordBoundaryMatchType.Unchecked;
			peekMatchType = wantsWordPeek
					? WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.PeekMustBeWord
					: WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.PeekMustNotBeWord;
		} else {
			// peek is known: fold it into a fixed direction for peekPrevious(), the only case still needing it.
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

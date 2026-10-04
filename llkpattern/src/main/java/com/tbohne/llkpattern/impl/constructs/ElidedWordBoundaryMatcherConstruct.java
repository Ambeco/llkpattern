package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.Matcher;



/**
 * A {@code \b}/{@code \B} whose neighbours were both statically known and always satisfy it
 * ({@code WordBoundaryPatternConstruct.buildMatcher()}), so nothing is checked at match time --
 * except under transparent bounds at {@code regionEnd}, where the known following character can't
 * actually be consumed and the real next character decides whether {@code java.util.regex} flags
 * {@code hitEnd}.
 */
final class ElidedWordBoundaryMatcherConstruct extends ZeroWidthAssertionMatcherConstruct {
	final CodePointSet wordSet;
	final boolean isWordBoundary; // true: \b, false: \B

	ElidedWordBoundaryMatcherConstruct(PatternConstruct owner, CodePointSet wordSet, boolean isWordBoundary) {
		super(owner, owner.next().matcher());
		this.wordSet = wordSet;
		this.isWordBoundary = isWordBoundary;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		return holdsHere(matcher, peeked) && next.match(matcher, peeked);
	}

	@Override
	public boolean holdsHere(Matcher matcher, int peeked) {
		if (peeked == -1) {
			int ahead = matcher.peekForBoundary();
			if (ahead != -1) {
				boolean boundary = WordBoundaryMatcherConstruct.isWordChar(wordSet, matcher.peekPrevious())
						!= WordBoundaryMatcherConstruct.isWordChar(wordSet, ahead);
				return boundary == isWordBoundary;
			}
		}
		return true;
	}
}

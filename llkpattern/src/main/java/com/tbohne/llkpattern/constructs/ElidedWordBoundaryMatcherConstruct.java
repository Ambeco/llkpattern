package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;



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

package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.constructs.ComplexCharacter;

public final class SingleCharMatcherConstruct extends MatcherConstruct {
	final CodePointSet validRanges;

	SingleCharMatcherConstruct(ComplexCharacter owner) {
		super(owner, owner.next().matcher());
		this.validRanges = owner.validRanges();
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		// -1 (Matcher's "no more input" sentinel -- see Matcher#peek) is never a real member, even
		// of a negated class whose fill would otherwise report it "in".
		if (peeked == -1 || !validRanges.contains(peeked)) {
			if (peeked == -1) {
				matcher.hitEnd = true;
			}
			return false;
		}
		return next.match(matcher, matcher.consume1CodePoint());
	}
}

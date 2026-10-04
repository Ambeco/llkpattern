package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.Matcher;

import com.tbohne.llkpattern.impl.constructs.ComplexCharacterPatternConstruct;

/**
 * Matches one code point against {@code validRanges} (a character class: {@code .}, a literal
 * single character, or {@code [...]}), then advances. A pure membership test: every member leads
 * to the same successor.
 */
public final class SingleCharMatcherConstruct extends MatcherConstruct {
	final CodePointSet validRanges;

	SingleCharMatcherConstruct(ComplexCharacterPatternConstruct owner) {
		super(owner, owner.next().matcher());
		this.validRanges = owner.validRanges();
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		// -1 (the "no more input" sentinel) is never a member, even of a negated class.
		if (peeked == -1 || !validRanges.contains(peeked)) {
			if (peeked == -1) {
				matcher.hitEnd = true;
			}
			return false;
		}
		return next.match(matcher, matcher.consume1CodePoint());
	}
}

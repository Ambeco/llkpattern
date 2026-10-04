package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.Matcher;



/**
 * {@code (?<=X)}/{@code (?<!X)} with a body that always matches exactly one code point (see {@code
 * LookbehindPatternConstruct}, design.md "Boundary matching"). Never sets {@code
 * hitEnd}/{@code requireEnd}: it only looks backward, so more input ahead can't change its result.
 *
 * <p>When the body is wrapped in a capturing group ({@code captureConstructIndex >= 0}), this node
 * writes {@code captureGroups[]} itself for the one code point behind {@code pos}, skipping
 * Begin/EndCapture. That is safe because the body is provably one code point wide, so no partial
 * (begin-without-end) write is possible.
 */
final class LookbehindMatcherConstruct extends ZeroWidthAssertionMatcherConstruct {
	final boolean isPositive; // true: (?<=X), false: (?<!X)
	final CodePointSet lookSet;
	final int captureConstructIndex; // -1 if the body wasn't wrapped in a capturing group

	LookbehindMatcherConstruct(
			PatternConstruct owner, boolean isPositive, CodePointSet lookSet, int captureConstructIndex) {
		super(owner, owner.next().matcher());
		this.isPositive = isPositive;
		this.lookSet = lookSet;
		this.captureConstructIndex = captureConstructIndex;
	}

	private boolean holds(int prior) {
		return (prior != -1 && lookSet.contains(prior)) == isPositive;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		int prior = matcher.peekPrevious();
		if (!holds(prior)) {
			return false;
		}
		if (captureConstructIndex >= 0) {
			int base = captureConstructIndex * 2;
			matcher.captureGroups[base] = matcher.pos - Character.charCount(prior);
			matcher.captureGroups[base + 1] = matcher.pos;
		}
		return next.match(matcher, peeked);
	}

	// Side-effect-free "does it hold here" predicate; see ZeroWidthAssertionGuard.
	@Override
	public boolean holdsHere(Matcher matcher, int peeked) {
		return holds(matcher.peekPrevious());
	}
}

package com.tbohne.llkpattern;



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

	/**
	 * As {@link #matchBody}, but only the "does this lookbehind hold here" question -- no
	 * capture-group write, no dispatch to {@code next}. See {@link ZeroWidthAssertionGuard}'s own
	 * doc for why this duplicates rather than shares matchBody's logic.
	 */
	@Override
	public boolean holdsHere(Matcher matcher, int peeked) {
		return holds(matcher.peekPrevious());
	}
}

package com.tbohne.llkpattern;



final class GraphemeBoundaryMatcherConstruct extends ZeroWidthAssertionMatcherConstruct {
	GraphemeBoundaryMatcherConstruct(PatternConstruct owner) {
		super(owner, owner.next().matcher());
	}

	private static boolean holds(Matcher matcher) {
		if (matcher.pos <= matcher.lookFloor) {
			return true;
		}
		if (matcher.pos < matcher.lookCeil) {
			return GraphemeCluster.isBoundary(matcher.input, matcher.pos, matcher.lookFloor);
		}
		return true;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		if (matcher.pos >= matcher.lookCeil) {
			matcher.hitEnd = true;
			matcher.requireEnd = true;
		}
		return holds(matcher) && next.match(matcher, peeked);
	}

	/**
	 * As {@link #matchBody}, but only the "does \b{g} hold here" question -- no {@code hitEnd}/
	 * {@code requireEnd} side effects, no dispatch to {@code next}. See {@link
	 * ZeroWidthAssertionGuard}'s own doc for why this duplicates rather than shares matchBody's
	 * logic.
	 */
	@Override
	public boolean holdsHere(Matcher matcher, int peeked) {
		return holds(matcher);
	}
}

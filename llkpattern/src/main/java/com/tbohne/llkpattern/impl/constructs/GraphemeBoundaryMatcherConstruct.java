package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.GraphemeCluster;
import com.tbohne.llkpattern.Matcher;



/**
 * {@code \b{g}} (see {@code GraphemeBoundaryPatternConstruct}, design.md "Extended grapheme
 * clusters"). No statically-known-neighbor optimization: the general check always runs. Three
 * positions skip {@code GraphemeCluster#isBoundary}, mirroring JDK 27's {@code
 * Pattern.GraphemeBound}: the region start is always a boundary; past the region end is too, but
 * also sets {@code hitEnd}/{@code requireEnd} (a longer suffix could always change the answer,
 * unlike a one-code-point lookbehind); strictly between them the real check runs.
 */
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

	// Side-effect-free "does it hold here" predicate; see ZeroWidthAssertionGuard.
	@Override
	public boolean holdsHere(Matcher matcher, int peeked) {
		return holds(matcher);
	}
}

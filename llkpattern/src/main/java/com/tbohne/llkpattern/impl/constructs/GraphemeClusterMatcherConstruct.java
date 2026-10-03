package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.GraphemeCluster;
import com.tbohne.llkpattern.Matcher;



final class GraphemeClusterMatcherConstruct extends MatcherConstruct {
	GraphemeClusterMatcherConstruct(GraphemeClusterPatternConstruct owner) {
		super(owner, owner.next().matcher());
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		if (peeked == -1) {
			matcher.hitEnd = true;
			return false;
		}
		int boundary = GraphemeCluster.nextBoundary(matcher.input, matcher.pos, matcher.regionEnd);
		return next.match(matcher, matcher.consumeCodeUnits(boundary - matcher.pos));
	}
}

package com.tbohne.llkpattern;



final class GraphemeClusterMatcherConstruct extends MatcherConstruct {
	GraphemeClusterMatcherConstruct(GraphemeClusterConstruct owner) {
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

package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.GraphemeCluster;
import com.tbohne.llkpattern.Matcher;



/**
 * Matches {@code \X}: one whole extended grapheme cluster via {@code
 * GraphemeCluster#nextBoundary}, then advances. A cluster is never empty, so the membership test
 * is really "is there any input left". As in JDK 27's {@code Pattern.XGrapheme#match}, {@code
 * hitEnd} is set only when no input is left to start a cluster, not when the cluster merely
 * reaches {@code regionEnd} (a faithful port, not a considered choice).
 */
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

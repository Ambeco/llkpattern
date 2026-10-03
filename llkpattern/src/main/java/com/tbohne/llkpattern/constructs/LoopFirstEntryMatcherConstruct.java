package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;



final class LoopFirstEntryMatcherConstruct extends MatcherConstruct {
	LoopFirstEntryMatcherConstruct(int flags, MatcherConstruct bodyHead) {
		super(flags, bodyHead);
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		return next.matchBody(matcher, peeked);
	}
}

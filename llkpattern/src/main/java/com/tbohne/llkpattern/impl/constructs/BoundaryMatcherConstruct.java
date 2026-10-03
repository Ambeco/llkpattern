package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;

import com.tbohne.llkpattern.impl.constructs.BoundaryPatternConstruct.BoundaryEnum;

final class BoundaryMatcherConstruct extends MatcherConstruct {
	final BoundaryEnum type;

	BoundaryMatcherConstruct(PatternConstruct owner, BoundaryEnum type) {
		super(owner, owner.next().matcher());
		this.type = type;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		boolean matchesHere;
		switch (type) {
			case InputBegin: // \A: always the true start of input, MULTILINE has no effect.
				matchesHere = matcher.pos == matcher.anchorStart;
				break;
			case InputEnd: // \z: always the true end of input, MULTILINE has no effect.
				matchesHere = matcher.pos == matcher.anchorEnd;
				break;
			case InputEndExceptTerminator: // \Z
				matchesHere = matchesEndExceptTerminator(matcher, flags);
				break;
			default:
				// Every BoundaryEnum value is handled above -- this is only reachable if a new one
				// is ever added without updating this switch.
				throw new AssertionError("Unhandled BoundaryEnum: " + type);
		}
		if (matchesHere && type != BoundaryEnum.InputBegin) {
			// \z only hits the end; \Z (like $) also could be broken by more input.
			matcher.hitEnd = true;
			matcher.requireEnd |= type == BoundaryEnum.InputEndExceptTerminator;
		}
		return matchesHere && next.match(matcher, peeked);
	}
}

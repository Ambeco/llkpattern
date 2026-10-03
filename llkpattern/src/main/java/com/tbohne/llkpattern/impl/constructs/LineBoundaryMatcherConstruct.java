package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Ll1Pattern;
import com.tbohne.llkpattern.Matcher;



final class LineBoundaryMatcherConstruct extends ZeroWidthAssertionMatcherConstruct {
	final boolean isLineBegin; // true: ^, false: $

	LineBoundaryMatcherConstruct(PatternConstruct owner, boolean isLineBegin) {
		super(owner, owner.next().matcher());
		this.isLineBegin = isLineBegin;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		boolean matchesHere;
		boolean atEnd = false;
		if (isLineBegin) {
			if ((flags & Ll1Pattern.MULTILINE) != 0 && matcher.pos == matcher.anchorEnd) {
				// java.util.regex never matches a MULTILINE ^ at the end of input (even after a
				// terminator, or in empty input), and counts the attempt as hitting the end.
				matcher.hitEnd = true;
				return false;
			}
			matchesHere = matcher.pos == matcher.anchorStart
					|| ((flags & Ll1Pattern.MULTILINE) != 0
							&& lineTerminatorLengthBefore(matcher.input, matcher.pos, matcher.anchorStart, matcher.anchorEnd, flags) > 0);
		} else {
			// Without MULTILINE every $ match is at the end or before the final terminator, and
			// java.util.regex flags both; with it only an actual end-of-input match is flagged.
			if ((flags & Ll1Pattern.MULTILINE) == 0) {
				matchesHere = matchesEndExceptTerminator(matcher, flags);
				atEnd = matchesHere;
			} else {
				atEnd = matcher.pos == matcher.anchorEnd;
				matchesHere = atEnd || lineTerminatorLengthAt(matcher, flags) > 0;
			}
		}
		if (atEnd) {
			matcher.hitEnd = true;
			matcher.requireEnd = true;
		}
		return matchesHere && next.match(matcher, peeked);
	}

	/**
	 * As {@link #matchBody}, but only the "does ^/$ hold here" question -- no {@code hitEnd}/
	 * {@code requireEnd} side effects, no dispatch to {@code next}. See {@link
	 * ZeroWidthAssertionGuard}'s own doc for why this duplicates rather than shares matchBody's
	 * logic.
	 */
	@Override
	public boolean holdsHere(Matcher matcher, int peeked) {
		if (isLineBegin) {
			if ((flags & Ll1Pattern.MULTILINE) != 0 && matcher.pos == matcher.anchorEnd) {
				return false;
			}
			return matcher.pos == matcher.anchorStart
					|| ((flags & Ll1Pattern.MULTILINE) != 0
							&& lineTerminatorLengthBefore(matcher.input, matcher.pos, matcher.anchorStart, matcher.anchorEnd, flags) > 0);
		}
		if ((flags & Ll1Pattern.MULTILINE) == 0) {
			return matchesEndExceptTerminator(matcher, flags);
		}
		return matcher.pos == matcher.anchorEnd || lineTerminatorLengthAt(matcher, flags) > 0;
	}
}

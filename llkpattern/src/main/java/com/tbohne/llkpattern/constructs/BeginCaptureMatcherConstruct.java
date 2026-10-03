package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;



final class BeginCaptureMatcherConstruct extends MatcherConstruct {
	final int captureConstructIndex;

	BeginCaptureMatcherConstruct(PatternConstruct owner, int captureConstructIndex, MatcherConstruct next) {
		super(owner, next);
		this.captureConstructIndex = captureConstructIndex;
	}

	/**
	 * Internal (non-self-registering) variant used by a capturing loop's shared "begin the next
	 * iteration" node -- see {@code QuantifiablePatternConstruct.buildLoopMatcher}.
	 */
	BeginCaptureMatcherConstruct(int captureConstructIndex, int flags, MatcherConstruct next) {
		super(flags, next);
		this.captureConstructIndex = captureConstructIndex;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		int base = captureConstructIndex * 2;
		matcher.captureGroups[base] = matcher.pos;
		// Reset the end slot too: re-entering a capture inside a loop must fully overwrite the
		// previous iteration's entry, not just its start, or a stale end from that earlier
		// iteration would linger if (impossibly, given this engine's forward-only structure) this
		// iteration's own EndCaptureMatcherConstruct somehow didn't run.
		matcher.captureGroups[base + 1] = -1;
		return next.match(matcher, peeked);
	}
}

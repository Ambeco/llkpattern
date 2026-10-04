package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;



/**
 * EXPERIMENT (2026-09-24): a loop's entry point used INSTEAD OF the body chain's head, for the
 * narrow case where the head's own {@code entrySet} check is provably redundant on first entry: a
 * single-alternative, non-capturing, {@code min >= 1} loop (see {@code
 * QuantifiablePatternConstruct.buildLoopMatcher}, and design.md's "LoopFirstEntryMatcherConstruct"
 * for why those conditions can't currently be relaxed).
 *
 * <p>Calls {@code bodyHead.matchBody(...)}, not {@code match(...)}, so the head's gate is skipped
 * on this path only; this node has no gate of its own. Whatever got THIS node called (an outer
 * chain candidate's entrySet via {@link MatcherConstruct#aliasOrPassThrough}, or nothing for an
 * ungated top-level loop) already establishes what the head's check would reconfirm. The loop-back
 * path ({@code continueMarker.matcher = bodyHead}) still goes through the head's gate, since it
 * has no such guarantee.
 */
final class LoopFirstEntryMatcherConstruct extends MatcherConstruct {
	LoopFirstEntryMatcherConstruct(int flags, MatcherConstruct bodyHead) {
		super(flags, bodyHead);
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		return next.matchBody(matcher, peeked);
	}
}

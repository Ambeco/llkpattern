package com.tbohne.llkpattern;



final class BackReferenceMatcherConstruct extends MatcherConstruct {
	final int captureConstructIndex;

	BackReferenceMatcherConstruct(PatternConstruct owner, int captureConstructIndex) {
		super(owner, owner.next().matcher());
		this.captureConstructIndex = captureConstructIndex;
	}

	@Override
	boolean matchBody(Matcher matcher, int peeked) {
		int base = captureConstructIndex * 2;
		int start = matcher.captureGroups[base];
		int end = matcher.captureGroups[base + 1];
		if (start < 0) {
			// The referenced group never participated in the match (e.g. it's in a sibling
			// alternation branch that wasn't taken) -- java.util.regex treats an unparticipated
			// group's backreference as never matching, not as matching the empty string. Nothing
			// has been consumed yet, so -- as below -- it's safe to defer to failedEntry (this
			// node's own loop-exit/next-union-candidate, when it's a chain candidate at all)
			// rather than failing the whole match outright.
			return failedEntry != null && failedEntry.match(matcher, peeked);
		}
		if (start == end) {
			return next.match(matcher, peeked);
		}
		// Compared straight against matcher.input by index rather than materializing the
		// captured text as its own String/CharSequence first -- there's nothing here that needs
		// one, and a backreference can be matched repeatedly (e.g. inside a loop), so avoiding an
		// allocation per comparison (not just per capture) matters more than it would for a
		// one-shot use.
		String input = matcher.input;
		int i = start;
		do {
			int next = input.codePointAt(i);
			int units = Character.isSupplementaryCodePoint(next) ? 2 : 1;
			if (!codePointsMatch(next, peeked, flags)) {
				// java.util.regex's BackRef checks the whole group's length against the input
				// left BEFORE comparing anything, so a too-short remainder is a hit-end even if
				// it would also have mismatched.
				if (matcher.pos + (end - i) > matcher.regionEnd) {
					matcher.hitEnd = true;
				}
				// A mismatch on the very FIRST code point of this attempt (i == start) hasn't
				// consumed anything yet, so it's exactly as safe to defer to failedEntry (this
				// backreference's own loop-exit, when it's compiled as a loop body part -- see
				// QuantifiableConstruct.buildLoopMatcher's per-part dispatchFailedEntry wiring,
				// unchanged by this) as an entrySet miss would have been -- entrySet only gates on
				// the group's overall (possibly multi-valued) first-character set, e.g.
				// `([ab])\1?`, so this is the actual, precise check that set was too coarse to
				// make. A mismatch AFTER already consuming one or more matching code points of a
				// multi-character captured group, by contrast, has irreversibly committed input
				// this engine can't un-consume -- deliberately a hard failure here (`return
				// false`), the exact same "no backtracking" limitation as a plain multi-character
				// loop body failing mid-iteration (see KnownDivergenceTest's
				// multiCharLoopBodyThatFailsMidIterationIsNotRetried, and `ab(ab)?` vs "aba").
				return i == start && failedEntry != null && failedEntry.match(matcher, peeked);
			}
			peeked = matcher.consumeCodeUnits(units);
			i += units;
		} while (i < end);
		return next.match(matcher, peeked);
	}
}

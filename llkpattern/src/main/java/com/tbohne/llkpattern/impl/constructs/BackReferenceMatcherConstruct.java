package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;



/**
 * Matches whatever group {@code captureConstructIndex} captured last, then advances: {@code
 * \1}/{@code \k<name>}, resolved to a fixed index at parse time.
 */
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
			// The group never participated (e.g. in an untaken alternation branch): java.util.regex
			// treats that as never matching, not as empty. Nothing is consumed yet, so deferring to
			// failedEntry is safe.
			return failedEntry != null && failedEntry.match(matcher, peeked);
		}
		if (start == end) {
			return next.match(matcher, peeked);
		}
		// Compared against matcher.input by index: no allocation per comparison (it may run in a loop).
		String input = matcher.input;
		int i = start;
		do {
			int next = input.codePointAt(i);
			int units = Character.isSupplementaryCodePoint(next) ? 2 : 1;
			if (!codePointsMatch(next, peeked, flags)) {
				// java.util.regex's BackRef checks the group's length against the remaining input
				// first, so a too-short remainder is a hitEnd even if it would also mismatch.
				if (matcher.pos + (end - i) > matcher.regionEnd) {
					matcher.hitEnd = true;
				}
				// A mismatch on the FIRST code point consumed nothing, so deferring to failedEntry is as
				// safe as an entrySet miss (entrySet gates only on the group's coarse first-char set,
				// e.g. `([ab])\1?`). A mismatch after consuming part of a multi-character capture has
				// irreversibly committed input: a hard failure, the same no-backtracking limitation as
				// a loop body failing mid-iteration (KnownDivergenceTest
				// multiCharLoopBodyThatFailsMidIterationIsNotRetried, `ab(ab)?` vs "aba").
				return i == start && failedEntry != null && failedEntry.match(matcher, peeked);
			}
			peeked = matcher.consumeCodeUnits(units);
			i += units;
		} while (i < end);
		return next.match(matcher, peeked);
	}
}

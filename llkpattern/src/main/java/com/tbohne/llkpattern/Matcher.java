package com.tbohne.llkpattern;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.regex.MatchResult;

/**
 * An engine that performs match operations on a character sequence by
 * interpreting an Ll1Pattern.
 *
 * <p>This is very similar to {@link java.util.regex.Matcher}, except that it
 * requires an Ll1Pattern input:
 * <ul>
 *   <li>The first character of each branch of a choice ("|", aka alternation, aka
 *   set-union) must have a strictly distinct pattern.</li>
 *   <li>The first character after any quantifier ("?" or "*" or "+" or "{n,m}")
 *   must have a strictly distinct pattern than the start of the qualifier.</li>
 *   <li>(?idmsuxU) and (?idmsux:...) cannot be used to turn flags on and off</li>
 * </ul>
 * These restrictions make writing a regex more annoying, but the pattern can
 * be both compiled and matched in linear time. FAR faster than a full regex.
 * Note that since this restriction would effectively make "*" useless, this code
 * simply makes it match everything that would otherwise be valid.
 */
public class Matcher implements MatchResult {
	public static String quoteReplacement(String s) {
		throw new UnsupportedOperationException("TODO: implement Matcher#quoteReplacement");
	}

	// Package-private (not private) so MatcherConstruct can read pattern.flags() for
	// case-insensitive matching (CASE_INSENSITIVE/UNICODE_CASE) -- see MatcherConstruct#getNext and
	// LiteralMatcherConstruct#match.
	Ll1Pattern pattern;
	String input;
	int regionEnd;
	int regionStart = 0;
	int pos = 0;
	int[] quantifiableCounts;
	// One slot per capture-group construct in the pattern, indexed by captureConstructIndex.
	// BeginCaptureMatcherConstruct overwrites the slot on entry; since there's no recursion or
	// backtracking in this engine, the same construct can never be "open" twice at once, so a flat
	// array (not an actual stack) suffices -- re-entering a capture inside a loop naturally
	// implements regex's "last iteration wins" semantics by simply overwriting the previous Group,
	// and a slot a loop never entered stays null (unset), also matching regex semantics.
	@Nullable Group[] captureGroups;

	// Set by attemptMatch() before each match attempt, read by MatcherConstruct.EndMatcherConstruct:
	// true for matches() (the whole region must be consumed), false for lookingAt()/find() (a
	// prefix match starting at `pos` is enough). This is the one place the "same compiled graph"
	// design needs a runtime switch -- see design.md.
	boolean requireFullMatch;

	// The most recent successful match's span, and whether one exists yet at all (start()/end()/
	// group() throw IllegalStateException before the first successful match(), same as
	// java.util.regex.Matcher).
	private boolean hasMatch = false;
	private int matchStart = -1;
	private int matchEnd = -1;

	Matcher(Ll1Pattern pattern, String input) {
		this.pattern = pattern;
		this.input = input;
		this.regionEnd = input.length();
		this.quantifiableCounts = new int[pattern.quantifiableCount];
		this.captureGroups = new Group[pattern.captureGroupCount];
	}

	public Matcher appendReplacement(StringBuffer sb, String replacement) {
		throw new UnsupportedOperationException("TODO: implement Matcher#appendReplacement");
	}

	public StringBuffer appendTail(StringBuffer sb) {
		throw new UnsupportedOperationException("TODO: implement Matcher#appendTail");
	}

	public int end() {
		return end(0);
	}

	public int end(int group) {
		if (group == 0) {
			requireMatch();
			return matchEnd;
		}
		Group g = captureGroup(group);
		if (g == null || g.result == null) {
			return -1;
		}
		return g.inputStartIndex + g.result.length();
	}

	public int end(String name) {
		return end(groupIndexByName(name));
	}

	public boolean find() {
		// Same start-of-search-window semantics as java.util.regex: resume right after the previous
		// match, advancing by one extra position if that match was empty so find() always makes
		// forward progress instead of matching the same empty span forever.
		int nextStart = hasMatch ? (matchEnd == matchStart ? matchEnd + 1 : matchEnd) : regionStart;
		return find(nextStart);
	}

	public boolean find(int start) {
		if (pattern.anchorsToPreviousMatchEnd) {
			// \G: no PatternConstruct/MatcherConstruct involved at all -- it's purely this flag,
			// meaning "only try exactly here, don't scan forward looking for a later match." See
			// PatternParser#anchorsToPreviousMatchEnd's doc.
			boolean success = attemptMatch(start, false);
			if (!success) {
				hasMatch = false;
			}
			return success;
		}
		for (int i = start; i <= regionEnd; i++) {
			// Unicode code points, not UTF-16 code units, are the atomic matching unit (see
			// java.util.regex's own behavior, and JDK-8149446): a valid high+low surrogate pair must
			// never be split, so `i` landing on the low half of one is not a legal place to *start* a
			// match, even though it's a legal char index. Skipping it here (rather than in
			// attemptMatch/peek, which is also used for internal within-match advancement that's
			// already code-point-aware via consume1CodePoint) keeps this fix scoped to exactly the
			// bug: find()'s scan treating every char index as a candidate start position.
			if (i > 0
					&& i < input.length()
					&& Character.isLowSurrogate(input.charAt(i))
					&& Character.isHighSurrogate(input.charAt(i - 1))) {
				continue;
			}
			if (attemptMatch(i, false)) {
				return true;
			}
		}
		hasMatch = false;
		return false;
	}

	public String group() {
		return group(0);
	}

	public String group(int group) {
		if (group == 0) {
			requireMatch();
			return input.substring(matchStart, matchEnd);
		}
		Group g = captureGroup(group);
		return g == null ? null : g.result;
	}

	public String group(String name) {
		return group(groupIndexByName(name));
	}

	public int groupCount() {
		return pattern.captureGroupCount;
	}

	public boolean hasAnchoringBounds()  {
		throw new UnsupportedOperationException("TODO: implement Matcher#hasAnchoringBounds");
	}

	public boolean hasTransparentBounds()  {
		throw new UnsupportedOperationException("TODO: implement Matcher#hasTransparentBounds");
	}

	public boolean hitEnd() {
		throw new UnsupportedOperationException("TODO: implement Matcher#hitEnd");
	}

	public boolean lookingAt() {
		return attemptMatch(regionStart, false);
	}

	public boolean matches() {
		return attemptMatch(regionStart, true);
	}

	public Ll1Pattern pattern() {
		return pattern;
	}

	public Matcher region(int start, int end)  {
		this.regionStart = start;
		this.regionEnd = end;
		this.pos = start;
		hasMatch = false;
		return this;
	}

	public int regionEnd()  {
		return regionEnd;
	}

	public int regionStart()  {
		return regionStart;
	}

	String replaceAll(String replacement) {
		throw new UnsupportedOperationException("TODO: implement Matcher#replaceAll");
	}

	String replaceFirst(String replacement) {
		throw new UnsupportedOperationException("TODO: implement Matcher#replaceFirst");
	}

	boolean requireEnd() {
		throw new UnsupportedOperationException("TODO: implement Matcher#requireEnd");
	}

	public Matcher reset() {
		regionStart = 0;
		regionEnd = input.length();
		pos = 0;
		resetMatchState();
		return this;
	}

	public Matcher reset(String input)  {
		this.input = input;
		regionStart = 0;
		regionEnd = input.length();
		pos = 0;
		resetMatchState();
		return this;
	}

	private void resetMatchState() {
		hasMatch = false;
		matchStart = -1;
		matchEnd = -1;
		java.util.Arrays.fill(quantifiableCounts, 0);
		java.util.Arrays.fill(captureGroups, null);
	}

	public int start() {
		return start(0);
	}

	public int start(int group)  {
		if (group == 0) {
			requireMatch();
			return matchStart;
		}
		Group g = captureGroup(group);
		return g == null ? -1 : g.inputStartIndex;
	}

	public int start(String name)  {
		return start(groupIndexByName(name));
	}

	public MatchResult toMatchResult() {
		throw new UnsupportedOperationException("TODO: implement Matcher#toMatchResult");
	}

	public String toString()  {
		return pattern.toString();
	}

	public Matcher useAnchoringBounds(boolean b)  {
		throw new UnsupportedOperationException("TODO: implement Matcher#useAnchoringBounds");
	}

	public Matcher usePattern(Ll1Pattern newPattern)  {
		if (pattern == null) {
			throw new IllegalArgumentException("newPattern cannot be null");
		}
		pattern = newPattern;
		quantifiableCounts = new int[newPattern.quantifiableCount];
		captureGroups = new Group[newPattern.captureGroupCount];
		resetMatchState();
		return this;
	}

	public Matcher useTransparentBounds(boolean b)  {
		throw new UnsupportedOperationException("TODO: implement Matcher#useTransparentBounds");
	}

	/**
	 * Attempts one match starting at code point index {@code from}, requiring the whole region to
	 * be consumed iff {@code requireFullMatch}. On success, records the match span (readable via
	 * start()/end()/group()) and leaves {@link #hasMatch} true.
	 */
	private boolean attemptMatch(int from, boolean requireFullMatch) {
		pos = from;
		this.requireFullMatch = requireFullMatch;
		boolean success = pattern.compiled.match(this, peek());
		if (success) {
			hasMatch = true;
			matchStart = from;
			matchEnd = pos;
		}
		return success;
	}

	private void requireMatch() {
		if (!hasMatch) {
			throw new IllegalStateException("No match found");
		}
	}

	private @Nullable Group captureGroup(int group) {
		requireMatch();
		if (group < 0 || group > pattern.captureGroupCount) {
			throw new IndexOutOfBoundsException("No group " + group);
		}
		// captureConstructIndex is 0-based for the first *real* capturing group (group 1 in the
		// public/java.util.regex numbering, where group 0 is the whole match) -- see PatternParser.
		return captureGroups[group - 1];
	}

	private int groupIndexByName(String name) {
		Integer index = pattern.namedGroups.get(name);
		if (index == null) {
			throw new IllegalArgumentException("No group with name <" + name + ">");
		}
		// Bug fix (2026-09-06): pattern.namedGroups stores the 0-based captureConstructIndex (see
		// PatternParser), but group(int)/start(int)/end(int) all expect the 1-based *public*
		// numbering, where group 0 means "the whole match" -- returning the raw 0-based index
		// unconverted meant the FIRST named group in any pattern (captureConstructIndex 0) silently
		// resolved to group(0), i.e. always returned the whole match instead of that group's own
		// text. Masked in existing tests where the whole match happened to equal the group's own
		// text (e.g. a pattern that is just "(?<name>x)"). See remaining_work.md.
		return index + 1;
	}

	// -1 is used throughout as the "no more input" sentinel passed to MatcherConstruct#match /
	// #getNext: it can never equal a real code point, so it simply fails to match any dispatchMap
	// range, which is exactly what should happen once the input is exhausted.
	int peek() {
		return pos < regionEnd ? input.codePointAt(pos) : -1;
	}

	// Used by WordBoundaryMatcherConstruct (\b/\B), which is the only construct that needs to look
	// backward instead of forward -- see design.md's "Boundary matching" section. Bounded at
	// regionStart, not 0: useTransparentBounds() is still a stub (opaque bounds only), so a region's
	// start is treated the same as true start-of-input, same as -1 is peek()'s "no more input"
	// sentinel. codePointBefore (not charAt(pos-1)) to not split a surrogate pair.
	int peekPrevious() {
		return pos <= regionStart ? -1 : input.codePointBefore(pos);
	}

	boolean consumeLiteral(String value) {
		if (pos + value.length() >= input.length()) {
			return false;
		}
		if (!input.startsWith(value, pos)) {
			return false;
		}
		pos += value.length();
		return true;
	}

	int consume1CodePoint() {
		// The previous width computation (`codeunit <= 0xDFF || codeunit >= 0xE000 ? 1 : 2`) used
		// the wrong bounds entirely -- 0xDFF isn't near the surrogate range (0xD800-0xDFFF) -- and
		// neither version guarded against `pos` reaching the end of input, which crashed on the
		// very common case of consuming the last character of a match.
		pos += Character.charCount(input.codePointAt(pos));
		return pos < regionEnd ? input.codePointAt(pos) : -1;
	}

	int consumeCodeUnits(int width) {
		pos += width;
		return pos < regionEnd ? input.codePointAt(pos) : -1;
	}

	int beginCapture(String name) {
		throw new UnsupportedOperationException("TODO: implement Matcher#beginCapture");
	}

	void endCapture(int captureId, boolean result) {
		throw new UnsupportedOperationException("TODO: implement Matcher#endCapture");
	}

	static final class Group {
		int inputStartIndex;
		@MonotonicNonNull String result;

		Group(int inputStartIndex) {
			this.inputStartIndex = inputStartIndex;
		}
	}
}

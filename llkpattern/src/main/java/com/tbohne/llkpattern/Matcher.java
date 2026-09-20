package com.tbohne.llkpattern;

import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
		if (s.indexOf('\\') == -1 && s.indexOf('$') == -1) {
			return s;
		}
		StringBuilder sb = new StringBuilder(s.length() + 4);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' || c == '$') {
				sb.append('\\');
			}
			sb.append(c);
		}
		return sb.toString();
	}

	// Package-private (not private) so MatcherConstruct can read pattern.flags() for
	// case-insensitive matching (CASE_INSENSITIVE/UNICODE_CASE) -- see MatcherConstruct#getNext and
	// LiteralMatcherConstruct#match.
	Ll1Pattern pattern;
	// NOT also cached as a char[] the way PatternParser.patternChars caches `pattern` (see that
	// field's own doc for the codePointAt win a char[] gives): tried it, measured a clear
	// regression instead -- toCharArray() is an O(input.length()) copy, and unlike a Pattern
	// (compiled once, matched many times), a Matcher is typically constructed fresh per match
	// operation, so that copy's cost is paid on close to every match rather than amortized.
	// Confirmed via the Pixel 3a on-device benchmark: matchLlk 0.688 -> 1.117 ms/pass (+62%),
	// matchLlk allocation 37,744 -> 52,752 B/op (+40%) -- reverted, not kept.
	String input;
	int regionEnd;
	int regionStart = 0;
	int pos = 0;
	// The code point at `pos` (or -1 at/past regionEnd) -- kept in sync by every method that moves
	// `pos` (attemptMatch/consume1CodePoint/consumeCodeUnits/region/reset*, all via syncPeeked()),
	// so peek() below is a plain field read instead of a fresh input.codePointAt(pos) call, and
	// consume1CodePoint() no longer needs to call codePointAt(pos) once just to compute the
	// consumed char's width before calling it again at the new position -- both real CPU cost per
	// this project's own Android CPU sampling (String.codePointAt was 11.8% of matchLlk time,
	// Matcher.consume1CodePoint's two internal calls to it 4.1%/1.9% of that on their own).
	int peeked;
	int[] quantifiableCounts;
	// Two slots (start, end -- input code-unit indices) per capture-group construct in the
	// pattern, indexed by captureConstructIndex*2. BeginCaptureMatcherConstruct overwrites the
	// start slot (and resets the end slot to -1) on entry; since there's no recursion or
	// backtracking in this engine, the same construct can never be "open" twice at once, so a flat
	// array (not an actual stack) suffices -- re-entering a capture inside a loop naturally
	// implements regex's "last iteration wins" semantics by simply overwriting the previous
	// entry, and a slot a loop never entered stays -1 (unset), also matching regex semantics.
	// -1 rather than a boxed/nullable Group also means EndCaptureMatcherConstruct no longer has to
	// eagerly allocate a substring every time a capture completes (see allocation sampling in
	// benchmarks/Intel-i7-9750H_llkMatch_alloc_sampling.txt) -- group(int) below builds the String
	// lazily, only when a caller actually asks for that group's text, and
	// BackReferenceMatcherConstruct compares directly against these indices without ever
	// materializing one at all.
	int[] captureGroups;

	// True exactly when quantifiableCounts/captureGroups are already known zero/null -- right after
	// construction (both arrays are `new`-allocated, so already zero-filled by the JVM without an
	// explicit resetPerAttemptState() call) or an explicit reset()/reset(String)/usePattern() (which
	// still calls resetPerAttemptState() itself, then sets this true). attemptMatch() consults this
	// to skip a redundant resetPerAttemptState() on the very first attempt after any of those --
	// state can only have been dirtied by a PRIOR attempt, and there isn't one yet. Set false again
	// by attemptMatch() itself before running, since that attempt (success or failure) may leave
	// either array non-fresh for whatever attempt comes next.
	private boolean perAttemptStateIsFresh = true;

	// Set by attemptMatch() before each match attempt, read by MatcherConstruct.EndMatcherConstruct:
	// true for matches() (the whole region must be consumed), false for lookingAt()/find() (a
	// prefix match starting at `pos` is enough). This is the one place the "same compiled graph"
	// design needs a runtime switch -- see design.md.
	boolean requireFullMatch;

	// hitEnd()/requireEnd() state. Sticky across every start position one find() tries, cleared at
	// the start of each matches()/lookingAt()/find(int) (same as java.util.regex, which clears them
	// at the start of every match/search operation). Only ever set from MatcherConstruct's cold
	// paths -- a dispatch/character miss at end of input, a literal that runs off the end, a
	// $/\z/\Z/\b that matched at end of input -- so the match hot path never touches them.
	boolean hitEnd;
	boolean requireEnd;

	// The most recent successful match's span, and whether one exists yet at all (start()/end()/
	// group() throw IllegalStateException before the first successful match(), same as
	// java.util.regex.Matcher).
	private boolean hasMatch = false;
	private int matchStart = -1;
	private int matchEnd = -1;
	// Where the next appendReplacement() should resume copying unmatched input from: the end of the
	// previous appendReplacement()'s match (0 before any).
	private int appendPos = 0;

	Matcher(Ll1Pattern pattern, String input) {
		this.pattern = pattern;
		this.input = input;
		this.regionEnd = input.length();
		this.quantifiableCounts = new int[pattern.quantifiableCount];
		this.captureGroups = new int[pattern.captureGroupCount * 2];
		java.util.Arrays.fill(captureGroups, -1);
		syncPeeked();
	}

	public Matcher appendReplacement(StringBuffer sb, String replacement) {
		sb.append(replacementText(replacement));
		return this;
	}

	public Matcher appendReplacement(StringBuilder sb, String replacement) {
		sb.append(replacementText(replacement));
		return this;
	}

	public StringBuffer appendTail(StringBuffer sb) {
		sb.append(input, appendPos, input.length());
		return sb;
	}

	public StringBuilder appendTail(StringBuilder sb) {
		sb.append(input, appendPos, input.length());
		return sb;
	}

	/** The unmatched input since the last appendReplacement(), followed by {@code replacement} with its
	 *  {@code $n}/{@code ${name}}/backslash escapes expanded against the current match. Advances
	 *  {@link #appendPos}. Error cases and messages mirror java.util.regex.Matcher's. */
	private String replacementText(String replacement) {
		if (!hasMatch) {
			throw new IllegalStateException("No match available");
		}
		StringBuilder result = new StringBuilder();
		result.append(input, appendPos, matchStart);
		int cursor = 0;
		int length = replacement.length();
		while (cursor < length) {
			char c = replacement.charAt(cursor);
			if (c == '\\') {
				cursor++;
				if (cursor == length) {
					throw new IllegalArgumentException(
							"character to be escaped is missing (a trailing backslash in a replacement must itself be "
									+ "escaped as \\\\; did you mean Matcher.quoteReplacement(...)?)");
				}
				result.append(replacement.charAt(cursor));
				cursor++;
			} else if (c == '$') {
				cursor++;
				if (cursor == length) {
					throw new IllegalArgumentException(
							"Illegal group reference: group index is missing (a literal '$' in a replacement must be "
									+ "escaped as \\$; did you mean Matcher.quoteReplacement(...)?)");
				}
				c = replacement.charAt(cursor);
				int refNum;
				if (c == '{') {
					cursor++;
					int nameStart = cursor;
					while (cursor < length && isAsciiAlphanumeric(replacement.charAt(cursor))) {
						cursor++;
					}
					String name = replacement.substring(nameStart, cursor);
					if (name.isEmpty()) {
						throw new IllegalArgumentException("named capturing group has 0 length name");
					}
					if (cursor == length || replacement.charAt(cursor) != '}') {
						throw new IllegalArgumentException("named capturing group is missing trailing '}'");
					}
					if (name.charAt(0) >= '0' && name.charAt(0) <= '9') {
						throw new IllegalArgumentException(
								"capturing group name {" + name + "} starts with digit character");
					}
					Integer index = pattern.namedGroups.get(name);
					if (index == null) {
						throw new IllegalArgumentException("No group with name {" + name + "}");
					}
					refNum = index + 1;
					cursor++;
				} else {
					refNum = c - '0';
					if (refNum < 0 || refNum > 9) {
						throw new IllegalArgumentException(
								"Illegal group reference (expected a digit or {name} after '$', got '" + c + "')");
					}
					cursor++;
					// Greedy extra digits, but only while the result is still a real group -- so "$10"
					// with one group means group 1 followed by a literal '0'.
					while (cursor < length) {
						int digit = replacement.charAt(cursor) - '0';
						if (digit < 0 || digit > 9) {
							break;
						}
						int extended = refNum * 10 + digit;
						if (groupCount() < extended) {
							break;
						}
						refNum = extended;
						cursor++;
					}
				}
				String text = group(refNum);
				if (text != null) {
					result.append(text);
				}
			} else {
				result.append(c);
				cursor++;
			}
		}
		appendPos = matchEnd;
		return result.toString();
	}

	private static boolean isAsciiAlphanumeric(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
	}

	public int end() {
		return end(0);
	}

	public int end(int group) {
		if (group == 0) {
			requireMatch();
			return matchEnd;
		}
		return captureGroups[captureGroupBaseIndex(group) + 1];
	}

	public int end(String name) {
		return end(groupIndexByName(name));
	}

	public boolean find() {
		// Same start-of-search-window semantics as java.util.regex: resume right after the previous
		// match, advancing by one extra position if that match was empty so find() always makes
		// forward progress instead of matching the same empty span forever.
		// Like java.util.regex, resumes from the previous match's end even after a failed find() (which
		// keeps matchEnd but clears matchStart), so a failed find() stays failed.
		int nextStart = matchEnd == matchStart ? matchEnd + 1 : matchEnd;
		if (nextStart < regionStart) {
			nextStart = regionStart;
		}
		if (nextStart > regionEnd) {
			// Deliberately leaves matchStart alone: clearing it would make the next find() resume at
			// matchEnd again instead of failing again.
			hasMatch = false;
			hitEnd = true;
			requireEnd = false;
			return false;
		}
		return search(nextStart);
	}

	/** Like java.util.regex.Matcher#find(int): resets this matcher (including its region) first. */
	public boolean find(int start) {
		if (start < 0 || start > input.length()) {
			throw new IndexOutOfBoundsException(
					"Illegal start index " + start + " (input length is " + input.length() + ")");
		}
		reset();
		return search(start);
	}

	private boolean search(int start) {
		hitEnd = false;
		requireEnd = false;
		if (pattern.anchorsToPreviousMatchEnd) {
			// \G: no PatternConstruct/MatcherConstruct involved at all -- it's purely this flag,
			// meaning "only try exactly here, don't scan forward looking for a later match." See
			// PatternParser#anchorsToPreviousMatchEnd's doc.
			boolean success = attemptMatch(start, false);
			if (!success) {
				hasMatch = false;
				matchStart = -1;
				// java.util.regex's search loop always ends a failed find() by running off the end.
				hitEnd = true;
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
		matchStart = -1;
		if (!pattern.startsWithBeginAnchor) {
			hitEnd = true;
		}
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
		int base = captureGroupBaseIndex(group);
		int start = captureGroups[base];
		int end = captureGroups[base + 1];
		// Built lazily, only for a group a caller actually asks the text of -- EndCaptureMatcherConstruct
		// itself only ever records the (start, end) indices, not a materialized substring. -1 means
		// this group never participated in the match (e.g. it's in a sibling alternation branch that
		// wasn't taken), same as a null Group used to mean before this array-based representation.
		return start < 0 ? null : input.substring(start, end);
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

	/** True if the end of input was hit (or examined) by the search engine in the last match operation
	 *  -- see java.util.regex.Matcher#hitEnd. */
	public boolean hitEnd() {
		return hitEnd;
	}

	public boolean lookingAt() {
		hitEnd = false;
		requireEnd = false;
		return attemptMatch(regionStart, false);
	}

	public boolean matches() {
		hitEnd = false;
		requireEnd = false;
		return attemptMatch(regionStart, true);
	}

	public Ll1Pattern pattern() {
		return pattern;
	}

	public Matcher region(int start, int end)  {
		this.regionStart = start;
		this.regionEnd = end;
		this.pos = start;
		resetMatchState();
		syncPeeked();
		return this;
	}

	public int regionEnd()  {
		return regionEnd;
	}

	public int regionStart()  {
		return regionStart;
	}

	public String replaceAll(String replacement) {
		return replaceAll(m -> replacement);
	}

	public String replaceAll(Function<MatchResult, String> replacer) {
		reset();
		if (!find()) {
			return input;
		}
		StringBuilder sb = new StringBuilder();
		do {
			appendReplacement(sb, replacer.apply(this));
		} while (find());
		appendTail(sb);
		return sb.toString();
	}

	public String replaceFirst(String replacement) {
		return replaceFirst(m -> replacement);
	}

	public String replaceFirst(Function<MatchResult, String> replacer) {
		reset();
		if (!find()) {
			return input;
		}
		StringBuilder sb = new StringBuilder();
		appendReplacement(sb, replacer.apply(this));
		appendTail(sb);
		return sb.toString();
	}

	/** Like java.util.regex.Matcher#results: resets this matcher, then lazily yields a snapshot of each
	 *  successive find(). */
	public Stream<MatchResult> results() {
		reset();
		return StreamSupport.stream(
				new Spliterators.AbstractSpliterator<MatchResult>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
					@Override
					public boolean tryAdvance(java.util.function.Consumer<? super MatchResult> action) {
						if (!find()) {
							return false;
						}
						action.accept(toMatchResult());
						return true;
					}
				},
				false);
	}

	/** True if more input could change a positive match into a negative one -- see
	 *  java.util.regex.Matcher#requireEnd. Only meaningful after a successful match. */
	public boolean requireEnd() {
		return requireEnd;
	}

	public Matcher reset() {
		regionStart = 0;
		regionEnd = input.length();
		pos = 0;
		syncPeeked();
		resetMatchState();
		return this;
	}

	public Matcher reset(String input)  {
		this.input = input;
		regionStart = 0;
		regionEnd = input.length();
		pos = 0;
		syncPeeked();
		resetMatchState();
		return this;
	}

	private void resetMatchState() {
		hasMatch = false;
		appendPos = 0;
		matchStart = -1;
		matchEnd = 0;
		resetPerAttemptState();
		perAttemptStateIsFresh = true;
	}

	/** The per-attempt state a fresh match attempt must never see left over from an earlier one --
	 *  see {@link #attemptMatch}'s doc for why this must run before EVERY attempt, not just on an
	 *  explicit reset()/reset(String). */
	private void resetPerAttemptState() {
		java.util.Arrays.fill(quantifiableCounts, 0);
		java.util.Arrays.fill(captureGroups, -1);
	}

	public int start() {
		return start(0);
	}

	public int start(int group)  {
		if (group == 0) {
			requireMatch();
			return matchStart;
		}
		return captureGroups[captureGroupBaseIndex(group)];
	}

	public int start(String name)  {
		return start(groupIndexByName(name));
	}

	/** A snapshot of the current match, unaffected by later use of this matcher. If there is no current
	 *  match, its accessors throw IllegalStateException, same as this matcher's own would. */
	public MatchResult toMatchResult() {
		int groups = pattern.captureGroupCount;
		int[] bounds = new int[(groups + 1) * 2];
		if (hasMatch) {
			bounds[0] = matchStart;
			bounds[1] = matchEnd;
			System.arraycopy(captureGroups, 0, bounds, 2, groups * 2);
		}
		return new Snapshot(hasMatch ? input : null, bounds);
	}

	private static final class Snapshot implements MatchResult {
		private final String input; // null iff there was no match
		private final int[] bounds; // start,end per group, group 0 (the whole match) first

		Snapshot(String input, int[] bounds) {
			this.input = input;
			this.bounds = bounds;
		}

		private int checkGroup(int group) {
			if (input == null) {
				throw new IllegalStateException("No match found");
			}
			if (group < 0 || group > groupCount()) {
				throw new IndexOutOfBoundsException("No group " + group);
			}
			return group * 2;
		}

		public int start() {
			return start(0);
		}

		public int start(int group) {
			return bounds[checkGroup(group)];
		}

		public int end() {
			return end(0);
		}

		public int end(int group) {
			return bounds[checkGroup(group) + 1];
		}

		public String group() {
			return group(0);
		}

		public String group(int group) {
			int i = checkGroup(group);
			return bounds[i] < 0 ? null : input.substring(bounds[i], bounds[i + 1]);
		}

		public int groupCount() {
			return bounds.length / 2 - 1;
		}
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
		captureGroups = new int[newPattern.captureGroupCount * 2];
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
		syncPeeked();
		this.requireFullMatch = requireFullMatch;
		// Bug fix (2026-09-07): quantifiableCounts/captureGroups used to only get reset by
		// reset()/reset(String) -- never per attempt -- so a loop's iteration counter (incremented by
		// LoopMatcherConstruct as it consumes each repetition) leaked from one match attempt into the
		// next whenever an attempt failed WITHOUT reaching its own EndLoopMatcherConstruct exit (the
		// only place a counter gets reset to 0), which happens routinely: e.g. a bounded {n,m} loop
		// whose body character overlaps with what comes after it (unavoidable when nothing follows
		// the loop at all, since EndConstruct's catch-all entryElse always looks like "keep going")
		// hits its own max bound and hard-fails via LoopMatcherConstruct's own "loopCount > max"
		// check, leaving the counter non-zero. find()'s internal scan over successive start positions
		// (and any other back-to-back matches() /lookingAt()/find() calls on a reused Matcher without
		// an intervening reset()) would then read that stale, nonzero counter on the NEXT attempt,
		// letting a since-satisfied `min` check spuriously pass on an attempt that should have started
		// counting from zero -- e.g. "a{2,3}" against "aaaa" hard-failed at every real start position,
		// then spuriously "matched" an empty string at the end of input once a leftover count of 3
		// (from an earlier failed attempt) made EndLoopMatcherConstruct's "loopCount(0-that-should've-
		// been) < min" check pass. Confirmed via the scraped-corpus harness's un-triaged UNEXPECTED
		// rows -- this single bug explains most of them: bounded quantifiers, optional ("?")
		// constructs, and quantified capturing groups all showed wrong matches/no-matches whenever a
		// find() scan or repeated match attempt was involved, not just a single matches() call.
		//
		// Skipped on the very first attempt after construction/reset()/reset(String)/usePattern()
		// (see perAttemptStateIsFresh's own doc): quantifiableCounts/captureGroups are already known
		// zero/null then, so there's nothing to reset yet -- only a PRIOR attempt (this one, about to
		// run) can dirty them, which is exactly what clearing the flag right after guards against.
		if (!perAttemptStateIsFresh) {
			resetPerAttemptState();
		}
		perAttemptStateIsFresh = false;
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

	/** Index into {@link #captureGroups} of {@code group}'s start slot ({@code +1} for its end
	 *  slot) -- {@code group} is 1-based public/java.util.regex numbering (group 0, "the whole
	 *  match", is handled separately by every caller); captureConstructIndex, what this converts
	 *  to, is 0-based for the first *real* capturing group -- see PatternParser. */
	private int captureGroupBaseIndex(int group) {
		requireMatch();
		if (group < 0 || group > pattern.captureGroupCount) {
			throw new IndexOutOfBoundsException("No group " + group);
		}
		return (group - 1) * 2;
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
	// range, which is exactly what should happen once the input is exhausted. Just returns the
	// already-computed `peeked` -- see that field's own doc.
	int peek() {
		return peeked;
	}

	/** Recomputes {@link #peeked} from the current {@link #pos}/{@link #regionEnd}/{@link #input}
	 *  -- called by every method that sets any of those three directly (as opposed to
	 *  consume1CodePoint()/consumeCodeUnits(), which advance {@code pos} by a width they already
	 *  know and can update {@code peeked} more cheaply themselves). */
	private void syncPeeked() {
		peeked = pos < regionEnd ? input.codePointAt(pos) : -1;
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
		// very common case of consuming the last character of a match. Character.charCount(peeked)
		// here, not a second input.codePointAt(pos) call, since `peeked` (about to be overwritten
		// below) is already exactly the code point at the current `pos`.
		pos += Character.charCount(peeked);
		peeked = pos < regionEnd ? input.codePointAt(pos) : -1;
		return peeked;
	}

	int consumeCodeUnits(int width) {
		pos += width;
		peeked = pos < regionEnd ? input.codePointAt(pos) : -1;
		return peeked;
	}

	int beginCapture(String name) {
		throw new UnsupportedOperationException("TODO: implement Matcher#beginCapture");
	}

	void endCapture(int captureId, boolean result) {
		throw new UnsupportedOperationException("TODO: implement Matcher#endCapture");
	}
}

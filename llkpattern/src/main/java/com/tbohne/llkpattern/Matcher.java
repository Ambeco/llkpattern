package com.tbohne.llkpattern;

import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

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
 * </ul>
 * Ambiguous patterns are rejected at compile time with a {@code PatternSyntaxException}.
 * These restrictions make writing a regex more annoying, but the pattern can
 * be both compiled and matched in linear time. FAR faster than a full regex.
 * Inline flags ({@code (?i)}, {@code (?i:...)}) are supported and scoped as in
 * {@code java.util.regex}. A {@code .} claims whatever its siblings don't, so
 * {@code .*z} means {@code [^z]*z}.
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

	// Package-private so MatcherConstruct can read pattern.flags() for case-insensitive matching.
	Ll1Pattern pattern;
	// NOT cached as a char[] like PatternParser.patternChars: measured a clear regression, since a Matcher is
	// typically built fresh per match and toCharArray() is O(input) (Pixel 3a matchLlk 0.688 -> 1.117 ms/pass,
	// allocation +40%).
	public String input;
	public int regionEnd;
	int regionStart = 0;
	// Where ^ $ \A \z \Z see the start/end of input: the region's own edges when anchoring bounds are
	// on (the default), else the true edges of the input. Always kept in sync with regionStart/
	// regionEnd/input/anchoringBounds by syncAnchors(); anchorStart <= regionStart, anchorEnd >= regionEnd.
	public int anchorStart = 0;
	public int anchorEnd;
	private boolean anchoringBounds = true;
	// How far \b/\B may look before regionStart / at-and-after regionEnd: the region's own edges when
	// bounds are opaque (the default), else the true edges of the input. Kept in sync by syncAnchors().
	private boolean transparentBounds = false;
	public int lookFloor = 0;
	public int lookCeil;
	public int pos = 0;
	// The code point at `pos` (-1 at/past regionEnd), kept in sync by every method that moves `pos`, so peek()
	// is a field read: String.codePointAt was 11.8% of matchLlk time on Android.
	public int peeked;
	// Cached peekPrevious(), computed lazily (only \b/\B, lookbehind and \b{g} read it). UNKNOWN_PREVIOUS means
	// "not yet computed", distinct from -1 ("no previous input"). consume1CodePoint() updates it for free; other
	// pos-moving methods invalidate it, since recomputing codePointBefore eagerly costs as much as on demand.
	private static final int UNKNOWN_PREVIOUS = Integer.MIN_VALUE;
	private int previousPeeked = UNKNOWN_PREVIOUS;
	public int[] quantifiableCounts;
	// Two slots (start, end code-unit indices) per capture construct, indexed by captureConstructIndex*2. A flat
	// array suffices (no recursion or backtracking, so a construct is never open twice): re-entering a capture in
	// a loop overwrites the previous entry ("last iteration wins"), and a slot never entered stays -1.
	// group(int) builds the String lazily and BackReferenceMatcherConstruct compares indices directly, so no
	// substring is allocated per capture (alloc sampling).
	public int[] captureGroups;

	// True when quantifiableCounts/captureGroups are known zero/null (fresh after construction or reset: new arrays
	// are zeroed), so attemptMatch() skips a redundant resetPerAttemptState() on the first attempt. Cleared by
	// attemptMatch() before running, since an attempt may dirty them.
	private boolean perAttemptStateIsFresh = true;

	// Set by attemptMatch() before each attempt, read by EndMatcherConstruct: true for matches() (consume the
	// whole region), false for lookingAt()/find(). The one runtime switch the shared compiled graph needs.
	public boolean requireFullMatch;

	// Sticky across every start position one find() tries; cleared at the start of each
	// matches()/lookingAt()/find(int), as in java.util.regex. Set only from cold paths (a miss at end of input, a
	// literal running off the end, $/\z/\Z/\b matching at the end), so the match hot path never touches them.
	public boolean hitEnd;
	public boolean requireEnd;

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
		this.anchorEnd = regionEnd;
		this.lookCeil = regionEnd;
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
				cursor = appendGroupReference(result, replacement, cursor + 1);
			} else {
				result.append(c);
				cursor++;
			}
		}
		appendPos = matchEnd;
		return result.toString();
	}

	/**
	 * Appends the group named by the reference starting at {@code cursor} (just past a {@code '$'}), and
	 * returns the cursor just past the reference. An unmatched group appends nothing.
	 */
	private int appendGroupReference(StringBuilder result, String replacement, int cursor) {
		int length = replacement.length();
		if (cursor == length) {
			throw new IllegalArgumentException(
					"Illegal group reference: group index is missing (a literal '$' in a replacement must be "
							+ "escaped as \\$; did you mean Matcher.quoteReplacement(...)?)");
		}
		char c = replacement.charAt(cursor);
		int refNum;
		if (c == '{') {
			int nameStart = cursor + 1;
			int nameEnd = nameStart;
			while (nameEnd < length && isAsciiAlphanumeric(replacement.charAt(nameEnd))) {
				nameEnd++;
			}
			if (nameEnd == nameStart) {
				throw new IllegalArgumentException("named capturing group has 0 length name");
			}
			if (nameEnd == length || replacement.charAt(nameEnd) != '}') {
				throw new IllegalArgumentException("named capturing group is missing trailing '}'");
			}
			refNum = namedGroupNumber(replacement.substring(nameStart, nameEnd));
			cursor = nameEnd + 1;
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
		return cursor;
	}

	private int namedGroupNumber(String name) {
		if (name.charAt(0) >= '0' && name.charAt(0) <= '9') {
			throw new IllegalArgumentException(
					"capturing group name {" + name + "} starts with digit character");
		}
		// -1 sentinel avoids boxing; real indices are >= 0.
		int index = pattern.namedGroups.getOrDefault(name, -1);
		if (index == -1) {
			throw new IllegalArgumentException("No group with name {" + name + "}");
		}
		return index + 1;
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
		// As java.util.regex: resume right after the previous match (one extra position if it was empty, so
		// find() progresses), and even after a failed find() (which keeps matchEnd but clears matchStart), so a
		// failed find() stays failed.
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
			// \G has no construct: it only means "try exactly here, don't scan forward".
			boolean success = attemptMatch(start, false);
			if (!success) {
				hasMatch = false;
				matchStart = -1;
				// java.util.regex's search loop always ends a failed find() by running off the end.
				hitEnd = true;
			}
			return success;
		}
		// Code points, not UTF-16 units, are the matching unit (JDK-8149446): a start on the low half of a surrogate
		// pair is illegal. Only `start` needs the check; later candidates step by a decoded code point's width.
		int i = start;
		int cp = i < regionEnd ? input.codePointAt(i) : -1;
		// Derived from `cp` plus the PRECEDING code unit: codePointAt only combines forward, so a genuine pair at i
		// already shows up as one supplementary cp. Must stay an int comparison, not (char) cp: a supplementary
		// cp's low 16 bits can fall in the low-surrogate range.
		if (i > 0
				&& i < input.length()
				&& cp >= Character.MIN_LOW_SURROGATE
				&& cp <= Character.MAX_LOW_SURROGATE
				&& Character.isHighSurrogate(input.charAt(i - 1))) {
			i++;
			cp = i < regionEnd ? input.codePointAt(i) : -1;
		}
		// One codePointAt per candidate position: `cp` covers the first, the loop recomputes it for later ones, and
		// the decoded value is threaded into attemptMatch() and reused to step `i`.
		while (i <= regionEnd) {
			if (attemptMatch(i, false, cp)) {
				return true;
			}
			i += cp == -1 ? 1 : Character.charCount(cp);
			cp = i < regionEnd ? input.codePointAt(i) : -1;
		}
		hasMatch = false;
		matchStart = -1;
		if (!pattern.startsWithBeginAnchor) {
			hitEnd = true;
		}
		return false;
	}

	@SuppressWarnings("override.return") // MatchResult.group is documented to return null for a non-participating group
	public @Nullable String group() {
		return group(0);
	}

	@SuppressWarnings("override.return") // MatchResult.group is documented to return null for a non-participating group
	public @Nullable String group(int group) {
		if (group == 0) {
			requireMatch();
			return input.substring(matchStart, matchEnd);
		}
		int base = captureGroupBaseIndex(group);
		int start = captureGroups[base];
		int end = captureGroups[base + 1];
		// Built lazily, only for a group a caller asks for. -1 means the group never participated (e.g. an untaken
		// alternation branch).
		return start < 0 ? null : input.substring(start, end);
	}

	@SuppressWarnings("override.return") // MatchResult.group is documented to return null for a non-participating group
	public @Nullable String group(String name) {
		return group(groupIndexByName(name));
	}

	public int groupCount() {
		return pattern.captureGroupCount;
	}

	public boolean hasAnchoringBounds() {
		return anchoringBounds;
	}

	public boolean hasTransparentBounds()  {
		return transparentBounds;
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
		syncAnchors();
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
		syncAnchors();
		syncPeeked();
		resetMatchState();
		return this;
	}

	/** Like java.util.regex.Matcher#reset(CharSequence), except the text is snapshotted with toString()
	 *  (as Ll1Pattern#matcher already does): later changes to a mutable CharSequence aren't seen. */
	public Matcher reset(CharSequence input)  {
		this.input = input.toString();
		regionStart = 0;
		regionEnd = input.length();
		pos = 0;
		syncAnchors();
		syncPeeked();
		resetMatchState();
		return this;
	}

	private void syncAnchors() {
		anchorStart = anchoringBounds ? regionStart : 0;
		anchorEnd = anchoringBounds ? regionEnd : input.length();
		lookFloor = transparentBounds ? 0 : regionStart;
		lookCeil = transparentBounds ? input.length() : regionEnd;
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
	 *  explicit reset()/reset(CharSequence). */
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
		private final @Nullable String input; // null iff there was no match
		private final int[] bounds; // start,end per group, group 0 (the whole match) first

		Snapshot(@Nullable String input, int[] bounds) {
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

		@SuppressWarnings("override.return") // MatchResult.group is documented to return null for a non-participating group
		public @Nullable String group() {
			return group(0);
		}

		@SuppressWarnings("override.return") // MatchResult.group is documented to return null for a non-participating group
		public @Nullable String group(int group) {
			int i = checkGroup(group);
			return bounds[i] < 0 ? null : castNonNull(input).substring(bounds[i], bounds[i + 1]);
		}

		public int groupCount() {
			return bounds.length / 2 - 1;
		}
	}

	/** Same shape as java.util.regex.Matcher#toString. */
	public String toString() {
		return getClass().getName() + "[pattern=" + pattern() + " region=" + regionStart + "," + regionEnd
				+ " lastmatch=" + (hasMatch ? group() : "") + "]";
	}

	/** Whether the previous match operation found a match -- see java.util.regex.Matcher#hasMatch. */
	public boolean hasMatch() {
		return hasMatch;
	}

	/** Named group to group number; see {@link Ll1Pattern#namedGroups()}. */
	public java.util.Map<String, Integer> namedGroups() {
		return pattern.namedGroups();
	}

	/** Like java.util.regex.Matcher#useAnchoringBounds: does not otherwise reset this matcher. */
	public Matcher useAnchoringBounds(boolean b) {
		anchoringBounds = b;
		syncAnchors();
		return this;
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
		transparentBounds = b;
		syncAnchors();
		return this;
	}

	/**
	 * Attempts one match starting at code point index {@code from}, requiring the whole region to
	 * be consumed iff {@code requireFullMatch}. On success, records the match span (readable via
	 * start()/end()/group()) and leaves {@link #hasMatch} true.
	 */
	private boolean attemptMatch(int from, boolean requireFullMatch) {
		pos = from;
		syncPeeked();
		return finishAttemptMatch(from, requireFullMatch);
	}

	/** Like {@link #attemptMatch(int, boolean)}, but for a caller (search()'s scan loop) that has
	 *  already decoded the code point at {@code from} itself -- skips the redundant syncPeeked()
	 *  re-decode. */
	private boolean attemptMatch(int from, boolean requireFullMatch, int precomputedPeeked) {
		pos = from;
		peeked = precomputedPeeked;
		previousPeeked = UNKNOWN_PREVIOUS;
		return finishAttemptMatch(from, requireFullMatch);
	}

	private boolean finishAttemptMatch(int from, boolean requireFullMatch) {
		this.requireFullMatch = requireFullMatch;
		// Reset per attempt, not just on reset(): a loop counter left non-zero by an attempt that failed without
		// reaching its exit (e.g. a bounded {n,m} loop hitting its max with nothing after it) leaked into the next
		// attempt of a find() scan or reused matcher, letting a stale count satisfy `min`: "a{2,3}" on "aaaa"
		// spuriously matched "" at the end.
		//
		// Skipped on the first attempt after construction/reset/usePattern (see perAttemptStateIsFresh): only a
		// PRIOR attempt can dirty the state.
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
		// -1 sentinel avoids boxing; real indices are >= 0.
		int index = pattern.namedGroups.getOrDefault(name, -1);
		if (index == -1) {
			throw new IllegalArgumentException("No group with name <" + name + ">");
		}
		// namedGroups stores the 0-based captureConstructIndex but group(int) expects 1-based public numbering
		// (0 = whole match); unconverted, the first named group resolved to group(0). Tests masked it when the
		// group's text equalled the whole match.
		return index + 1;
	}

	// -1 is the "no more input" sentinel passed to MatcherConstruct#match: never a real code point, so it matches
	// no dispatch range once input is exhausted. Returns the already-computed `peeked`.
	int peek() {
		return peeked;
	}

	// Recomputes peeked from pos/regionEnd/input, for methods that set those directly
	// (consume1CodePoint()/consumeCodeUnits() update it more cheaply themselves).
	private void syncPeeked(@UnknownInitialization(Matcher.class) Matcher this) {
		peeked = pos < regionEnd ? input.codePointAt(pos) : -1;
		previousPeeked = UNKNOWN_PREVIOUS;
	}

	// For \b/\B and lookbehind. Bounded at lookFloor (regionStart unless transparent bounds are on), so an opaque
	// region start looks like true start-of-input. codePointBefore, not charAt(pos-1), to not split a surrogate pair.
	public int peekPrevious() {
		if (pos <= lookFloor) {
			return -1;
		}
		if (previousPeeked == UNKNOWN_PREVIOUS) {
			previousPeeked = input.codePointBefore(pos);
		}
		return previousPeeked;
	}

	/** The code point at {@code pos} as {@code \b}/{@code \B} see it: like {@link #peek()}, except that
	 *  with transparent bounds it looks past regionEnd (-1 only at the true end of input). */
	public int peekForBoundary() {
		return peeked != -1 || pos >= lookCeil ? peeked : input.codePointAt(pos);
	}

	public int consume1CodePoint() {
		// Character.charCount(peeked), not a second codePointAt: peeked is the code point at the current pos.
		// previousPeeked is updated free too: the code point about to be overwritten IS the new previous.
		previousPeeked = peeked;
		pos += Character.charCount(peeked);
		peeked = pos < regionEnd ? input.codePointAt(pos) : -1;
		return peeked;
	}

	public int consumeCodeUnits(int width) {
		pos += width;
		peeked = pos < regionEnd ? input.codePointAt(pos) : -1;
		// Unlike consume1CodePoint(), width may span several code points (a literal, a grapheme cluster), so the
		// preceding code point isn't in hand: invalidate rather than pay for a codePointBefore() peekPrevious() may
		// never need.
		previousPeeked = UNKNOWN_PREVIOUS;
		return peeked;
	}
}

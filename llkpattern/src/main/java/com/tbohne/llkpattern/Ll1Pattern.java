package com.tbohne.llkpattern;

import com.tbohne.llkpattern.impl.unicode.UnicodeFlags;
import com.tbohne.llkpattern.impl.parser.PatternParser;
import com.tbohne.llkpattern.impl.constructs.*;

import androidx.collection.ObjectIntMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A compiled representation of an LL(1) regular expression.
 *
 * <p>This is very similar to {@link java.util.regex.Pattern}, except that it
 * requires an LL(1) input:
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
public final class Ll1Pattern {
	public static final int CANON_EQ = UnicodeFlags.CANON_EQ;
	public static final int CASE_INSENSITIVE = UnicodeFlags.CASE_INSENSITIVE;
	public static final int COMMENTS = Pattern.COMMENTS;
	public static final int DOTALL = Pattern.DOTALL;
	public static final int LITERAL = Pattern.LITERAL;
	public static final int MULTILINE = Pattern.MULTILINE;
	public static final int UNICODE_CASE = UnicodeFlags.UNICODE_CASE;
	public static final int UNICODE_CHARACTER_CLASS = UnicodeFlags.UNICODE_CHARACTER_CLASS;
	public static final int UNIX_LINES = Pattern.UNIX_LINES;

	public static Ll1Pattern compile(String pattern) {
		return compile(pattern, 0);
	}

	public static Ll1Pattern compile(String pattern, int flags) {
		PatternParser parser = new PatternParser(pattern, flags);
		PatternConstruct parsed = parser.parse();
		MatcherConstruct compiled;
		try {
			compiled = parsed.compile(EndPatternConstruct.INSTANCE);
		} catch (EntryPointCycleException e) {
			// Only for a quantified construct whose whole body can match zero characters (e.g. "(a?)+"); see
			// design.md "Entry-point computation vs. matcher compilation".
			throw PatternSyntaxException.throwWithReferences(
					pattern,
					e.startIndex,
					"the quantified construct starting at index ", e.startIndex,
					" has a body that can match zero characters, so its own \"what comes next\" set can't ",
					"be determined -- besides being unsupported here, a loop whose body can match nothing ",
					"is also an infinite-loop hazard; rewrite it so every iteration consumes at least one ",
					"character");
		}
		return new Ll1Pattern(
				pattern,
				flags,
				compiled,
				parser.getQuantifiableCount(),
				parser.getCaptureGroupCount(),
				parser.getNamedGroups(),
				parser.anchorsToPreviousMatchEnd(),
				startsWithBeginAnchor(parsed));
	}

	// Whether the whole pattern is a single alternative whose first element is \A or a
	// non-MULTILINE ^. java.util.regex tries such a pattern only at the search start instead of
	// scanning, so a failed find() there isn't a hit-end (see Matcher#find).
	private static boolean startsWithBeginAnchor(PatternConstruct parsed) {
		// PatternParser#parse() unwraps a single-alternative root, so that's a bare SequencePatternConstruct here.
		if (!(parsed instanceof SequencePatternConstruct)) {
			return false;
		}
		PatternConstruct first = ((SequencePatternConstruct) parsed).patterns.get(0);
		if (first instanceof BoundaryPatternConstruct) {
			return ((BoundaryPatternConstruct) first).type
					== BoundaryPatternConstruct.BoundaryEnum.InputBegin;
		}
		return first instanceof LineBoundaryPatternConstruct
				&& ((LineBoundaryPatternConstruct) first).isLineBegin
				&& (first.flags & MULTILINE) == 0;
	}

	public static boolean matches(String regex, CharSequence input) {
		return compile(regex).matcher(input).matches();
	}

	public static String quote(String s) {
		int slashEIndex = s.indexOf("\\E");
		if (slashEIndex == -1) {
			return "\\Q" + s + "\\E";
		}
		// A literal \E can't appear inside \Q...\E, so close the quotation, emit the backslash quoted,
		// and reopen for the 'E' onward -- same technique as java.util.regex.Pattern#quote.
		StringBuilder sb = new StringBuilder(s.length() * 2);
		sb.append("\\Q");
		int current = 0;
		while ((slashEIndex = s.indexOf("\\E", current)) != -1) {
			sb.append(s, current, slashEIndex);
			current = slashEIndex + 2;
			sb.append("\\E\\\\E\\Q");
		}
		sb.append(s, current, s.length());
		sb.append("\\E");
		return sb.toString();
	}

	private final String pattern;
	private final int flags;
	final MatcherConstruct compiled;
	// Sizes for a Matcher's per-match scratch arrays; captureGroupCount excludes implicit group 0 (the Matcher
	// tracks it separately).
	final int quantifiableCount;
	final int captureGroupCount;
	final ObjectIntMap<String> namedGroups;
	// \G has no construct: it only tells Matcher#find() to anchor at the previous match end.
	final boolean anchorsToPreviousMatchEnd;
	final boolean startsWithBeginAnchor;

	Ll1Pattern(
			String pattern,
			int flags,
			MatcherConstruct compiled,
			int quantifiableCount,
			int captureGroupCount,
			ObjectIntMap<String> namedGroups,
			boolean anchorsToPreviousMatchEnd,
			boolean startsWithBeginAnchor) {
		this.pattern = pattern;
		this.flags = flags;
		this.compiled = compiled;
		this.quantifiableCount = quantifiableCount;
		this.captureGroupCount = captureGroupCount;
		// No defensive copy: the throwaway parser that built the map is discarded, so nothing else holds it.
		this.namedGroups = namedGroups;
		this.anchorsToPreviousMatchEnd = anchorsToPreviousMatchEnd;
		this.startsWithBeginAnchor = startsWithBeginAnchor;
	}

	/** Like java.util.regex.Pattern#asPredicate: true if a match is found anywhere in the input. */
	public Predicate<String> asPredicate() {
		return (input) -> matcher(input).find();
	}

	/** Like java.util.regex.Pattern#asMatchPredicate: true if the whole input matches. */
	public Predicate<String> asMatchPredicate() {
		return (input) -> matcher(input).matches();
	}

	/** Named group to its 1-based group number, unmodifiable (java.util.regex.Pattern#namedGroups). */
	public Map<String, Integer> namedGroups() {
		Map<String, Integer> result = new java.util.LinkedHashMap<>();
		// androidx's Kotlin forEach takes a Function2 returning Unit, so the lambda returns Unit.INSTANCE.
		namedGroups.forEach((name, index) -> {
			// namedGroups stores the 0-based capture index; the public numbering is 1-based.
			result.put(name, index + 1);
			return kotlin.Unit.INSTANCE;
		});
		return java.util.Collections.unmodifiableMap(result);
	}

	public int flags() {
		return flags;
	}

	public Matcher matcher(CharSequence input) {
		return new Matcher(this, input.toString());
	}

	public String pattern() {
		return pattern;
	}

	public String[] split(CharSequence input) {
		return split(input, 0);
	}

	public String[] split(CharSequence input, int limit) {
		return split(input, limit, false);
	}

	/** Like {@link #split(CharSequence)}, but each matched delimiter is also included in the result. */
	public String[] splitWithDelimiters(CharSequence input, int limit) {
		return split(input, limit, true);
	}

	private String[] split(CharSequence input, int limit, boolean withDelimiters) {
		// Same algorithm as java.util.regex.Pattern#split: a zero-width match at index 0 never produces a
		// leading empty string, and limit == 0 drops trailing empty strings.
		int index = 0;
		boolean matchLimited = limit > 0;
		ArrayList<String> matchList = new ArrayList<>();
		// Counts pieces excluding delimiters, which limit applies to.
		int pieceCount = 0;
		Matcher m = matcher(input);
		while (m.find()) {
			if (!matchLimited || pieceCount < limit - 1) {
				if (index == 0 && index == m.start() && m.start() == m.end()) {
					continue;
				}
				matchList.add(input.subSequence(index, m.start()).toString());
				index = m.end();
				if (withDelimiters) {
					matchList.add(input.subSequence(m.start(), index).toString());
				}
				pieceCount++;
			} else if (pieceCount == limit - 1) {
				matchList.add(input.subSequence(index, input.length()).toString());
				index = m.end();
				pieceCount++;
			}
		}
		if (index == 0) {
			return new String[] {input.toString()};
		}
		if (!matchLimited || pieceCount < limit) {
			matchList.add(input.subSequence(index, input.length()).toString());
		}
		int resultSize = matchList.size();
		if (limit == 0) {
			while (resultSize > 0 && matchList.get(resultSize - 1).isEmpty()) {
				resultSize--;
			}
		}
		return matchList.subList(0, resultSize).toArray(new String[0]);
	}

	public Stream<String> splitAsStream(CharSequence input) {
		return Arrays.stream(split(input, 0));
	}

	public String toString() {
		return pattern;
	}
}

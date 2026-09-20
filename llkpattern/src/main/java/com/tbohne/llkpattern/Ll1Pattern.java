package com.tbohne.llkpattern;

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
 *   <li>(?idmsuxU) and (?idmsux:...) cannot be used to turn flags on and off</li>
 * </ul>
 * These restrictions make writing a regex more annoying, but the pattern can
 * be both compiled and matched in linear time. FAR faster than a full regex.
 * Note that since this restriction would effectively make "*" useless, this code
 * simply makes it match everything that would otherwise be valid.
 */
public final class Ll1Pattern {
	public static final int CANON_EQ = Pattern.CANON_EQ;
	public static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;
	public static final int COMMENTS = Pattern.COMMENTS;
	public static final int DOTALL = Pattern.DOTALL;
	public static final int LITERAL = Pattern.LITERAL;
	public static final int MULTILINE = Pattern.MULTILINE;
	public static final int UNICODE_CASE = Pattern.UNICODE_CASE;
	public static final int UNICODE_CHARACTER_CLASS = Pattern.UNICODE_CHARACTER_CLASS;
	public static final int UNIX_LINES = Pattern.UNIX_LINES;

	public static Ll1Pattern compile(String pattern) {
		return compile(pattern, 0);
	}

	public static Ll1Pattern compile(String pattern, int flags) {
		PatternParser parser = new PatternParser(pattern, flags);
		PatternConstruct parsed = parser.parse();
		MatcherConstruct compiled;
		try {
			compiled = parsed.compile(new PatternConstruct.EndConstruct(parsed.endIndex));
		} catch (PatternConstruct.EntryPointCycleException e) {
			// See design.md's "Entry-point computation vs. matcher compilation" section: this fires
			// only for a quantified construct whose entire body can match zero characters (e.g.
			// "(a?)+"), which also makes it an infinite-loop hazard in its own right.
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
		// PatternParser#parse() unwraps a single-alternative root, so that's a bare Sequence here.
		if (!(parsed instanceof PatternConstruct.Sequence)) {
			return false;
		}
		PatternConstruct first = ((PatternConstruct.Sequence) parsed).patterns.get(0);
		if (first instanceof PatternConstruct.BoundaryConstruct) {
			return ((PatternConstruct.BoundaryConstruct) first).type
					== PatternConstruct.BoundaryConstruct.BoundaryEnum.InputBegin;
		}
		return first instanceof PatternConstruct.LineBoundaryConstruct
				&& ((PatternConstruct.LineBoundaryConstruct) first).isLineBegin
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
	// Sizes for the per-match scratch arrays a Matcher needs -- see Matcher#quantifiableCounts /
	// Matcher#captureGroups. captureGroupCount doesn't include implicit group 0 (the whole match),
	// which Matcher tracks separately (matchStart/matchEnd).
	final int quantifiableCount;
	final int captureGroupCount;
	final Map<String, Integer> namedGroups;
	// \G doesn't match any specific position, so it has no MatcherConstruct representation at all
	// -- it's purely a flag telling Matcher#find() to anchor to exactly where the previous match
	// ended (Matcher#matchEnd), rather than scanning forward for a later match. See
	// PatternParser#anchorsToPreviousMatchEnd's doc for the full rationale.
	final boolean anchorsToPreviousMatchEnd;
	final boolean startsWithBeginAnchor;

	Ll1Pattern(
			String pattern,
			int flags,
			MatcherConstruct compiled,
			int quantifiableCount,
			int captureGroupCount,
			Map<String, Integer> namedGroups,
			boolean anchorsToPreviousMatchEnd,
			boolean startsWithBeginAnchor) {
		this.pattern = pattern;
		this.flags = flags;
		this.compiled = compiled;
		this.quantifiableCount = quantifiableCount;
		this.captureGroupCount = captureGroupCount;
		// Not Collections.unmodifiableMap: `namedGroups` is package-private, and the only caller
		// (PatternParser.getNamedGroups(), in Ll1Pattern.compile() above) hands over its own live
		// HashMap right as the throwaway parser instance that built it is discarded -- nothing ever
		// holds a mutable reference to it afterward, so the wrapper bought no real safety, just an
		// allocation on every compile().
		this.namedGroups = namedGroups;
		this.anchorsToPreviousMatchEnd = anchorsToPreviousMatchEnd;
		this.startsWithBeginAnchor = startsWithBeginAnchor;
	}

	public Predicate<String> asPredicate() {
		return (input) -> matcher(input).matches();
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
		// Same algorithm as java.util.regex.Pattern#split: a zero-width match at index 0 never produces a
		// leading empty string, and limit == 0 drops trailing empty strings.
		int index = 0;
		boolean matchLimited = limit > 0;
		ArrayList<String> matchList = new ArrayList<>();
		Matcher m = matcher(input);
		while (m.find()) {
			if (!matchLimited || matchList.size() < limit - 1) {
				if (index == 0 && index == m.start() && m.start() == m.end()) {
					continue;
				}
				matchList.add(input.subSequence(index, m.start()).toString());
				index = m.end();
			} else if (matchList.size() == limit - 1) {
				matchList.add(input.subSequence(index, input.length()).toString());
				index = m.end();
			}
		}
		if (index == 0) {
			return new String[] {input.toString()};
		}
		if (!matchLimited || matchList.size() < limit) {
			matchList.add(input.subSequence(index, input.length()).toString());
		}
		int resultSize = matchList.size();
		if (limit == 0) {
			while (resultSize > 0 && matchList.get(resultSize - 1).isEmpty()) {
				resultSize--;
			}
		}
		return matchList.subList(0, resultSize).toArray(new String[resultSize]);
	}

	public Stream<String> splitAsStream(CharSequence input) {
		return Arrays.stream(split(input, 0));
	}

	public String toString() {
		return pattern;
	}
}

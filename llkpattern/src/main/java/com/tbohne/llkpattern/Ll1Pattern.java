package com.tbohne.llkpattern;

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
		MatcherConstruct compiled = parsed.compile(new PatternConstruct.EndConstruct(parsed.endIndex));
		return new Ll1Pattern(pattern, flags, compiled);
	}

	public static boolean matches(String regex, CharSequence input) {
		return compile(regex).matcher(input).matches();
	}

	public static String quote(String s) {
		throw new UnsupportedOperationException("TODO: implement Ll1Pattern#quote");
	}

	private final String pattern;
	private final int flags;
	private final MatcherConstruct compiled;

	Ll1Pattern(String pattern, int flags, MatcherConstruct compiled) {
		this.pattern = pattern;
		this.flags = flags;
		this.compiled = compiled;
	}

	public Predicate<String> asPredicate() {
		return (input) -> matcher(input).matches();
	}

	public int flags() {
		return flags;
	}

	public Matcher matcher(CharSequence input) {
		throw new UnsupportedOperationException("TODO: implement Ll1Pattern#matcher");
	}

	public String pattern() {
		return pattern;
	}

	public String[] split(CharSequence input) {
		return split(input, Integer.MAX_VALUE);
	}

	public String[] split(CharSequence input, int limit) {
		throw new UnsupportedOperationException("TODO: implement Ll1Pattern#split");
	}

	public Stream<String> splitAsStream(CharSequence input) {
		throw new UnsupportedOperationException("TODO: implement Ll1Pattern#split");
	}

	public String toString() {
		return pattern;
	}
}
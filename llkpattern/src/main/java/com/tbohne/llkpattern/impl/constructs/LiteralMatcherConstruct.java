package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.UnicodeFlags;
import com.tbohne.llkpattern.Matcher;



/**
 * Matches a fixed literal string, then advances. The whole {@code value} is compared in one call
 * because {@code String#regionMatches} is a JIT intrinsic, simpler and faster than a
 * per-code-point loop.
 *
 * <p>Case-insensitivity needs two strategies: {@code regionMatches(true, ...)} is full Unicode
 * case folding, which is exactly {@code UNICODE_CASE}, but plain {@code CASE_INSENSITIVE} is
 * ASCII-only (see {@link #foldAsciiUpper}) and uses a per-{@code char} loop instead. Comparing a
 * surrogate pair as two chars is still correct there, since ASCII folding never touches non-ASCII.
 */
public final class LiteralMatcherConstruct extends MatcherConstruct {
	// A real String, not LiteralPatternConstruct's possibly zero-copy CharBuffer view: converted
	// once per compile so every match attempt gets the regionMatches intrinsic and direct
	// charAt/length (a CharSequence here measurably cost match-time CPU on Android).
	public final String value;

	LiteralMatcherConstruct(PatternConstruct owner, String value) {
		super(owner, owner.next().matcher());
		this.value = value;
	}

	boolean matchBody(Matcher matcher, int peeked) {
		int end = matcher.pos + value.length();
		if (end > matcher.regionEnd) {
			// hitEnd only if the remaining input agrees with value so far (as java.util.regex's Slice).
			if (remainingInputIsPrefixOfValue(matcher)) {
				matcher.hitEnd = true;
			}
			return false;
		}
		boolean matches;
		if ((flags & UnicodeFlags.CASE_INSENSITIVE) == 0) {
			matches = matcher.input.regionMatches(matcher.pos, value, 0, value.length());
		} else if ((flags & UnicodeFlags.UNICODE_CASE) != 0) {
			matches = matcher.input.regionMatches(true, matcher.pos, value, 0, value.length());
		} else {
			matches = asciiFoldRegionMatches(matcher.input, matcher.pos, value, value.length());
		}
		if (!matches) {
			return false;
		}
		if (end < matcher.regionEnd
				&& Character.isHighSurrogate(value.charAt(value.length() - 1))
				&& Character.isLowSurrogate(matcher.input.charAt(end))) {
			// value ends on an unpaired high surrogate but the input continues with a low surrogate:
			// the input's code point there is the combined supplementary one, so this is no match.
			// Elsewhere raw units agree only if their surrogate pairing agrees too.
			return false;
		}
		return next.match(matcher, matcher.consumeCodeUnits(value.length()));
	}

	private boolean remainingInputIsPrefixOfValue(Matcher matcher) {
		int available = matcher.regionEnd - matcher.pos;
		if ((flags & UnicodeFlags.CASE_INSENSITIVE) == 0) {
			return matcher.input.regionMatches(matcher.pos, value, 0, available);
		} else if ((flags & UnicodeFlags.UNICODE_CASE) != 0) {
			return matcher.input.regionMatches(true, matcher.pos, value, 0, available);
		}
		return asciiFoldRegionMatches(matcher.input, matcher.pos, value, available);
	}

	private static boolean asciiFoldRegionMatches(String input, int offset, String value, int len) {
		for (int i = 0; i < len; i++) {
			char a = input.charAt(offset + i);
			char b = value.charAt(i);
			if (a != b && foldAsciiUpper(a) != foldAsciiUpper(b)) {
				return false;
			}
		}
		return true;
	}
}

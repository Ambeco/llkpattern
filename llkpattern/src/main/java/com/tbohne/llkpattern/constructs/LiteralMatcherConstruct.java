package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;



public final class LiteralMatcherConstruct extends MatcherConstruct {
	// A real String, not the CharSequence LiteralPatternConstruct.value itself may be (a zero-copy
	// CharBuffer view, for a literal run PatternParser could read straight off the pattern
	// text -- see that field's own doc): LiteralPatternConstruct.buildMatcher() calls value.toString()
	// once per compile to get here, deliberately, so match() below -- called once per match
	// *attempt*, not once per compile -- can use String#regionMatches, a real JIT intrinsic
	// (vectorized comparison), plus String#charAt/length's direct field/array reads. A
	// CharSequence-typed `value` here once meant a hand-written per-char loop instead (no
	// intrinsic) for every case below, which measurably cost real match-time CPU on Android
	// (java.nio.CharBuffer's own charAt/length aren't free either) for a win that only ever
	// existed at compile time -- not worth paying for on every match attempt afterward.
	public final String value;

	LiteralMatcherConstruct(PatternConstruct owner, String value) {
		super(owner, owner.next().matcher());
		this.value = value;
	}

	boolean matchBody(Matcher matcher, int peeked) {
		int end = matcher.pos + value.length();
		if (end > matcher.regionEnd) {
			// Only a hit-end if the input that IS left agrees with value so far -- a mismatch
			// before the end never reads that far (java.util.regex's Slice behaves the same).
			if (remainingInputIsPrefixOfValue(matcher)) {
				matcher.hitEnd = true;
			}
			return false;
		}
		boolean matches;
		if ((flags & Ll1Pattern.CASE_INSENSITIVE) == 0) {
			matches = matcher.input.regionMatches(matcher.pos, value, 0, value.length());
		} else if ((flags & Ll1Pattern.UNICODE_CASE) != 0) {
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
			// `value` ends on an unpaired high surrogate, but the input keeps going with a real
			// low surrogate right there -- the input's actual code point at this position is the
			// combined supplementary one, not the lone surrogate `value` means to match. Every
			// other position is safe (two positions' raw units can only agree if their
			// surrogate-pairing structure agrees too, since pairing is a pure function of the
			// unit values themselves); only right at `value`'s own end does the comparison above
			// stop looking one unit before it would matter.
			return false;
		}
		return next.match(matcher, matcher.consumeCodeUnits(value.length()));
	}

	private boolean remainingInputIsPrefixOfValue(Matcher matcher) {
		int available = matcher.regionEnd - matcher.pos;
		if ((flags & Ll1Pattern.CASE_INSENSITIVE) == 0) {
			return matcher.input.regionMatches(matcher.pos, value, 0, available);
		} else if ((flags & Ll1Pattern.UNICODE_CASE) != 0) {
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

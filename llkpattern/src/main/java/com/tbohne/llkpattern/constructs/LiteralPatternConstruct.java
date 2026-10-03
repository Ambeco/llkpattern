package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class LiteralPatternConstruct extends PatternConstruct {
	// A CharSequence, not a String: for a literal run PatternParser could decode verbatim from
	// the pattern text (no escapes, no COMMENTS-mode gaps), it's a zero-copy
	// java.nio.CharBuffer view of `pattern` rather than a materialized copy -- see
	// PatternParser.parseUnion's own doc for why (java.lang.String.subSequence/substring both
	// copy; CharBuffer.wrap doesn't).
	final CharSequence value;

	public LiteralPatternConstruct(int startIndex, int endIndex, CharSequence value) {
		super(startIndex, endIndex);
		this.value = value;
	}

	@Override
	boolean claimsEntryElse() {
		return false; // never sets entryElse -- see buildEntryMap.
	}

	@Override
	boolean needsEntryPointBeforeMatcher() {
		// buildMatcher() below reads nothing buildEntryMap() sets -- see the base class doc.
		return false;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		// A literal is the one leaf whose set isn't already folded (a class's is, at parse time;
		// a named class is never folded), so it is folded here rather than in checkDisjoint.
		entryMap = MatcherConstruct.foldedEntrySet(singletonCodePointMap(Character.codePointAt(value, 0)), flags);
	}

	@Override
	void buildMatcher() {
		// value.toString() here, not value directly: LiteralMatcherConstruct wants a real String
		// (String#regionMatches is a JIT intrinsic -- real vectorized comparison -- and
		// String#charAt/length are direct field/array reads; a CharBuffer's own versions of
		// those are neither, measurably so per this project's own Android CPU sampling once
		// tried -- see LiteralMatcherConstruct.value's own doc). This runs once per compile
		// (same as buildMatcher() itself), not once per match attempt, so it's the same
		// allocation this construct's value would have cost pre-CharBuffer if `value` is a
		// CharBuffer view here (the "pure" case -- see parseUnion's own doc); if `value` is
		// already a String (the "impure" case, escapes/COMMENTS-gaps), toString() is a free
		// no-op (String#toString() returns `this`).
		new LiteralMatcherConstruct(this, value.toString());
	}

	@Override
	final @Nullable CodePointSet lastCharSet() {
		if (value.length() == 0) {
			return null;
		}
		int cp = Character.codePointBefore(value, value.length());
		// Folded by this literal's OWN flags, same as buildEntryMap()'s entryMap -- a bare literal
		// (unlike a bracket-class member) isn't folded at parse time, so the raw written code point
		// alone would under-report what this literal could actually have matched under its own
		// CASE_INSENSITIVE. Every other firstCharSet()/lastCharSet() override already returns an
		// already-folded set (ComplexCharacterPatternConstruct's ranges are folded at parse time; a nested class's
		// or named class's isn't foldable at all under java.util.regex's own rules) -- this brings
		// LiteralPatternConstruct in line with that contract instead of being the one exception. See
		// BackReferencePatternConstruct.buildEntryMap's own doc for why this matters beyond \b/\B classification:
		// entrySet built from an under-reported firstCharSet() is a real match-time dispatch gate,
		// not just a compile-time approximation.
		return MatcherConstruct.foldedEntrySet(singletonCodePointMap(cp), flags);
	}

	@Override
	final @Nullable CodePointSet firstCharSet() {
		if (value.length() == 0) {
			return null;
		}
		return MatcherConstruct.foldedEntrySet(singletonCodePointMap(Character.codePointAt(value, 0)), flags);
	}

	@Override
	public final LookbehindPatternConstruct.@Nullable SingleCodePointBody resolveSingleCodePointBody() {
		if (Character.codePointCount(value, 0, value.length()) != 1) {
			return null;
		}
		// Folded, unlike lastCharSet's raw singleton: a literal's real match-time membership
		// (what this assertion must actually check) is the folded set under CASE_INSENSITIVE/
		// UNICODE_CASE, exactly like LiteralPatternConstruct.buildEntryMap's own entryMap.
		return new LookbehindPatternConstruct.SingleCodePointBody(MatcherConstruct.foldedEntrySet(singletonCodePointMap(Character.codePointAt(value, 0)), flags), -1);
	}
}

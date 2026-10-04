package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class LiteralPatternConstruct extends PatternConstruct {
	// A CharSequence, not a String: for a verbatim literal run it is a zero-copy CharBuffer view of `pattern`
	// (see PatternParser.parseUnion).
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
		// toString() once per compile: LiteralMatcherConstruct wants a real String for the regionMatches
		// intrinsic (see its `value`). Free when value is already a String.
		new LiteralMatcherConstruct(this, value.toString());
	}

	@Override
	final @Nullable CodePointSet lastCharSet() {
		if (value.length() == 0) {
			return null;
		}
		int cp = Character.codePointBefore(value, value.length());
		// Folded by this literal's OWN flags, like buildEntryMap: a bare literal isn't folded at parse time, and
		// an under-reported set becomes a real match-time dispatch gate (see
		// BackReferencePatternConstruct.buildEntryMap).
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
		// Folded: the real match-time membership under CASE_INSENSITIVE/UNICODE_CASE.
		return new LookbehindPatternConstruct.SingleCodePointBody(MatcherConstruct.foldedEntrySet(singletonCodePointMap(Character.codePointAt(value, 0)), flags), -1);
	}
}

package com.tbohne.llkpattern;

import com.tbohne.llkpattern.NamedCharClass.*;

final class BackReference extends PatternConstruct {
	final int captureConstructIndex;
	final QuantifiedUnion referencedGroup;

	BackReference(int startIndex, int endIndex, int captureConstructIndex, QuantifiedUnion referencedGroup) {
		super(startIndex, endIndex);
		this.captureConstructIndex = captureConstructIndex;
		this.referencedGroup = referencedGroup;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		CodePointSet firstChars = referencedGroup.firstCharSet();
		if (firstChars == null) {
			// Possibly-empty (e.g. "(a*)\1") or otherwise not-statically-known referenced group --
			// fall back to the catch-all entry set rather than risk silently wrong zero-width
			// handling. See design.md's "Backreferences" section.
			entryElse = this;
			return;
		}
		// Aliased directly -- firstCharSet() already returns a plain CodePointSet (often itself an
		// alias, e.g. straight through to a ComplexCharacter's own validRanges()), so there's no
		// identity to lose by sharing it instead of copying its entries.
		// Two layers of folding, not one: `firstChars` is already folded by the referenced group's
		// OWN flags (e.g. under "(?i)(a)", the group could have literally captured 'A', not just
		// 'a' -- see LiteralString#firstCharSet's own doc), since that's what the group's content
		// could actually have consumed at match time, independent of what follows it. This
		// method's own `foldedEntrySet` call then folds THAT by the backreference's own flags,
		// since the backreference itself compares case-insensitively (codePointsMatch) according
		// to ITS OWN flags, whatever the referenced group's own flags were -- e.g. "(?-i)(a)(?i)\1"
		// must accept 'A' too, even though the group itself never could have captured it.
		entryMap = MatcherConstruct.foldedEntrySet(firstChars, flags);
	}

	@Override
	void buildMatcher() {
		new BackReferenceMatcherConstruct(this, captureConstructIndex);
	}
}

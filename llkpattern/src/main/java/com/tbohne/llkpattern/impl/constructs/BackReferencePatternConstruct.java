package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

/**
 * {@code \1}/{@code \k<name>}. {@code referencedGroup} is resolved at parse time to the
 * already-parsed group; forward references and undefined groups are rejected there. See
 * design.md's "Backreferences".
 */
public final class BackReferencePatternConstruct extends PatternConstruct {
	final int captureConstructIndex;
	private final QuantifiedUnionPatternConstruct referencedGroup;

	public BackReferencePatternConstruct(int startIndex, int endIndex, int captureConstructIndex, QuantifiedUnionPatternConstruct referencedGroup) {
		super(startIndex, endIndex);
		this.captureConstructIndex = captureConstructIndex;
		this.referencedGroup = referencedGroup;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		CodePointSet firstChars = referencedGroup.firstCharSet();
		if (firstChars == null) {
			// Possibly empty (e.g. "(a*)\1") or not statically known: fall back to the catch-all
			// rather than risk wrong zero-width handling.
			entryElse = this;
			return;
		}
		// Folded twice: firstChars already carries the referenced group's own flags (under
		// "(?i)(a)" it may have captured 'A'), and the backreference compares by ITS flags, so
		// "(?-i)(a)(?i)\1" must accept 'A' too.
		entryMap = MatcherConstruct.foldedEntrySet(firstChars, flags);
	}

	@Override
	void buildMatcher() {
		new BackReferenceMatcherConstruct(this, captureConstructIndex);
	}
}

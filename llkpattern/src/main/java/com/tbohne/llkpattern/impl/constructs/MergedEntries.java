package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.CodePointSet.MutableCodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Result of {@code PatternConstruct#mergeEntryPoints}. {@code ranges} is a plain {@code
 * CodePointSet}, never construct-valued: the merge projects its transient multi-valued map (kept
 * only to run the ambiguity check) down to this, so no caller can alias a construct-valued map as
 * an ancestor's {@code entryMap} (see that field's doc).
 */
final class MergedEntries {
	// Not Mutable: the single-candidate fast path aliases that candidate's own entryMap.
	final CodePointSet ranges;
	// The candidate that claimed "matches any other character" (at most one may).
	final @Nullable PatternConstruct elseCandidate;

	MergedEntries(CodePointSet ranges, @Nullable PatternConstruct elseCandidate) {
		this.ranges = ranges;
		this.elseCandidate = elseCandidate;
	}

	@Nullable PatternConstruct entryElse() {
		return elseCandidate != null ? elseCandidate.getEntryElse() : null;
	}
}

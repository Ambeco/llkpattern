package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.CodePointSet.MutableCodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

final class MergedEntries {
	// Not MutableCodePointSet -- the candidates.size() == 1 fast path in mergeEntryPoints below
	// aliases that lone candidate's own (immutable-from-here) entryMap directly, with no
	// allocation of its own; only the real (>= 2 candidates) merge path actually builds a fresh
	// MutableCodePointSet to hand back here.
	final CodePointSet ranges;
	// Whichever candidate claimed "matches any other character" (at most one is allowed to).
	final @Nullable PatternConstruct elseCandidate;

	MergedEntries(CodePointSet ranges, @Nullable PatternConstruct elseCandidate) {
		this.ranges = ranges;
		this.elseCandidate = elseCandidate;
	}

	@Nullable PatternConstruct entryElse() {
		return elseCandidate != null ? elseCandidate.getEntryElse() : null;
	}
}

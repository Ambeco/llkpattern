package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class ComplexCharacterPatternConstruct
		extends PatternConstruct {
	// Effectively immutable: set once at construction from a set PatternParser finished building, so it is a
	// plain CodePointSet and a shared NamedCharClass/RegexCharacterClass constant needs no defensive copy.
	final CodePointSet ranges;
	// Set by the parser for `.`: it claims whatever its siblings don't (see elseIsResidual) instead of `ranges`,
	// which is then only its accept set: the match-time re-check and the ceiling on a loop body's residual gate.
	public boolean residualElse;

	public ComplexCharacterPatternConstruct(int startIndex, CodePointSet ranges) {
		super(startIndex);
		this.ranges = ranges;
	}

	public ComplexCharacterPatternConstruct(int startIndex, int endIndex, CodePointSet ranges) {
		super(startIndex, endIndex);
		this.ranges = ranges;
	}

	public ComplexCharacterPatternConstruct(int startIndex, int character) {
		super(startIndex);
		this.ranges = singletonCodePointMap(character);
	}

	// SingleCharMatcherConstruct guards -1 ("no more input") itself, since an inverted set would otherwise
	// report it a member.
	CodePointSet validRanges() {
		return ranges;
	}

	@Override
	boolean claimsEntryElse() {
		return residualElse; // mirrors buildEntryMap's `entryElse = this` exactly.
	}

	@Override
	boolean elseIsResidual() {
		return residualElse;
	}

	@Override
	boolean needsEntryPointBeforeMatcher() {
		// buildMatcher() reads `ranges` directly, not entryMap.
		return false;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		// Aliased: a class's entry point IS its valid ranges.
		if (residualElse) {
			entryMap = EMPTY_ENTRY_MAP;
			entryElse = this;
		} else {
			entryMap = validRanges();
		}
	}

	@Override
	void buildMatcher() {
		new SingleCharMatcherConstruct(this);
	}

	@Override
	final @Nullable CodePointSet lastCharSet() {
		return validRanges();
	}

	@Override
	final @Nullable CodePointSet firstCharSet() {
		return validRanges();
	}

	@Override
	public final LookbehindPatternConstruct.@Nullable SingleCodePointBody resolveSingleCodePointBody() {
		return new LookbehindPatternConstruct.SingleCodePointBody(validRanges(), -1);
	}
}

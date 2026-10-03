package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class ComplexCharacterPatternConstruct
		extends PatternConstruct {
	// Effectively immutable once a ComplexCharacterPatternConstruct exists: every constructor below sets this
	// exactly once, from a set PatternParser finished building beforehand (see
	// PatternParser#parseComplexCharacter's own local `ranges` accumulator) -- so it's typed as
	// the plain (non-Mutable) CodePointSet here, and can be assigned directly from a
	// NamedCharClass/RegexCharacterClass static constant with no defensive copy, since nothing
	// past construction ever mutates it.
	final CodePointSet ranges;
	// Set once by the parser for `.`: instead of explicitly claiming `ranges` at dispatch time, this
	// character claims whatever its siblings don't (see elseIsResidual) -- `ranges` is then only its
	// own accept set: the match-time re-check, and the ceiling on a loop body's residual gate.
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

	/**
	 * {@code ranges} itself -- kept as a method (rather than exposing the field directly to every
	 * caller) since this used to also clamp to the code point domain before {@link CodePointMap}
	 * existed: Guava {@code RangeSet#complement()} (negated classes via {@code [^...]}, {@code .},
	 * built-ins like {@code \D}/{@code \S}/{@code \W}) produced a mathematically unbounded
	 * result that could swallow {@code -1}, the sentinel {@code Matcher} uses for "no more input"
	 * (see {@code Matcher#peek}). {@link CodePointSet}'s {@link CodePointSet#complement} is
	 * always finite over {@code [0, MAX_CODE_POINT]} by construction (see its own doc), so no
	 * clamping is needed here any more -- {@link SingleCharMatcherConstruct} instead guards
	 * {@code -1} directly, since an inverted {@code ranges} would otherwise report it a "member"
	 * via the fill.
	 */
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
		// buildMatcher() below (new SingleCharMatcherConstruct(this)) reads `ranges` directly off
		// this instance, not entryMap -- see the base class doc.
		return false;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		// Aliased directly: a character class's own entry point IS exactly its own valid ranges,
		// not a separate copy of them -- entryMap and ranges/validRanges() were always meant to
		// hold identical content, so there's nothing to gain from keeping them as two objects.
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

package com.tbohne.llkpattern;

import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeMap;
import com.google.common.collect.TreeRangeSet;
import com.tbohne.llkpattern.MatcherConstruct.*;

import java.util.Map.Entry;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

abstract class PatternConstruct {
	static final RangeMap<Integer, PatternConstruct> EMPTY_MAP = TreeRangeMap.create();

	final int startIndex;
	int endIndex = -1;

	@MonotonicNonNull MatcherConstruct matcher;

	// The construct that comes after this one -- i.e. what compile() was last called with.
	// Recorded so that, once this construct appears as a value in some ancestor's entryMap, that
	// ancestor's MatcherConstruct-building code can rely on `entryMapValue.matcher` already being
	// set (this construct's own compile() already ran, tail-to-front) rather than needing to
	// thread the continuation through again.
	@MonotonicNonNull PatternConstruct next;

	// Which PatternConstruct handles each possible next code point, once this construct (and
	// anything it can trivially skip, e.g. an optional quantifier) has matched. Populated by
	// buildEntryMap(); consumed while compiling a containing QuantifiedUnion/Sequence to detect
	// ambiguous branches, and to build the MatcherConstruct graph.
	RangeMap<Integer, PatternConstruct> entryMap = TreeRangeMap.create();
	@MonotonicNonNull PatternConstruct entryElse;


	PatternConstruct(int startIndex) {
		this.startIndex = startIndex;
	}

	PatternConstruct(int startIndex, int endIndex) {
		this.startIndex = startIndex;
		this.endIndex = endIndex;
	}

	/**
	 * Compiles this construct (and, transitively, whatever it depends on) into a MatcherConstruct
	 * graph, returning the node that represents "start matching this construct here". See
	 * design.md's "The compile() algorithm and cycle handling" section.
	 *
	 * <p>Memoized on {@link #matcher}: if it's already set -- either because this exact construct
	 * was already compiled, or because a MatcherConstruct constructor further up the call stack
	 * already self-registered here to break a cycle -- this returns immediately without redoing
	 * (or re-entering) any work.
	 */
	@Nullable MatcherConstruct compile(PatternConstruct next) {
		if (matcher != null) {
			return matcher;
		}
		this.next = next;
		buildEntryMap(next);
		if (matcher == null) {
			// buildMatcher() constructs `new SomeMatcherConstruct(this, ...)`, whose constructor's
			// first act is `this.matcher = it` (see MatcherConstruct's class doc) -- so `matcher` is
			// set as a side effect of the call below, not by assigning its return value.
			buildMatcher();
		}
		return matcher;
	}

	abstract void buildEntryMap(PatternConstruct next);

	abstract void buildMatcher();

	static abstract class QuantifiableConstruct extends PatternConstruct {
		int min = 1;
		int max = 1;
		int quantifiableIndex = -1;

		@MonotonicNonNull MatcherConstruct endLoopMatcher;
		RangeMap<Integer, PatternConstruct> endLoopExitMap = TreeRangeMap.create();
		@MonotonicNonNull PatternConstruct endLoopExitElse;

		QuantifiableConstruct(int startIndex) {
			super(startIndex);
		}

		QuantifiableConstruct(int startIndex, int endIndex) {
			super(startIndex, endIndex);
		}

		/** True for a "plain" {@code {1,1}} construct -- i.e. no real repetition/optionality. */
		boolean isUnquantified() {
			return min == 1 && max == 1;
		}
	}

	static final class QuantifiedUnion extends QuantifiableConstruct {
		final String pattern;
		final int parentFlags;

		int captureConstructIndex = 0;
		String captureName = "";
		final List<PatternConstruct> constructs = new ArrayList<>();
		boolean tempFlags = false;

		QuantifiedUnion(String pattern, int startIndex, int parentFlags) {
			super(startIndex);
			this.pattern = pattern;
			this.parentFlags = parentFlags;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			if (!isUnquantified()) {
				// TODO(remaining_work.md "quantifier loop compilation"): a quantified group (e.g.
				// `(a|b)*`, `(a|b)?`, `(a|b){2,3}`) needs a LoopMatcherConstruct/EndLoopMatcherConstruct
				// pair and, for `min == 0`, this union's own entryMap must also fold in `next`'s entry
				// set (entering zero times). That's also where the real compile-time cycle (this
				// construct's body dispatching back to itself) is exercised -- see design.md.
				throw new UnsupportedOperationException(
						"TODO: quantified groups (a group followed by ?, *, +, or {n,m}) are not yet compiled");
			}

			// Compile every branch (tail-to-front relative to this union: each branch's "next" is
			// this union's own "next", since choosing a branch doesn't consume anything itself) and
			// merge their entry ranges, rejecting any two branches that could both match the same
			// next code point -- the core LL(1) restriction this whole library is built around.
			TreeCodePointMap<PatternConstruct> merged = new TreeCodePointMap<>();
			int elseBranchIndex = -1;
			for (int i = 0; i < constructs.size(); i++) {
				PatternConstruct construct = constructs.get(i);
				construct.compile(next);

				if (construct.entryElse != null) {
					if (entryElse != null) {
						PatternConstruct priorElse = constructs.get(elseBranchIndex);
						throw PatternSyntaxException.throwWithReferences(
								pattern,
								construct.startIndex,
								"union subpattern #", i + 1, " starting at index ", construct.startIndex,
								" allows any character, but subpattern #", elseBranchIndex + 1,
								" starting at index ", priorElse.startIndex,
								" also allows any character, which is ambiguous");
					}
					entryElse = construct.entryElse;
					elseBranchIndex = i;
				}

				TreeCodePointMap<PatternConstruct> branchMap = toCodePointMap(construct.entryMap);
				Entry<CodePointMap.Range, PatternConstruct> conflict = findFirstOverlap(merged, branchMap);
				if (conflict != null) {
					throw PatternSyntaxException.throwWithReferences(
							pattern,
							construct.startIndex,
							"union subpattern #", i + 1, " starting at index ", construct.startIndex,
							" accepts character(s) ",
							new PatternSyntaxException.CodePoint(conflict.getKey().min),
							"-",
							new PatternSyntaxException.CodePoint(conflict.getKey().max - 1),
							", but a prior subpattern in the same union already claims those, which is not allowed");
				}
				merged = (TreeCodePointMap<PatternConstruct>) merged.union(branchMap);
			}

			for (Entry<CodePointMap.Range, PatternConstruct> e : merged.entrySet()) {
				entryMap.put(Range.closedOpen(e.getKey().min, e.getKey().max), e.getValue());
			}
		}

		@Override
		void buildMatcher() {
			new DispatchMatcherConstruct(this);
		}

		/** Converts a Guava RangeMap (arbitrary bound types) into a CodePointMap ({@code [min,max)}). */
		private static TreeCodePointMap<PatternConstruct> toCodePointMap(RangeMap<Integer, PatternConstruct> rangeMap) {
			TreeCodePointMap<PatternConstruct> result = new TreeCodePointMap<>();
			for (Entry<Range<Integer>, PatternConstruct> e : rangeMap.asMapOfRanges().entrySet()) {
				Range<Integer> canon = e.getKey().canonical(DiscreteDomain.integers());
				result.put(canon.lowerEndpoint(), canon.upperEndpoint(), e.getValue());
			}
			return result;
		}

		/**
		 * Returns the first code point range present (with a different value) in both maps, or null
		 * if none overlap. {@code CodePointMap.intersectionRejectingConflicts} would detect the same
		 * thing, but throws immediately rather than letting us report which ranges/branches conflict.
		 */
		private static @Nullable Entry<CodePointMap.Range, PatternConstruct> findFirstOverlap(
				CodePointMap<PatternConstruct> merged, CodePointMap<PatternConstruct> branch) {
			for (Entry<CodePointMap.Range, PatternConstruct> branchEntry : branch.entrySet()) {
				for (Entry<CodePointMap.Range, PatternConstruct> mergedEntry : merged.entrySet()) {
					int loMax = Math.min(branchEntry.getKey().max, mergedEntry.getKey().max);
					int hiMin = Math.max(branchEntry.getKey().min, mergedEntry.getKey().min);
					if (hiMin < loMax) {
						return new CodePointMap.ImmutableEntry<>(
								new CodePointMap.Range(hiMin, loMax), mergedEntry.getValue());
					}
				}
			}
			return null;
		}
	}


	static final class Sequence extends PatternConstruct {
		final List<PatternConstruct> patterns = new ArrayList<>();

		Sequence(int startIndex) {
			super(startIndex);
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			// Compile tail-to-front: the last element's next is this sequence's own next, and each
			// earlier element's next is the element right after it (already compiled by the time we
			// get to it).
			PatternConstruct tail = next;
			for (int i = patterns.size() - 1; i >= 0; i--) {
				patterns.get(i).compile(tail);
				tail = patterns.get(i);
			}
			entryMap = patterns.get(0).entryMap;
			entryElse = patterns.get(0).entryElse;
		}

		@Override
		void buildMatcher() {
			// A Sequence has no matching behavior of its own -- it's exactly whatever its first
			// element compiled to (already compiled by buildEntryMap, above).
			matcher = patterns.get(0).matcher;
		}
	}

	static final class LiteralString extends PatternConstruct {
		final String value;

		LiteralString(int startIndex, int endIndex, String value) {
			super(startIndex, endIndex);
			this.value = value;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			entryMap.put(Range.singleton(value.codePointAt(0)), this);
		}

		@Override
		void buildMatcher() {
			new LiteralMatcherConstruct(this, value);
		}
	}

	static final class BackReference extends PatternConstruct {
		final @Nullable Integer id;
		final @Nullable String name;

		BackReference(int startIndex, int endIndex, @Nullable Integer id, @Nullable String name) {
			super(startIndex, endIndex);
			this.id = id;
			this.name = name;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			entryElse = this;
		}

		@Override
		void buildMatcher() {
			if (name != null) {
				new BackReferenceMatcherConstruct(this, name);
			} else {
				new BackReferenceMatcherConstruct(this, id);
			}
		}
	}

	static final class ComplexCharacter
			extends PatternConstruct {
		RangeSet<Integer> ranges = TreeRangeSet.create();
		@Nullable PatternConstruct dotElse;

		ComplexCharacter(int startIndex, RangeSet<Integer> ranges) {
			super(startIndex);
			this.ranges = ranges;
		}

		ComplexCharacter(int startIndex, int endIndex, RangeSet<Integer> ranges) {
			super(startIndex, endIndex);
			this.ranges = ranges;
		}

		ComplexCharacter(int startIndex, int endIndex, RangeSet<Integer> ranges, boolean positiveMatch) {
			super(startIndex, endIndex);
			this.ranges = ranges;
		}

		ComplexCharacter(int startIndex, int character) {
			super(startIndex);
			ranges.add(Range.singleton(character));
		}

		ComplexCharacter(int startIndex) {
			super(startIndex);
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			for (Range<Integer> range : ranges.asRanges()) {
				entryMap.put(range, this);
			}
			entryElse = dotElse;
		}

		@Override
		void buildMatcher() {
			new SingleCharMatcherConstruct(this);
		}
	}

	static final class ComplexQuantifiedCharacter extends QuantifiableConstruct {
		final ComplexCharacter delegate;

		ComplexQuantifiedCharacter(int startIndex, ComplexCharacter delegate) {
			super(startIndex, delegate.endIndex);
			this.delegate = delegate;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			if (!isUnquantified()) {
				// TODO(remaining_work.md "quantifier loop compilation"): same gap as
				// QuantifiedUnion's -- a quantified character class (`a*`, `a?`, `a{2,3}`, ...) needs
				// a real loop, not just a pass-through.
				throw new UnsupportedOperationException(
						"TODO: quantified character classes (?, *, +, or {n,m} on a character or class) are not yet compiled");
			}
			delegate.compile(next);
			for (Range<Integer> range : delegate.ranges.asRanges()) {
				entryMap.put(range, this);
			}
		}

		@Override
		void buildMatcher() {
			// Unquantified (i.e. exactly-once) case: this construct behaves exactly like its
			// delegate ComplexCharacter (already compiled by buildEntryMap, above).
			matcher = delegate.matcher;
		}
	}

	static final class BoundaryConstruct extends PatternConstruct {
		enum BoundaryEnum {
			LineBegin,
			LineEnd,
			Word,
			NonWord,
			InputBegin,
			PreviousMatchEnd,
			InputEndExceptTerminator,
			InputEnd,
			Linebreak
		}

		final BoundaryEnum type;

		BoundaryConstruct(int startIndex, int endIndex, BoundaryEnum type) {
			super(startIndex, endIndex);
			this.type = type;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			entryElse = this;
		}

		@Override
		void buildMatcher() {
			new BoundaryMatcherConstruct(this, type);
		}
	}

	static final class EndConstruct extends PatternConstruct {

		EndConstruct(int startIndex) {
			super(startIndex);
			new EndMatcherConstruct(this);
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			// An EndConstruct has no "next" -- it's the sentinel marking the end of the whole pattern.
		}

		@Override
		void buildMatcher() {
			// matcher is already set by the constructor -- compile() never reaches this (see its
			// `if (matcher == null)` guard) but it's implemented for completeness/symmetry.
		}
	}
}

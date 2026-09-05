package com.tbohne.llkpattern;

import com.google.common.collect.BoundType;
import com.google.common.collect.ImmutableRangeMap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeMap;
import com.google.common.collect.TreeRangeSet;
import com.tbohne.llkpattern.MatcherConstruct.*;

import java.util.Map.Entry;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

abstract class PatternConstruct {
	static final RangeMap<Integer, PatternConstruct> EMPTY_MAP = TreeRangeMap.create();

	final int startIndex;
	int endIndex = -1;

	@MonotonicNonNull MatcherConstruct matcher;

	RangeMap<Integer, MatcherConstruct> exitMap = TreeRangeMap.create();
	@MonotonicNonNull MatcherConstruct exitElse;

	// Which PatternConstruct handles each possible next code point, once this construct (and
	// anything it can trivially skip, e.g. an optional quantifier) has matched. Populated by
	// buildEntryMap(); consumed while compiling a containing QuantifiedUnion/Sequence to detect
	// ambiguous branches, and eventually to drive building the MatcherConstruct graph.
	RangeMap<Integer, PatternConstruct> entryMap = TreeRangeMap.create();
	@MonotonicNonNull PatternConstruct entryElse;


	PatternConstruct(int startIndex) {
		this.startIndex = startIndex;
	}

	PatternConstruct(int startIndex, int endIndex) {
		this.startIndex = startIndex;
		this.endIndex = endIndex;
	}

	// TODO: this doesn't yet build a MatcherConstruct graph from the AST -- it only populates
	// entryMap/entryElse (see buildEntryMap) and returns whatever `matcher` already happens to be
	// set to (only EndConstruct sets it today). Finishing this is tracked in remaining_work.md.
	@Nullable MatcherConstruct compile(PatternConstruct next) {
		if (entryMap.equals(EMPTY_MAP)) {
			buildEntryMap(next);
		}
		return matcher;
	}

	abstract void buildEntryMap(PatternConstruct next);

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
			// TODO(https://github.com/Ambeco/llpattern remaining_work.md "ambiguity-detection algorithm"):
			// This needs to walk `constructs`, compile each one against `next`, and merge their entry
			// ranges into this union's entryMap/entryElse, throwing a PatternSyntaxException with a
			// helpful message the first time two branches' entry ranges overlap (or two branches both
			// try to claim "else"). The previous attempt at this (see git history) didn't compile and
			// is being redone now that CodePointMap/TreeCodePointMap's merge-conflict detection
			// (CodePointMap#intersectionRejectingConflicts) exists to build this on top of, rather than
			// hand-rolling Guava RangeMap overlap bookkeeping here.
			throw new UnsupportedOperationException("TODO: QuantifiedUnion.buildEntryMap not yet implemented");
		}

		BiFunction<PatternConstruct, PatternConstruct, PatternConstruct> rejectAmbiguous(int thisIndex,
																																										 Map.Entry<Range<Integer>,	PatternConstruct> entry) {
			return (first, second) -> {
				RangeMap<Integer, PatternConstruct> overlapMap = entryMap.subRangeMap(entry.getKey());
				Range<Integer> range = overlapMap.asMapOfRanges().keySet().iterator().next();
				char rangeStart = range.lowerBoundType() == BoundType.CLOSED ? '(' : '[';
				char rangeEnd = range.upperBoundType() == BoundType.CLOSED ? ')' : ']';
				PatternConstruct other = overlapMap.get(+rangeStart);
				// TODO: Narrow down the references to only the first character
				throw PatternSyntaxException.throwWithReferences(
						pattern,
						second.startIndex,
						"union subpattern #",
						thisIndex+1,
						" starts with \"",
						new PatternSyntaxException.Reference(first.startIndex, first.endIndex),
						"\" which accepts characters in the range of ",
						rangeStart,
						new PatternSyntaxException.CodePoint(entry.getKey().lowerEndpoint()),
						"-",
						new PatternSyntaxException.CodePoint(entry.getKey().upperEndpoint()),
						rangeEnd,
						", but a prior subpattern that starts with \"",
						new PatternSyntaxException.Reference(second.startIndex, second.endIndex),
						"\", matches those same characters, which is not allowed.");
			};
		}
	}


	static final class Sequence extends PatternConstruct {
		final List<PatternConstruct> patterns = new ArrayList<>();

		Sequence(int startIndex) {
			super(startIndex);
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			// TODO: each pattern in the sequence should be compiled against *the following* pattern
			// (or `next` for the last one), not all against `next` -- this is a placeholder wiring so
			// the module compiles while the matcher-graph work in remaining_work.md is finished.
			for (int i = 0; i < patterns.size(); i++) {
				patterns.get(i).buildEntryMap(next);
			}
			entryMap = patterns.get(0).entryMap;
			entryElse = patterns.get(0).entryElse;
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
	}

	static final class ComplexQuantifiedCharacter extends QuantifiableConstruct {
		final ComplexCharacter delegate;

		ComplexQuantifiedCharacter(int startIndex, ComplexCharacter delegate) {
			super(startIndex, delegate.endIndex);
			this.delegate = delegate;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			delegate.buildEntryMap(next);
			for (Range<Integer> range : delegate.ranges.asRanges()) {
				entryMap.put(range, this);
			}
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
	}
	
	static final class EndConstruct extends PatternConstruct {

		EndConstruct(int startIndex) {
			super(startIndex);
			matcher = EndMatcherConstruct.instance;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			// An EndConstruct has no "next" -- it's the sentinel marking the end of the whole pattern.
		}

		@Override MatcherConstruct compile(PatternConstruct next) {
			return matcher;
		}
	}
}

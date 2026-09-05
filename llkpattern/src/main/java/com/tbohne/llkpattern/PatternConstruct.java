package com.tbohne.llkpattern;

import com.google.common.collect.BoundType;
import com.google.common.collect.ImmutableRangeMap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeMap;
import com.google.common.collect.TreeRangeSet;
import com.sun.org.apache.xerces.internal.impl.xpath.regex.Match;
import com.tbohne.llkpattern.MatcherConstruct.*;

import java.util.Map.Entry;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

abstract class PatternConstruct {
	static final RangeMap<Integer, MatcherConstruct> EMPTY_MAP = TreeRangeMap.create();

	final int startIndex;
	int endIndex = -1;

	@MonotonicNonNull MatcherConstruct matcher;

	RangeMap<Integer, MatcherConstruct> exitMap = TreeRangeMap.create();
	@MonotonicNonNull MatcherConstruct exitElse;

	RangeMap<Integer, MatcherConstruct> entryMap = TreeRangeMap.create();
	@MonotonicNonNull MatcherConstruct entryElse;


	PatternConstruct(int startIndex) {
		this.startIndex = startIndex;
	}

	PatternConstruct(int startIndex, int endIndex) {
		this.startIndex = startIndex;
		this.endIndex = endIndex;
	}

	void compile(PatternConstruct next) {
		if (entryMap.equals(EMPTY_MAP)) {
			buildEntryMap(next);
		}
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
			RangeMap<Integer, PatternConstruct> patternMap = TreeRangeMap.create();
			int elsePatternIndex = -1;
			for (int i = 0; i < constructs.size(); i++) {
				PatternConstruct construct = constructs.get(i);
				construct.compile(next);
        if (construct.entryElse != null) {
          if (entryElse != null) {
						PatternConstruct priorElseConstruct = constructs.get(elsePatternIndex);
            throw PatternSyntaxException.throwWithReferences(
                pattern,
                construct.startIndex,
                "union subpattern #",
                i + 1,
                " starts with \"",
                new PatternSyntaxException.Reference(construct.startIndex, construct.endIndex),
                "\" which allows any character, but subpattern #",
								elsePatternIndex + 1,
								" starts with \"",
								new PatternSyntaxException.Reference(priorElseConstruct.startIndex, priorElseConstruct.endIndex),
								"\" which allows any character, which is ambiguous");
          }
					elsePatternIndex = i;
          entryElse = construct.entryElse;
				}
				for (Map.Entry<Range<Integer>, MatcherConstruct> entry : construct.entryMap.asMapOfRanges().entrySet()) {
					RangeMap<Integer, MatcherConstruct> overlap = entryMap.subRangeMap(entry.getKey());
					if (overlap != EMPTY_MAP) {
						StringBuilder overlappingCharactters = new StringBuilder();
						Set<Range<Integer>> overlapMap = overlap.asMapOfRanges().keySet();
						Range<Integer> firstOverlap = overlapMap.iterator().next();
						if (firstOverlap.lowerEndpoint().equals(firstOverlap.upperEndpoint())) {
							overlappingCharactters.append("'")
									.appendCodePoint(firstOverlap.lowerEndpoint())
									.append("' (U+")
									.append(String.format("%04x", firstOverlap.lowerEndpoint()))
									.append(")");
						} else {

						}
						if (overlapMap.size() == 1 && overlapMap.entrySet().)
						for (Map.Entry<Range<Integer>, MatcherConstruct> overlapEntry : .entrySet()) {
							if (overlapEntry.getKey())
						}
						if (entryMapRanges.size() == 1 && entryMapRanges.)
						throw PatternSyntaxException.throwWithReferences(
								pattern,
								construct.startIndex,
								"union subpattern #",
								i + 1,
								" starts with \"",
								new PatternSyntaxException.Reference(construct.startIndex, construct.endIndex),
								"\" which allows any character, but subpattern #",
								elsePatternIndex + 1,
								" starts with \"",
								new PatternSyntaxException.Reference(priorElseConstruct.startIndex, priorElseConstruct.endIndex),
								"\" which allows any character, which is ambiguous");
					}
					if (entryMap.)
					entryMap.merge(entry.getKey(), entry.getValue(), ());
				}
				Map<Range<Integer>, PatternConstruct> constructEntryMap = construct.entryMap.asMapOfRanges();
				if (constructEntryMap.isEmpty()) {
					if (entryElse == null) {
						entryElse = this;
						entryElseIndex = i;
					} else {
						// TODO: Narrow down the references to only the first character
					}
				} else { //map is not empty
					for (Map.Entry<Range<Integer>, PatternConstruct> entry : constructEntryMap.entrySet()) {
						entryMap.merge(entry.getKey(), entry.getValue(), rejectAmbiguous(i, entry));
					}
				}
			}
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
			for (int i=0; i<patterns.size(); i++) {
				patterns.get(i).buildEntryMap();
			}
			entryMap = patterns.get(0).entryMap;
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
			delegate.buildEntryMap();
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

		}

		@Override void compile(PatternConstruct next) {
			throw new NotImplementedException();
		}
	}
}

package com.tbohne.llkpattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableRangeMap;
import com.tbohne.llkpattern.Matcher.Group;
import com.tbohne.llkpattern.PatternConstruct.BoundaryConstruct.BoundaryEnum;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

abstract class MatcherConstruct {
	final ImmutableRangeMap<Integer, MatcherConstruct> dispatchMap;
	final @Nullable MatcherConstruct elseDispatch;

	MatcherConstruct(ImmutableRangeMap<Integer, MatcherConstruct> dispatchMap, @Nullable MatcherConstruct elseDispatch) {
		this.dispatchMap = dispatchMap;
		this.elseDispatch = elseDispatch;
	}

	abstract boolean match(Matcher matcher, int peeked);

	@Nullable MatcherConstruct getNext(Matcher matcher, int peeked) {
		MatcherConstruct mapped = dispatchMap.get(peeked);
		return (mapped != null) ? mapped : elseDispatch;
	}

	@VisibleForTesting
	ImmutableRangeMap<Integer, MatcherConstruct> getDispatchMap() { return dispatchMap; }

	@VisibleForTesting
	@Nullable MatcherConstruct getElse() { return elseDispatch; }

	static final class SingleCharMatcherConstruct extends MatcherConstruct {
		final Integer value;
		SingleCharMatcherConstruct(Integer value, ImmutableRangeMap<Integer, MatcherConstruct> dispatchMap, @Nullable MatcherConstruct elseDispatch) {
			super(dispatchMap, elseDispatch);
			this.value = value;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, matcher.consume1CodePoint());
		}
	}

	static final class LiteralMatcherConstruct extends MatcherConstruct {
		final String value;

		LiteralMatcherConstruct(String value, ImmutableRangeMap<Integer, MatcherConstruct> dispatchMap, @Nullable MatcherConstruct elseDispatch) {
			super(dispatchMap, elseDispatch);
			this.value = value;
		}

		boolean match(Matcher matcher, int peeked) {
			int i=0;
			do {
				int next = value.codePointAt(i);
				if (next != peeked) {
					return false;
				}
				int units = Character.isSupplementaryCodePoint(i) ? 2 : 1;
				peeked = matcher.consumeCodeUnits(units);
				i += units;
			} while (i<value.length());
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, peeked);
		}
	}

	static final class BackReferenceMatcherConstruct extends MatcherConstruct {
		final @Nullable Integer id;
		final @Nullable String name;

		BackReferenceMatcherConstruct(int id, ImmutableRangeMap<Integer, MatcherConstruct> dispatchMap, @Nullable MatcherConstruct elseDispatch) {
			super(dispatchMap, elseDispatch);
			this.id = id;
			this.name = null;
		}

		BackReferenceMatcherConstruct(@NonNull String name) {
			super(ImmutableRangeMap.of(), null);
			this.id = null;
			this.name = name;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			throw new UnsupportedOperationException("TODO");
			//MatcherConstruct next = getNext(matcher, peeked);
			//return next != null && next.match(matcher, peeked);
		}
	}

	static final class BoundaryMatcherConstruct extends MatcherConstruct {
		final BoundaryEnum type;

		BoundaryMatcherConstruct(BoundaryEnum type, ImmutableRangeMap<Integer, MatcherConstruct> dispatchMap, @Nullable MatcherConstruct elseDispatch) {
			super(dispatchMap, elseDispatch);
			this.type = type;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			throw new UnsupportedOperationException("TODO");
			//MatcherConstruct next = getNext(matcher, peeked);
			//return next != null && next.match(matcher, peeked);
		}
	}

	static final class LoopMatcherConstruct extends MatcherConstruct {
		final int quantifiableIndex;
		final int max;

		LoopMatcherConstruct(int quantifiableIndex, int max, ImmutableRangeMap<Integer, MatcherConstruct> dispatchMap, @Nullable MatcherConstruct elseDispatch) {
			super(dispatchMap, elseDispatch);
			this.quantifiableIndex = quantifiableIndex;
			this.max = max;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			int loopCount = ++matcher.quantifiableCounts[quantifiableIndex];
			if (loopCount > max) {
				return false;
			}
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, peeked);
		}
	}

	static final class EndLoopMatcherConstruct extends MatcherConstruct {
		final int quantifiableIndex;
		final int min;

		EndLoopMatcherConstruct(int quantifiableIndex, int min, ImmutableRangeMap<Integer, MatcherConstruct> dispatchMap, @Nullable MatcherConstruct elseDispatch) {
			super(dispatchMap, elseDispatch);
			this.quantifiableIndex = quantifiableIndex;
			this.min = min;
		}

		boolean match(Matcher matcher, int peeked) {
			int loopCount = matcher.quantifiableCounts[quantifiableIndex];
			matcher.quantifiableCounts[quantifiableIndex] = 0;
			if (loopCount < min) {
				return false;
			}
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, peeked);
		}
	}

	static final class BeginCaptureMatcherConstruct extends MatcherConstruct {
		final int captureConstructIndex;
		final @Nullable String captureName;

		BeginCaptureMatcherConstruct(int captureConstructIndex, ImmutableRangeMap<Integer, MatcherConstruct> dispatchMap, @Nullable MatcherConstruct elseDispatch) {
			super(dispatchMap, elseDispatch);
			this.captureConstructIndex = captureConstructIndex;
			this.captureName = null;
		}

		BeginCaptureMatcherConstruct(@NonNull String captureName, ImmutableRangeMap<Integer, MatcherConstruct> dispatchMap, @Nullable MatcherConstruct elseDispatch) {
			super(dispatchMap, elseDispatch);
			this.captureConstructIndex = -1;
			this.captureName = captureName;
		}

		boolean match(Matcher matcher, int peeked) {
			Group group = new Group(matcher.pos);
			matcher.groups.add(group);
			matcher.currentCaptureGroupIdx[captureConstructIndex] =  matcher.groups.size();
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, peeked);
		}
	}

	static final class EndCaptureMatcherConstruct extends MatcherConstruct {
		final int captureConstructIndex;

		EndCaptureMatcherConstruct(int captureConstructIndex, ImmutableRangeMap<Integer, MatcherConstruct> dispatchMap, @Nullable MatcherConstruct elseDispatch) {
			super(dispatchMap, elseDispatch);
			this.captureConstructIndex = captureConstructIndex;
		}

		boolean match(Matcher matcher, int peeked) {
			int groupIdx = matcher.currentCaptureGroupIdx[captureConstructIndex];
			Group group = matcher.groups.get(groupIdx);
			group.result = matcher.input.substring(group.inputStartIndex, matcher.pos);
			matcher.currentCaptureGroupIdx[captureConstructIndex] = -1;
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, peeked);
		}
	}
	
	static final class EndMatcherConstruct extends MatcherConstruct {
		public static final EndMatcherConstruct instance = new EndMatcherConstruct();

		EndMatcherConstruct() {
			super(ImmutableRangeMap.of(), null);
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			return true;
		}
	}
}

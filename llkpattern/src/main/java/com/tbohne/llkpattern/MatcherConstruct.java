package com.tbohne.llkpattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import com.tbohne.llkpattern.Matcher.Group;
import com.tbohne.llkpattern.PatternConstruct.BoundaryConstruct.BoundaryEnum;
import com.tbohne.llkpattern.PatternConstruct.ComplexCharacter;
import com.tbohne.llkpattern.PatternConstruct.QuantifiedUnion;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A single compiled, executable step in the matcher graph. See design.md's "The compile()
 * algorithm and cycle handling" section for the full picture -- the short version:
 *
 * <p>Every {@link PatternConstruct} compiles to exactly one {@code MatcherConstruct}, referenced
 * by that construct's {@code matcher} field. A {@code MatcherConstruct}'s constructor's *first*
 * action (done here, in the base constructor) is to assign itself to its owning construct's
 * {@code matcher} field -- before anything else, including resolving the dependencies it needs
 * for its own {@link #dispatchMap}/{@link #elseDispatch}. That ordering is what makes a cyclic
 * PatternConstruct graph (a quantifier looping back on itself) safe to compile without infinite
 * recursion or a separate visited-set: a nested {@code PatternConstruct.compile(...)} call that
 * loops back to a construct already under construction sees its (still being filled in)
 * {@code matcher} and returns immediately instead of recursing.
 *
 * <p>{@link #dispatchMap}/{@link #elseDispatch} are intentionally not {@code final}: a `final`
 * field can only be safely published to other threads if it's set before the constructor
 * completes, but a self-referential dispatch entry is unavoidably written by a *different*
 * (nested) constructor call while this one is still running. Instead, every dispatch/build step
 * for an entire {@code Ll1Pattern} happens synchronously, before that pattern's own constructor
 * (which does have a `final` field) runs -- so the whole graph is safely published transitively
 * through {@code Ll1Pattern}'s `final compiled` field, even though the individual
 * {@code MatcherConstruct}s reachable from it are not `final` themselves. Treat these fields as
 * immutable *by contract* once construction of the whole graph is finished.
 */
abstract class MatcherConstruct {
	RangeMap<Integer, MatcherConstruct> dispatchMap = TreeRangeMap.create();
	@Nullable MatcherConstruct elseDispatch;

	/**
	 * @param owner the PatternConstruct this MatcherConstruct implements. Assigning {@code
	 *     owner.matcher = this} here, before subclass constructors resolve any dependencies, is
	 *     what breaks cycles -- see the class doc.
	 */
	MatcherConstruct(PatternConstruct owner) {
		owner.matcher = this;
	}

	abstract boolean match(Matcher matcher, int peeked);

	@Nullable MatcherConstruct getNext(Matcher matcher, int peeked) {
		MatcherConstruct mapped = dispatchMap.get(peeked);
		return (mapped != null) ? mapped : elseDispatch;
	}

	@VisibleForTesting
	RangeMap<Integer, MatcherConstruct> getDispatchMap() { return dispatchMap; }

	@VisibleForTesting
	@Nullable MatcherConstruct getElse() { return elseDispatch; }

	/**
	 * Matches exactly one code point against {@code ranges} (a character class -- {@code .}, a
	 * literal single character, or {@code [...]}), then dispatches to whatever comes next.
	 */
	static final class SingleCharMatcherConstruct extends MatcherConstruct {
		SingleCharMatcherConstruct(ComplexCharacter owner) {
			super(owner);
			MatcherConstruct target = owner.next.matcher;
			for (Range<Integer> range : owner.ranges.asRanges()) {
				dispatchMap.put(range, target);
			}
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, matcher.consume1CodePoint());
		}
	}

	/** Matches a fixed literal string exactly, then dispatches to whatever comes next. */
	static final class LiteralMatcherConstruct extends MatcherConstruct {
		final String value;

		LiteralMatcherConstruct(PatternConstruct owner, String value) {
			super(owner);
			this.value = value;
			dispatchMap.put(Range.singleton(value.codePointAt(0)), owner.next.matcher);
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

	/**
	 * Dispatches immediately based on the next code point, without consuming any input itself.
	 * This is what a {@code QuantifiedUnion}'s alternation (the {@code |} branches) compiles to:
	 * picking a branch doesn't consume a character, the chosen branch's own matcher does.
	 */
	static final class DispatchMatcherConstruct extends MatcherConstruct {
		DispatchMatcherConstruct(QuantifiedUnion owner) {
			super(owner);
			for (Map.Entry<Range<Integer>, PatternConstruct> e : owner.entryMap.asMapOfRanges().entrySet()) {
				dispatchMap.put(e.getKey(), e.getValue().matcher);
			}
			elseDispatch = owner.entryElse != null ? owner.entryElse.matcher : null;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, peeked);
		}
	}

	static final class BackReferenceMatcherConstruct extends MatcherConstruct {
		final @Nullable Integer id;
		final @Nullable String name;

		BackReferenceMatcherConstruct(PatternConstruct owner, int id) {
			super(owner);
			this.id = id;
			this.name = null;
		}

		BackReferenceMatcherConstruct(PatternConstruct owner, @NonNull String name) {
			super(owner);
			this.id = null;
			this.name = name;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			// TODO(remaining_work.md "Backreferences"): backreferences aren't context-free (see
			// PatternParser's grammar comment) -- this needs to look up the referenced group's
			// already-matched text on `matcher` and compare it against upcoming input.
			throw new UnsupportedOperationException("TODO: backreference matching not yet implemented");
		}
	}

	static final class BoundaryMatcherConstruct extends MatcherConstruct {
		final BoundaryEnum type;

		BoundaryMatcherConstruct(PatternConstruct owner, BoundaryEnum type) {
			super(owner);
			this.type = type;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			// TODO(remaining_work.md "Boundary matching"): per design.md, this is the one open
			// question in the matcher-graph shape -- a boundary depends on matcher state (position,
			// surrounding characters), not just the next code point, so it may not fit this
			// dispatch-map-node shape as cleanly as the other constructs do.
			throw new UnsupportedOperationException("TODO: boundary matching not yet implemented");
		}
	}

	static final class LoopMatcherConstruct extends MatcherConstruct {
		final int quantifiableIndex;
		final int max;

		LoopMatcherConstruct(PatternConstruct owner, int quantifiableIndex, int max) {
			super(owner);
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

		EndLoopMatcherConstruct(PatternConstruct owner, int quantifiableIndex, int min) {
			super(owner);
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

		BeginCaptureMatcherConstruct(PatternConstruct owner, int captureConstructIndex) {
			super(owner);
			this.captureConstructIndex = captureConstructIndex;
			this.captureName = null;
		}

		BeginCaptureMatcherConstruct(PatternConstruct owner, @NonNull String captureName) {
			super(owner);
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

		EndCaptureMatcherConstruct(PatternConstruct owner, int captureConstructIndex) {
			super(owner);
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
		EndMatcherConstruct(PatternConstruct.EndConstruct owner) {
			super(owner);
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			return true;
		}
	}
}

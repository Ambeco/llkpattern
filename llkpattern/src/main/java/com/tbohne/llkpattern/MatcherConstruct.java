package com.tbohne.llkpattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import com.tbohne.llkpattern.Matcher.Group;
import com.tbohne.llkpattern.PatternConstruct.BoundaryConstruct.BoundaryEnum;
import com.tbohne.llkpattern.PatternConstruct.ComplexCharacter;
import com.tbohne.llkpattern.PatternConstruct.QuantifiedUnion;
import java.util.ArrayList;
import java.util.List;
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

	/**
	 * For internal/synthetic nodes that aren't the externally-visible entry point of any single
	 * PatternConstruct -- e.g. {@link LoopMatcherConstruct}/{@link EndLoopMatcherConstruct} (both
	 * only ever reached via a {@link LoopDispatchMatcherConstruct}'s own dispatch map), or the
	 * plain alternation dispatch wrapped inside a capturing group's Begin/EndCapture pair. Skips
	 * self-registration since there's no single owning construct to register into.
	 */
	MatcherConstruct() {}

	abstract boolean match(Matcher matcher, int peeked);

	@Nullable MatcherConstruct getNext(Matcher matcher, int peeked) {
		MatcherConstruct mapped = dispatchMap.get(peeked);
		if (mapped == null && peeked != -1) {
			// CASE_INSENSITIVE/UNICODE_CASE (2026-09-06): dispatchMap's keys are exactly the code
			// points the pattern was written with (e.g. "[a-z]" only ever puts 'a'-'z' in the map),
			// so a case-insensitive match has to try the *input* character's other-case forms
			// against that same map, rather than expanding every character class's ranges at compile
			// time. Two lookups (not one) because there's no single "canonical case" that works for
			// both an all-lowercase pattern matching an uppercase input and vice versa.
			int flags = matcher.pattern.flags();
			if ((flags & Ll1Pattern.CASE_INSENSITIVE) != 0) {
				boolean unicode = (flags & Ll1Pattern.UNICODE_CASE) != 0;
				int upper = unicode ? Character.toUpperCase(peeked) : foldAsciiUpper(peeked);
				int lower = unicode ? Character.toLowerCase(peeked) : foldAsciiLower(peeked);
				if (upper != peeked) {
					mapped = dispatchMap.get(upper);
				}
				if (mapped == null && lower != peeked) {
					mapped = dispatchMap.get(lower);
				}
			}
		}
		return (mapped != null) ? mapped : elseDispatch;
	}

	private static int foldAsciiUpper(int codePoint) {
		return (codePoint >= 'a' && codePoint <= 'z') ? codePoint - ('a' - 'A') : codePoint;
	}

	private static int foldAsciiLower(int codePoint) {
		return (codePoint >= 'A' && codePoint <= 'Z') ? codePoint + ('a' - 'A') : codePoint;
	}

	/**
	 * True if {@code a} and {@code b} should be treated as the same character for matching
	 * purposes, honoring {@code flags}' {@code CASE_INSENSITIVE}/{@code UNICODE_CASE} the same way
	 * {@link #getNext} does for dispatch-map-based matching. Used by {@link LiteralMatcherConstruct},
	 * whose own characters are compared directly rather than through a dispatch map.
	 */
	static boolean codePointsMatch(int a, int b, int flags) {
		if (a == b) {
			return true;
		}
		if ((flags & Ll1Pattern.CASE_INSENSITIVE) == 0) {
			return false;
		}
		if ((flags & Ll1Pattern.UNICODE_CASE) != 0) {
			return Character.toUpperCase(a) == Character.toUpperCase(b)
					|| Character.toLowerCase(a) == Character.toLowerCase(b);
		}
		return foldAsciiUpper(a) == foldAsciiUpper(b);
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
			for (Range<Integer> range : owner.validRanges().asRanges()) {
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
			// dispatchMap deliberately stays empty: it's only ever consulted (via getNext(), from
			// match() below) *after* the whole literal has matched, at which point every one of this
			// literal's own characters leads to the exact same place -- `owner.next.matcher` -- so
			// this is an unconditional forward, not a lookup keyed by the just-consumed character.
			// (A dispatchMap entry keyed by this literal's own first character was here previously,
			// for no-longer-obvious reasons; it was never actually correct, since match() below looks
			// up whatever character comes *after* the literal, not the literal's first character --
			// it just went uncaught because no test had exercised match() end-to-end until now.)
			elseDispatch = owner.next.matcher;
		}

		boolean match(Matcher matcher, int peeked) {
			int i=0;
			do {
				int next = value.codePointAt(i);
				// Bug fix (2026-09-06): this was Character.isSupplementaryCodePoint(i) -- checking
				// whether the *loop index* happened to be a huge number, not whether the code point
				// just read is supplementary. Always false for any realistically-sized literal, so
				// `units` was always 1, silently downgrading every supplementary character in a
				// literal to two separate surrogate-half "characters" compared one at a time. That
				// happened to still produce correct comparisons (both sides advance in the same
				// lockstep), so it was latent rather than an active bug, but it's exactly backwards
				// from what was intended and worth fixing now that this method is being touched.
				int units = Character.isSupplementaryCodePoint(next) ? 2 : 1;
				if (!codePointsMatch(next, peeked, matcher.pattern.flags())) {
					return false;
				}
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
		/** Self-registering: this becomes {@code owner.matcher} (the plain, non-capturing case). */
		DispatchMatcherConstruct(QuantifiedUnion owner) {
			super(owner);
			populate(owner.entryMap, owner.entryElse);
		}

		/**
		 * Internal (non-self-registering) variant, used when a capturing union's actual entry point
		 * is a {@link BeginCaptureMatcherConstruct} that wraps this node instead.
		 */
		DispatchMatcherConstruct(RangeMap<Integer, PatternConstruct> entryMap, @Nullable PatternConstruct entryElse) {
			populate(entryMap, entryElse);
		}

		private void populate(RangeMap<Integer, PatternConstruct> entryMap, @Nullable PatternConstruct entryElse) {
			for (Map.Entry<Range<Integer>, PatternConstruct> e : entryMap.asMapOfRanges().entrySet()) {
				dispatchMap.put(e.getKey(), e.getValue().matcher);
			}
			elseDispatch = entryElse != null ? entryElse.matcher : null;
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

	/**
	 * Entered every time a loop's body is (re-)attempted -- i.e. reached only via a {@link
	 * LoopDispatchMatcherConstruct}'s dispatch map, never self-registered into a PatternConstruct.
	 * Enforces the quantifier's upper bound: if the body would otherwise match again but {@code
	 * max} repetitions are already used up, the overall match must fail here rather than continue.
	 */
	static final class LoopMatcherConstruct extends MatcherConstruct {
		final int quantifiableIndex;
		final int max;

		LoopMatcherConstruct(int quantifiableIndex, int max) {
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

	/**
	 * Entered when a {@link LoopDispatchMatcherConstruct} decides the next character means "done
	 * looping" -- reached only via that node's dispatch map, never self-registered. Enforces the
	 * quantifier's lower bound: if fewer than {@code min} repetitions have happened, the overall
	 * match must fail here even though the next character looks like a valid continuation of
	 * whatever follows the loop.
	 */
	static final class EndLoopMatcherConstruct extends MatcherConstruct {
		final int quantifiableIndex;
		final int min;

		EndLoopMatcherConstruct(int quantifiableIndex, int min) {
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

	/**
	 * The externally-visible entry point (and re-check point after each iteration) for a
	 * quantified construct ({@code ?}, {@code *}, {@code +}, or {@code {n,m}}). See design.md's
	 * "compile() algorithm" section. This one dispatch map serves both the very first attempt and
	 * every subsequent re-check (the body's own compiled "next" is {@code owner} itself, forming
	 * the cycle this class's self-registering superclass constructor exists to break): for a
	 * character that could continue the loop, it routes through a {@link LoopMatcherConstruct}
	 * (which enforces {@code max} then dispatches to whichever body part matched); for a character
	 * that matches what follows the loop, it routes through an {@link EndLoopMatcherConstruct}
	 * (which enforces {@code min} then dispatches to {@code next}). The runtime min/max checks --
	 * not two different static dispatch maps -- are what make one map correct for every bound.
	 *
	 * <p>When {@code captureConstructIndex != -1} (the construct is <i>also</i> a capturing group,
	 * e.g. {@code (a)*}), the capture must re-fire every iteration -- last iteration wins, per real
	 * regex semantics -- so this inserts two more indirections: {@code LoopMatcherConstruct}
	 * dispatches unconditionally to a shared {@link BeginCaptureMatcherConstruct} (which records the
	 * start position, then dispatches by character among the body parts, same as the non-capturing
	 * case would have dispatched directly), and each body part is compiled against a {@link
	 * PatternConstruct.CaptureEndMarker} standing in for {@code owner} -- so finishing one iteration
	 * records the captured substring before looping back, rather than looping back directly.
	 */
	static final class LoopDispatchMatcherConstruct extends MatcherConstruct {
		LoopDispatchMatcherConstruct(
				PatternConstruct.QuantifiableConstruct owner, List<PatternConstruct> body, PatternConstruct next,
				int captureConstructIndex) {
			super(owner); // Self-registers FIRST -- see the class doc above and design.md.

			boolean capturing = captureConstructIndex != -1;
			PatternConstruct bodyCompileTarget = owner;
			if (capturing) {
				// Loops back to `owner` (this node) itself, same as the non-capturing case, but only
				// after recording the captured substring -- owner.matcher (== this) is already set by
				// the super(owner) call above, so this is safe to compile immediately.
				bodyCompileTarget = new PatternConstruct.CaptureEndMarker(owner.startIndex, captureConstructIndex, owner);
				bodyCompileTarget.compile(owner);
			}

			List<PatternConstruct> candidates = new ArrayList<>(body);
			candidates.add(next);
			PatternConstruct.MergedEntries result =
					PatternConstruct.compileAndMergeCandidates(owner.pattern, candidates, bodyCompileTarget, "loop part");

			LoopMatcherConstruct loopNode = new LoopMatcherConstruct(owner.quantifiableIndex, owner.max);
			EndLoopMatcherConstruct endLoopNode = new EndLoopMatcherConstruct(owner.quantifiableIndex, owner.min);
			endLoopNode.elseDispatch = next.matcher;

			// Where a body-owned range's target actually gets recorded: directly on loopNode in the
			// non-capturing case, or on a shared BeginCapture (which loopNode forwards to
			// unconditionally, below) when capturing -- either way, `loopNode` is what the outer
			// dispatchMap below routes "continue" characters to.
			BeginCaptureMatcherConstruct beginCaptureNode =
					capturing ? new BeginCaptureMatcherConstruct(captureConstructIndex) : null;
			MatcherConstruct bodyDispatchNode = capturing ? beginCaptureNode : loopNode;

			for (Map.Entry<CodePointMap.Range, PatternConstruct> e : result.ranges.entrySet()) {
				Range<Integer> range = Range.closedOpen(e.getKey().min, e.getKey().max);
				boolean isExit = e.getValue() == next;
				dispatchMap.put(range, isExit ? endLoopNode : loopNode);
				if (!isExit) {
					bodyDispatchNode.dispatchMap.put(range, e.getValue().matcher);
				}
			}
			if (result.elseCandidate != null) {
				elseDispatch = (result.elseCandidate == next) ? endLoopNode : loopNode;
				if (result.elseCandidate != next) {
					bodyDispatchNode.elseDispatch = result.elseCandidate.matcher;
				}
			}
			if (capturing) {
				loopNode.elseDispatch = beginCaptureNode; // unconditional: every continue attempt begins capturing.
			}

			// This construct's own entry set, as seen by whatever ambiguity check an ancestor (e.g.
			// an enclosing union or loop) runs on it: always the body's ranges; also `next`'s ranges
			// (entering zero times is valid) when min == 0.
			for (Map.Entry<CodePointMap.Range, PatternConstruct> e : result.ranges.entrySet()) {
				if (e.getValue() != next || owner.min == 0) {
					owner.entryMap.put(Range.closedOpen(e.getKey().min, e.getKey().max), e.getValue());
				}
			}
			if (result.elseCandidate != null && (result.elseCandidate != next || owner.min == 0)) {
				owner.entryElse = result.elseCandidate.entryElse;
			}
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, peeked);
		}
	}

	static final class BeginCaptureMatcherConstruct extends MatcherConstruct {
		final int captureConstructIndex;

		BeginCaptureMatcherConstruct(PatternConstruct owner, int captureConstructIndex, MatcherConstruct target) {
			super(owner);
			this.captureConstructIndex = captureConstructIndex;
			this.elseDispatch = target;
		}

		/**
		 * Internal (non-self-registering) variant used when the same BeginCapture instance needs to
		 * dispatch by character among several loop-body branches -- the caller populates
		 * dispatchMap/elseDispatch directly afterward (see LoopDispatchMatcherConstruct) rather than
		 * this constructor taking one fixed target.
		 */
		BeginCaptureMatcherConstruct(int captureConstructIndex) {
			this.captureConstructIndex = captureConstructIndex;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			matcher.captureGroups[captureConstructIndex] = new Group(matcher.pos);
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, peeked);
		}
	}

	static final class EndCaptureMatcherConstruct extends MatcherConstruct {
		final int captureConstructIndex;

		EndCaptureMatcherConstruct(PatternConstruct.CaptureEndMarker owner, int captureConstructIndex, MatcherConstruct target) {
			super(owner);
			this.captureConstructIndex = captureConstructIndex;
			this.elseDispatch = target;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			Group group = matcher.captureGroups[captureConstructIndex];
			group.result = matcher.input.substring(group.inputStartIndex, matcher.pos);
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, peeked);
		}
	}

	/**
	 * Reached once the whole pattern has matched. Whether that's actually a *complete* match
	 * depends on which Matcher operation is running: {@code matches()} requires consuming the
	 * whole region, while {@code lookingAt()}/{@code find()} only need a matched prefix -- see
	 * {@link Matcher#requireFullMatch}, set immediately before each match attempt. This is the one
	 * place that flag is read; every other node just cares whether the pattern's own structure was
	 * satisfied, not how much of the region is left over.
	 */
	static final class EndMatcherConstruct extends MatcherConstruct {
		EndMatcherConstruct(PatternConstruct.EndConstruct owner) {
			super(owner);
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			return !matcher.requireFullMatch || matcher.pos == matcher.regionEnd;
		}
	}
}

package com.tbohne.llkpattern;

import com.google.common.annotations.VisibleForTesting;
import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import com.tbohne.llkpattern.Matcher.Group;
import com.tbohne.llkpattern.PatternConstruct.BoundaryConstruct.BoundaryEnum;
import com.tbohne.llkpattern.PatternConstruct.ComplexCharacter;
import com.tbohne.llkpattern.PatternConstruct.QuantifiedUnion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A single compiled, executable step in the matcher graph -- one "opcode" of the tiny virtual
 * machine this package interprets. See design.md's "The compile() algorithm and cycle handling"
 * and "Opcode set" sections for the full picture -- the short version:
 *
 * <p>Every {@link PatternConstruct} compiles to exactly one {@code MatcherConstruct}, referenced
 * by that construct's {@code matcher} field. A {@code MatcherConstruct}'s constructor's *first*
 * action (done here, in the base constructor) is to assign itself to its owning construct's
 * {@code matcher} field -- before anything else, including resolving the dependencies it needs
 * for its own successor(s). That ordering is what makes a cyclic PatternConstruct graph (a
 * quantifier looping back on itself) safe to compile without infinite recursion or a separate
 * visited-set: a nested {@code PatternConstruct.compile(...)} call that loops back to a construct
 * already under construction sees its (still being filled in) {@code matcher} and returns
 * immediately instead of recursing.
 *
 * <p>Almost every node has exactly one possible successor, known at construction time --
 * {@link SingleDispatchingMatcherConstruct} covers those, holding a {@code final} successor
 * reference since there's no risk of a node needing to see its own not-yet-built successor. The
 * handful of nodes that genuinely branch on the next code point -- a union's {@code |}, and a
 * loop's "keep looping vs. exit" choice -- extend
 * {@link MultiDispatchingMatcherConstruct} instead, whose dispatch map/else-value are
 * intentionally not {@code final}: a `final` field can only be safely published to other threads
 * if it's set before the constructor completes, but a self-referential dispatch entry (a loop
 * dispatching back to its own entry node) is unavoidably written by a *different* (nested)
 * constructor call while this one is still running. Instead, every dispatch/build step for an
 * entire {@code Ll1Pattern} happens synchronously, before that pattern's own constructor (which
 * does have a `final` field) runs -- so the whole graph is safely published transitively through
 * {@code Ll1Pattern}'s `final compiled` field, even though the individual
 * {@code MultiDispatchingMatcherConstruct}s reachable from it are not `final` themselves. Treat
 * these fields as immutable *by contract* once construction of the whole graph is finished.
 */
abstract class MatcherConstruct {
	// The CASE_INSENSITIVE/UNICODE_CASE/etc. flags in effect where this node's PatternConstruct was
	// parsed (see PatternConstruct#flags) -- NOT necessarily the pattern-wide
	// Ll1Pattern.compile(pattern, flags) flags. This is what makes an inline "(?i:...)" actually
	// scope case-insensitivity to just that group instead of the whole pattern: see "Inline flag
	// toggles don't actually locally scope anything" in remaining_work.md.
	final int flags;

	/**
	 * @param owner the PatternConstruct this MatcherConstruct implements. Assigning {@code
	 *     owner.matcher = this} here, before subclass constructors resolve any dependencies, is
	 *     what breaks cycles -- see the class doc.
	 */
	MatcherConstruct(PatternConstruct owner) {
		owner.matcher = this;
		this.flags = owner.flags;
	}

	/**
	 * For internal/synthetic nodes that aren't the externally-visible entry point of any single
	 * PatternConstruct -- e.g. {@link LoopMatcherConstruct}/{@link EndLoopMatcherConstruct} (both
	 * only ever reached via a loop's own entry {@link MultiDispatchingMatcherConstruct}), or the
	 * plain alternation dispatch wrapped inside a capturing group's Begin/EndCapture pair. Skips
	 * self-registration since there's no single owning construct to register into, so the caller
	 * must supply the local flags directly (normally the owning construct's own {@code flags}).
	 */
	MatcherConstruct(int flags) {
		this.flags = flags;
	}

	abstract boolean match(Matcher matcher, int peeked);

	private static int foldAsciiUpper(int codePoint) {
		return (codePoint >= 'a' && codePoint <= 'z') ? codePoint - ('a' - 'A') : codePoint;
	}

	private static int foldAsciiLower(int codePoint) {
		return (codePoint >= 'A' && codePoint <= 'Z') ? codePoint + ('a' - 'A') : codePoint;
	}

	/**
	 * True if {@code a} and {@code b} should be treated as the same character for matching
	 * purposes, honoring {@code flags}' {@code CASE_INSENSITIVE}/{@code UNICODE_CASE}. Used by
	 * {@link LiteralMatcherConstruct}, whose own characters are compared directly rather than
	 * through a dispatch map.
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

	/**
	 * True if {@code peeked} (or, under {@code CASE_INSENSITIVE}, one of its other-case forms) is a
	 * member of {@code ranges}. Used by {@link SingleCharMatcherConstruct} in place of a dispatch
	 * map: a character class has exactly one successor regardless of *which* member character was
	 * seen, so all it needs is a yes/no membership test, not a lookup keyed by the character.
	 */
	static boolean containsFolded(CodePointMap<Boolean> ranges, int peeked, int flags) {
		// -1 (Matcher's "no more input" sentinel -- see Matcher#peek) is checked FIRST and
		// unconditionally, unlike getNext()'s use of getExplicit() for a similar-looking check: a
		// negated class (e.g. "[^a-z]") is an else-value map, and its else-value fill legitimately
		// covers every real code point it doesn't explicitly exclude -- membership here has to see
		// that fill (hence get(), not getExplicit()), but -1 is never a real code point, so it must
		// never be reported a "member" of even a total (else-valued) ranges map. This is the same
		// domain guard ComplexCharacter#validRanges() used to provide via clamping a Guava RangeSet.
		if (peeked == -1) {
			return false;
		}
		if (ranges.get(peeked) != null) {
			return true;
		}
		if ((flags & Ll1Pattern.CASE_INSENSITIVE) == 0) {
			return false;
		}
		boolean unicode = (flags & Ll1Pattern.UNICODE_CASE) != 0;
		int upper = unicode ? Character.toUpperCase(peeked) : foldAsciiUpper(peeked);
		int lower = unicode ? Character.toLowerCase(peeked) : foldAsciiLower(peeked);
		return (upper != peeked && ranges.get(upper) != null) || (lower != peeked && ranges.get(lower) != null);
	}

	/**
	 * Base class for the (large majority of) nodes that only ever have one possible successor,
	 * known at construction time -- matching input at that node never depends on *which* character
	 * was seen to decide where to go next, only whether matching succeeded at all.
	 *
	 * <p>{@code next} is safe to be {@code final} here (unlike {@link MultiDispatchingMatcherConstruct}'s
	 * fields) because none of this class's subclasses are ever the self-referential node in a
	 * cycle -- that role belongs exclusively to a loop's entry {@code MultiDispatchingMatcherConstruct}
	 * -- so a Single-dispatching node's successor is always some other node that's already fully
	 * built (or at least already self-registered) by the time this constructor runs.
	 *
	 * <p>{@code next.match(...)} is called as a plain virtual call ({@link #matchNext}), not via a
	 * {@code MethodHandle} -- an earlier version of this class bound one via {@code findSpecial}
	 * per successor, on the theory that an {@code invokespecial}-style direct call would let the
	 * JIT inline it more readily than an ordinary virtual dispatch. Reverted 2026-09-07 (per the
	 * project owner, after discussion elsewhere): a {@code MethodHandle} invocation on a
	 * non-static receiver isn't reliably inlined by any JVM, and is frequently *slower* than a
	 * plain virtual call even on newer Android runtimes -- there was no actual benefit to trade
	 * against the construction-time cost and the Java-8/Android-API-26 compatibility contortions
	 * (see design.md's "Direct-call MethodHandle binding" section for that history, kept for the
	 * record even though the conclusion was to not do this).
	 */
	abstract static class SingleDispatchingMatcherConstruct extends MatcherConstruct {
		final MatcherConstruct next;

		SingleDispatchingMatcherConstruct(PatternConstruct owner, MatcherConstruct next) {
			super(owner);
			this.next = next;
		}

		SingleDispatchingMatcherConstruct(int flags, MatcherConstruct next) {
			super(flags);
			this.next = next;
		}

		/** Invokes {@code next.match(matcher, peeked)}. */
		final boolean matchNext(Matcher matcher, int peeked) {
			return next.match(matcher, peeked);
		}

		@VisibleForTesting
		MatcherConstruct getNext() { return next; }
	}

	/**
	 * Base class for the handful of nodes that genuinely branch on the next code point without
	 * consuming it: a union's {@code |}, and a loop's entry/re-entry dispatch (see
	 * {@link DispatchMatcherConstruct}'s loop-flavored constructor). See the class doc above for why
	 * these fields, unlike {@link SingleDispatchingMatcherConstruct#next}, are not {@code final}.
	 */
	abstract static class MultiDispatchingMatcherConstruct extends MatcherConstruct {
		// The "else" destination lives as dispatchMap's own else-value (CodePointMap#getElseValue)
		// rather than a separate field, since that's exactly what it is: wherever the next code
		// point isn't explicitly claimed. getNext() below has to use getExplicit(), not get(), for
		// its own intermediate lookups -- get() would fold the else-value in too early and short-
		// circuit the case-insensitive fallback (see its comment).
		MutableCodePointMap<MatcherConstruct> dispatchMap = new ArrayCodePointMap<>();

		MultiDispatchingMatcherConstruct(PatternConstruct owner) {
			super(owner);
		}

		MultiDispatchingMatcherConstruct(int flags) {
			super(flags);
		}

		@Nullable MatcherConstruct getNext(Matcher matcher, int peeked) {
			MatcherConstruct mapped = dispatchMap.getExplicit(peeked);
			if (mapped == null && peeked != -1) {
				// CASE_INSENSITIVE/UNICODE_CASE (2026-09-06): dispatchMap's keys are exactly the code
				// points the pattern was written with (e.g. "[a-z]" only ever puts 'a'-'z' in the map),
				// so a case-insensitive match has to try the *input* character's other-case forms
				// against that same map, rather than expanding every character class's ranges at compile
				// time. Two lookups (not one) because there's no single "canonical case" that works for
				// both an all-lowercase pattern matching an uppercase input and vice versa.
				// Uses this node's own local `flags` (its PatternConstruct's parse-time flags), not
				// matcher.pattern.flags(), so an inline "(?i:...)" only affects matching within its own
				// scope instead of the whole pattern.
				if ((flags & Ll1Pattern.CASE_INSENSITIVE) != 0) {
					boolean unicode = (flags & Ll1Pattern.UNICODE_CASE) != 0;
					int upper = unicode ? Character.toUpperCase(peeked) : foldAsciiUpper(peeked);
					int lower = unicode ? Character.toLowerCase(peeked) : foldAsciiLower(peeked);
					if (upper != peeked) {
						mapped = dispatchMap.getExplicit(upper);
					}
					if (mapped == null && lower != peeked) {
						mapped = dispatchMap.getExplicit(lower);
					}
				}
			}
			return (mapped != null) ? mapped : dispatchMap.getElseValue();
		}

		@VisibleForTesting
		CodePointMap<MatcherConstruct> getDispatchMap() { return dispatchMap; }

		@VisibleForTesting
		@Nullable MatcherConstruct getElse() { return dispatchMap.getElseValue(); }
	}

	/**
	 * Matches exactly one code point against {@code validRanges} (a character class -- {@code .}, a
	 * literal single character, or {@code [...]}), then advances to whatever comes next. A pure
	 * membership test, not a dispatch: every member character leads to the same single successor.
	 */
	static final class SingleCharMatcherConstruct extends SingleDispatchingMatcherConstruct {
		final CodePointMap<Boolean> validRanges;

		SingleCharMatcherConstruct(ComplexCharacter owner) {
			super(owner, owner.next.matcher);
			this.validRanges = owner.validRanges();
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			if (!containsFolded(validRanges, peeked, flags)) {
				return false;
			}
			return matchNext(matcher, matcher.consume1CodePoint());
		}
	}

	/** Matches a fixed literal string exactly, then advances to whatever comes next. */
	static final class LiteralMatcherConstruct extends SingleDispatchingMatcherConstruct {
		final String value;

		LiteralMatcherConstruct(PatternConstruct owner, String value) {
			super(owner, owner.next.matcher);
			this.value = value;
		}

		// TODO: optimize to compare the entire `String value` as a single operation.
		boolean match(Matcher matcher, int peeked) {
			int i=0;
			do {
				int next = value.codePointAt(i);
				int units = Character.isSupplementaryCodePoint(next) ? 2 : 1;
				if (!codePointsMatch(next, peeked, flags)) {
					return false;
				}
				peeked = matcher.consumeCodeUnits(units);
				i += units;
			} while (i<value.length());
			return matchNext(matcher, peeked);
		}
	}

	/**
	 * Dispatches immediately based on the next code point, without consuming any input itself. This
	 * is what a {@code QuantifiedUnion}'s alternation (the {@code |} branches) compiles to, and --
	 * via the loop-flavored constructor below -- also what a quantified construct's entry/re-entry
	 * point compiles to: picking a branch (or deciding to keep looping vs. exit) doesn't consume a
	 * character, the chosen path's own matcher does.
	 */
	static final class DispatchMatcherConstruct extends MultiDispatchingMatcherConstruct {
		/**
		 * Self-registering variant that populates from an explicit entry map/else instead of {@code
		 * owner}'s own {@code entryMap}/{@code entryElse} fields -- needed by {@code
		 * QuantifiedUnion.buildMatcher()}'s non-capturing case: {@code owner.entryMap} is deliberately
		 * re-keyed onto {@code owner} itself (see {@code QuantifiedUnion.rawEntryMap}'s doc), which
		 * would make {@code populate()} resolve every entry to {@code owner.matcher} -- i.e. to this
		 * very node, once self-registration sets it -- an infinite self-dispatch loop. {@code
		 * rawEntryMap}/{@code rawEntryElse} keep the original, immediately-resolvable candidate
		 * identities this constructor actually needs. (A simpler self-registering constructor that
		 * just read {@code owner.getEntryPointMap()} directly used to exist here too, but had no
		 * callers -- removed 2026-09-08 alongside entryMap's Boolean-value migration, which would
		 * have made it read the wrong thing anyway: entryMap's values are always {@code true}, not a
		 * dispatch target -- see PatternConstruct.entryMap's doc.)
		 */
		DispatchMatcherConstruct(PatternConstruct owner, CodePointMap<PatternConstruct> entryMap, @Nullable PatternConstruct entryElse) {
			super(owner);
			populate(entryMap, entryElse);
		}

		/**
		 * Internal (non-self-registering) variant, used when a capturing union's actual entry point
		 * is a {@link BeginCaptureMatcherConstruct} that wraps this node instead.
		 */
		DispatchMatcherConstruct(CodePointMap<PatternConstruct> entryMap, @Nullable PatternConstruct entryElse, int flags) {
			super(flags);
			populate(entryMap, entryElse);
		}

		/**
		 * Self-registering constructor for a quantified construct's entry/re-entry dispatch point --
		 * see design.md's opcode section. Builds the same generic multi-way dispatch a plain union
		 * uses (this IS a {@code DispatchMatcherConstruct}, not a distinct node type), wired to route
		 * "keep looping" characters through a {@link LoopMatcherConstruct} and "exit" characters
		 * through an {@link EndLoopMatcherConstruct}. The *same* map serves both the very first
		 * attempt and every re-check after a successful iteration -- the runtime min/max counters on
		 * those two nodes, not two different static maps, are what make one map correct for every
		 * bound.
		 *
		 * <p>When {@code captureConstructIndex != -1} (the construct is <i>also</i> a capturing
		 * group, e.g. {@code (a)*}), the capture must re-fire every iteration -- last iteration wins,
		 * per real regex semantics. Since {@link BeginCaptureMatcherConstruct} is a pure
		 * single-successor opcode (it can't itself branch among body parts), that's implemented by
		 * composing existing opcodes instead of adding a new one: {@code LoopMatcherConstruct}
		 * dispatches unconditionally to a {@code BeginCaptureMatcherConstruct}, whose one successor is
		 * an internal {@code DispatchMatcherConstruct} doing the actual per-character routing among
		 * body parts. Each body part is compiled against a {@link PatternConstruct.CaptureEndMarker}
		 * standing in for {@code owner}, so finishing one iteration records the captured substring
		 * (via {@code EndCaptureMatcherConstruct}) before looping back, rather than looping back
		 * directly.
		 */
		DispatchMatcherConstruct(
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
				bodyCompileTarget.flags = owner.flags;
				bodyCompileTarget.compile(owner);
			}

			List<PatternConstruct> candidates = new ArrayList<>(body);
			candidates.add(next);
			PatternConstruct.MergedEntries result =
					PatternConstruct.compileAndMergeCandidates(owner.pattern, candidates, bodyCompileTarget, "loop part");
			// Whether `body` ITSELF (not `next`) claims a catchall -- e.g. a loop body that's a lone
			// "." -- as opposed to `result.elseCandidate`, which conflates in whatever `next` happens
			// to formally advertise. `next`'s own *advertised* entry set is deliberately narrow (only
			// its own continuation characters -- see design.md's "Entry-point computation vs. matcher
			// compilation" section), even when `next` is itself a loop whose real matcher graph always
			// has SOME way to handle "anything else" (by trying to exit). So whether this loop's own
			// exit path should be reachable for a character neither side explicitly claims must be
			// decided from `body` alone, not from whether `next` happened to register a catchall.
			PatternConstruct.MergedEntries bodyOnlyResult =
					PatternConstruct.mergeEntryPoints(owner.pattern, body, "loop part");

			LoopMatcherConstruct loopNode = new LoopMatcherConstruct(owner.quantifiableIndex, owner.max, owner.flags);
			EndLoopMatcherConstruct endLoopNode =
					new EndLoopMatcherConstruct(owner.quantifiableIndex, owner.min, owner.flags, next.matcher);

			// Non-capturing: loopNode's own map dispatches directly to whichever body part matched.
			// Capturing: loopNode dispatches unconditionally to a BeginCapture, which forwards to a
			// plain (internal) DispatchMatcherConstruct doing that same per-branch routing -- needed
			// because BeginCapture itself is a pure single-successor opcode now.
			if (capturing) {
				MutableCodePointMap<PatternConstruct> bodyEntries = new ArrayCodePointMap<>();
				for (Map.Entry<CodePointMap.Range, PatternConstruct> e : result.ranges.entrySet()) {
					if (e.getValue() != next) {
						bodyEntries.put(e.getKey().min, e.getKey().max, e.getValue());
					}
				}
				DispatchMatcherConstruct bodyDispatch =
						new DispatchMatcherConstruct(bodyEntries, bodyOnlyResult.elseCandidate, owner.flags);
				BeginCaptureMatcherConstruct beginCaptureNode =
						new BeginCaptureMatcherConstruct(captureConstructIndex, owner.flags, bodyDispatch);
				loopNode.dispatchMap.setElseValue(beginCaptureNode); // unconditional: every continue attempt begins capturing.
			} else {
				for (Map.Entry<CodePointMap.Range, PatternConstruct> e : result.ranges.entrySet()) {
					if (e.getValue() != next) {
						loopNode.dispatchMap.put(e.getKey().min, e.getKey().max, e.getValue().matcher);
					}
				}
				if (bodyOnlyResult.elseCandidate != null) {
					loopNode.dispatchMap.setElseValue(bodyOnlyResult.elseCandidate.matcher);
				}
			}

			for (Map.Entry<CodePointMap.Range, PatternConstruct> e : result.ranges.entrySet()) {
				dispatchMap.put(e.getKey().min, e.getKey().max, (e.getValue() == next) ? endLoopNode : loopNode);
			}
			if (bodyOnlyResult.elseCandidate == null) {
				// Body claims no catchall of its own, so any character it doesn't explicitly claim
				// should always try to exit -- regardless of whether `next` happens to have registered
				// an explicit catchall. `next`'s own dispatch (reached via endLoopNode) will correctly
				// accept or reject it on its own terms (e.g. an ancestor loop's own continue-vs-exit
				// check) -- this node doesn't need to pre-verify that itself.
				dispatchMap.setElseValue(endLoopNode);
			} else if (result.elseCandidate != null) {
				dispatchMap.setElseValue((result.elseCandidate == next) ? endLoopNode : loopNode);
			}

			// owner's own entry set (as seen by whatever ambiguity check an ancestor runs on it) is
			// NOT computed here -- it's computed earlier, by PatternConstruct.QuantifiableConstruct's
			// buildLoopEntryMap(), before this constructor (built from buildMatcher()) ever runs. See
			// design.md's "Entry-point computation vs. matcher compilation" section for why that
			// split is what lets a loop nested in this construct's own body ask `owner` for its entry
			// set without forcing owner's (still in-progress, right here) matcher construction to
			// finish first.
		}

		private void populate(CodePointMap<PatternConstruct> entryMap, @Nullable PatternConstruct entryElse) {
			for (Map.Entry<CodePointMap.Range, PatternConstruct> e : entryMap.entrySet()) {
				dispatchMap.put(e.getKey().min, e.getKey().max, e.getValue().matcher);
			}
			dispatchMap.setElseValue(entryElse != null ? entryElse.matcher : null);
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			MatcherConstruct next = getNext(matcher, peeked);
			return next != null && next.match(matcher, peeked);
		}
	}

	/**
	 * Matches whatever {@code captureConstructIndex}'s group actually captured last, then advances
	 * to whatever comes next -- {@code \1}/{@code \k<name>}, resolved to a fixed
	 * {@code captureConstructIndex} at parse time (see {@code PatternConstruct.BackReference}).
	 */
	static final class BackReferenceMatcherConstruct extends SingleDispatchingMatcherConstruct {
		final int captureConstructIndex;

		BackReferenceMatcherConstruct(PatternConstruct owner, int captureConstructIndex) {
			super(owner, owner.next.matcher);
			this.captureConstructIndex = captureConstructIndex;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			Group group = matcher.captureGroups[captureConstructIndex];
			if (group == null || group.result == null) {
				// The referenced group never participated in the match (e.g. it's in a sibling
				// alternation branch that wasn't taken) -- java.util.regex treats an unparticipated
				// group's backreference as never matching, not as matching the empty string.
				return false;
			}
			String value = group.result;
			if (value.isEmpty()) {
				return matchNext(matcher, peeked);
			}
			int i = 0;
			do {
				int next = value.codePointAt(i);
				int units = Character.isSupplementaryCodePoint(next) ? 2 : 1;
				if (!codePointsMatch(next, peeked, flags)) {
					return false;
				}
				peeked = matcher.consumeCodeUnits(units);
				i += units;
			} while (i < value.length());
			return matchNext(matcher, peeked);
		}
	}

	/**
	 * Length (in chars) of the line terminator starting at {@code input.charAt(index)}, or 0 if
	 * there isn't one there -- {@code "\r\n"} counts as a single 2-char terminator, matching
	 * {@code java.util.regex}'s default (non-{@code UNIX_LINES}) set: {@code \n}, {@code \r},
	 * {@code \r\n}, {@code \u0085}, {@code \u2028}, {@code \u2029}. Under {@code UNIX_LINES},
	 * only {@code \n} counts. Never looks past {@code limit} (the region end, per this engine's
	 * "opaque bounds" stance -- see {@code Matcher#peekPrevious()}). Shared by {@link
	 * BoundaryMatcherConstruct} ({@code \Z}) and {@link LineBoundaryMatcherConstruct} ({@code $}).
	 */
	private static int lineTerminatorLengthAt(String input, int index, int limit, int flags) {
		if (index >= limit) {
			return 0;
		}
		char c = input.charAt(index);
		if (c == '\n') {
			return 1;
		}
		if ((flags & Ll1Pattern.UNIX_LINES) != 0) {
			return 0;
		}
		if (c == '\r') {
			return (index + 1 < limit && input.charAt(index + 1) == '\n') ? 2 : 1;
		}
		return (c == '\u0085' || c == '\u2028' || c == '\u2029') ? 1 : 0;
	}

	/**
	 * Length (in chars) of the line terminator ending exactly at {@code input.charAt(index - 1)}
	 * (i.e. occupying {@code [index - length, index)}), or 0 if there isn't one -- the mirror
	 * image of {@link #lineTerminatorLengthAt}, used for {@code ^}'s MULTILINE check (was the
	 * character just before this position the end of a line terminator?). Never looks before
	 * {@code floor} (the region start) or at/past {@code limit} (the region end). Used only by
	 * {@link LineBoundaryMatcherConstruct} ({@code ^}) -- nothing else looks backward.
	 */
	private static int lineTerminatorLengthBefore(String input, int index, int floor, int limit, int flags) {
		if (index <= floor) {
			return 0;
		}
		char c = input.charAt(index - 1);
		if (c == '\n') {
			boolean crlf = (flags & Ll1Pattern.UNIX_LINES) == 0
					&& index - 2 >= floor
					&& input.charAt(index - 2) == '\r';
			return crlf ? 2 : 1;
		}
		if ((flags & Ll1Pattern.UNIX_LINES) != 0) {
			return 0;
		}
		if (c == '\r') {
			// A lone '\r' is its own complete terminator ONLY if it's not immediately followed by
			// '\n' -- otherwise it's the first half of a "\r\n" pair, which only completes (and
			// only counts as ending here) one position later, at index + 1.
			boolean startsCrLf = index < limit && input.charAt(index) == '\n';
			return startsCrLf ? 0 : 1;
		}
		return (c == '\u0085' || c == '\u2028' || c == '\u2029') ? 1 : 0;
	}

	/**
	 * {@code \Z}: the end of the input, or immediately before the input's own final line
	 * terminator (if it has one) -- i.e. a line terminator starting here that reaches exactly to
	 * {@code regionEnd}, not merely one somewhere in the middle of the remaining input. Also
	 * {@code $}'s definition when {@code MULTILINE} is off (see {@code java.util.regex.Pattern}'s
	 * "Line terminators" section: without {@code MULTILINE}, {@code $} and {@code \Z} coincide) --
	 * shared by {@link BoundaryMatcherConstruct} and {@link LineBoundaryMatcherConstruct}.
	 */
	private static boolean matchesEndExceptTerminator(Matcher matcher, int flags) {
		if (matcher.pos == matcher.regionEnd) {
			return true;
		}
		int len = lineTerminatorLengthAt(matcher.input, matcher.pos, matcher.regionEnd, flags);
		return len > 0 && matcher.pos + len == matcher.regionEnd;
	}

	static final class BoundaryMatcherConstruct extends SingleDispatchingMatcherConstruct {
		final BoundaryEnum type;

		BoundaryMatcherConstruct(PatternConstruct owner, BoundaryEnum type) {
			super(owner, owner.next.matcher);
			this.type = type;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			boolean matchesHere;
			switch (type) {
				case InputBegin: // \A: always the true start of input, MULTILINE has no effect.
					matchesHere = matcher.pos == matcher.regionStart;
					break;
				case InputEnd: // \z: always the true end of input, MULTILINE has no effect.
					matchesHere = matcher.pos == matcher.regionEnd;
					break;
				case InputEndExceptTerminator: // \Z
					matchesHere = matchesEndExceptTerminator(matcher, flags);
					break;
				default:
					// Every BoundaryEnum value is handled above -- this is only reachable if a new one
					// is ever added without updating this switch.
					throw new AssertionError("Unhandled BoundaryEnum: " + type);
			}
			return matchesHere && matchNext(matcher, peeked);
		}
	}

	/**
	 * {@code ^} (line begin) / {@code $} (line end): without {@code MULTILINE}, exactly {@code \A}/
	 * {@code \Z} (see {@link #matchesEndExceptTerminator}); under {@code MULTILINE}, {@code ^} also
	 * matches immediately after any line terminator ({@link #lineTerminatorLengthBefore}, a
	 * backward scan -- the mirror of {@code \Z}'s forward one) and {@code $} immediately before any
	 * line terminator ({@link #lineTerminatorLengthAt}). See design.md's "Boundary matching"
	 * section.
	 */
	static final class LineBoundaryMatcherConstruct extends SingleDispatchingMatcherConstruct {
		final boolean isLineBegin; // true: ^, false: $

		LineBoundaryMatcherConstruct(PatternConstruct owner, boolean isLineBegin) {
			super(owner, owner.next.matcher);
			this.isLineBegin = isLineBegin;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			boolean matchesHere;
			if (isLineBegin) {
				matchesHere = matcher.pos == matcher.regionStart
						|| ((flags & Ll1Pattern.MULTILINE) != 0
								&& lineTerminatorLengthBefore(matcher.input, matcher.pos, matcher.regionStart, matcher.regionEnd, flags) > 0);
			} else {
				matchesHere = (flags & Ll1Pattern.MULTILINE) == 0
						? matchesEndExceptTerminator(matcher, flags)
						: (matcher.pos == matcher.regionEnd
								|| lineTerminatorLengthAt(matcher.input, matcher.pos, matcher.regionEnd, flags) > 0);
			}
			return matchesHere && matchNext(matcher, peeked);
		}
	}

	/**
	 * {@code \b} (word boundary) / {@code \B} (non-word-boundary): unlike every other construct,
	 * whether this matches depends on the character just BEFORE the current position, not just the
	 * one at/after it -- see design.md's "Boundary matching" section. The general case needs to
	 * inspect both {@code matcher.peekPrevious()} and {@code peeked} and compare their "is this a
	 * word character" classifications; but per the project owner (2026-09-07), \b/\B very often sits
	 * next to a literal character or character class that is statically always-word or
	 * always-non-word, in which case only ONE side needs checking at match time. {@code
	 * WordBoundaryConstruct.buildMatcher()} does that compile-time classification (and folds the fully
	 * statically-known case into either a compile error or a zero-width no-op, never even
	 * constructing one of these) -- this class just interprets whichever of the two enums below ended
	 * up not {@code Unchecked}.
	 */
	static final class WordBoundaryMatcherConstruct extends SingleDispatchingMatcherConstruct {
		/** Whether {@code match()} needs to independently check {@code matcher.peekPrevious()}. */
		enum PriorWordBoundaryMatchType {
			Unchecked,
			PriorMustBeWord,
			PriorMustBeNonWord
		}

		/**
		 * Whether/how {@code match()} needs to check {@code peeked} -- either against a fixed
		 * word-ness (when the OTHER side, the preceding character, is statically known instead), or
		 * against {@code matcher.peekPrevious()}'s actual word-ness (when neither side is statically
		 * known).
		 */
		enum PeekWordBoundaryMatchType {
			Unchecked,
			PeekMustBeWord,
			PeekMustNotBeWord,
			PeekMustBeSameAsPrior,
			PeekMustBeOppositePrior
		}

		final CodePointMap<Boolean> wordSet;
		final PriorWordBoundaryMatchType priorMustBeWord;
		final PeekWordBoundaryMatchType peekMustBeWord;

		WordBoundaryMatcherConstruct(
				PatternConstruct owner,
				CodePointMap<Boolean> wordSet,
				PriorWordBoundaryMatchType priorMustBeWord,
				PeekWordBoundaryMatchType peekMustBeWord) {
			super(owner, owner.next.matcher);
			if (priorMustBeWord == PriorWordBoundaryMatchType.Unchecked
					&& peekMustBeWord == PeekWordBoundaryMatchType.Unchecked) {
				// WordBoundaryConstruct.buildMatcher() never builds one of these with both sides
				// Unchecked -- that's the fully-statically-known case, resolved at compile time into
				// a compile error or a no-op pass-through instead of a WordBoundaryMatcherConstruct.
				throw new IllegalStateException(
						"WordBoundaryMatcherConstruct built with neither side checked");
			}
			this.wordSet = wordSet;
			this.priorMustBeWord = priorMustBeWord;
			this.peekMustBeWord = peekMustBeWord;
		}

		private boolean isWordChar(int codePoint) {
			return codePoint >= 0 && wordSet.containsKey(codePoint);
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			// peekPrevious() is only actually called when some check below needs it -- checkPrior
			// is exactly that: either the prior side has a fixed target of its own, or the peek
			// side needs to compare against it. checkPeek is the mirror image, for symmetry/clarity
			// (peeked itself is already available for free, but isWordChar(peeked) is not free).
			boolean checkPrior = priorMustBeWord != PriorWordBoundaryMatchType.Unchecked
					|| peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeSameAsPrior
					|| peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeOppositePrior;
			boolean priorIsWord = checkPrior && isWordChar(matcher.peekPrevious());
			boolean checkPeek = peekMustBeWord != PeekWordBoundaryMatchType.Unchecked;
			boolean peekIsWord = checkPeek && isWordChar(peeked);

			if (priorMustBeWord == PriorWordBoundaryMatchType.PriorMustBeWord && !priorIsWord) {
				return false;
			}
			if (priorMustBeWord == PriorWordBoundaryMatchType.PriorMustBeNonWord && priorIsWord) {
				return false;
			}
			if (peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeWord && !peekIsWord) {
				return false;
			}
			if (peekMustBeWord == PeekWordBoundaryMatchType.PeekMustNotBeWord && peekIsWord) {
				return false;
			}
			if (peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeSameAsPrior && peekIsWord != priorIsWord) {
				return false;
			}
			if (peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeOppositePrior && peekIsWord == priorIsWord) {
				return false;
			}
			return matchNext(matcher, peeked);
		}
	}

	/**
	 * Entered every time a loop's body is (re-)attempted -- i.e. reached only via a loop's entry
	 * {@link DispatchMatcherConstruct}, never self-registered into a PatternConstruct. Enforces the
	 * quantifier's upper bound: if the body would otherwise match again but {@code max} repetitions
	 * are already used up, the overall match must fail here rather than continue. Genuinely
	 * branches (by character, among body parts) after that check, so this stays Multi-dispatching.
	 */
	static final class LoopMatcherConstruct extends MultiDispatchingMatcherConstruct {
		final int quantifiableIndex;
		final int max;

		LoopMatcherConstruct(int quantifiableIndex, int max, int flags) {
			super(flags);
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
	 * Entered when a loop's entry {@link DispatchMatcherConstruct} decides the next character means
	 * "done looping" -- reached only via that node's dispatch map, never self-registered. Enforces
	 * the quantifier's lower bound: if fewer than {@code min} repetitions have happened, the overall
	 * match must fail here even though the next character looks like a valid continuation of
	 * whatever follows the loop. Resets the loop's counter slot to 0 on the way out (rather than a
	 * separate reset-on-entry step) -- safe because this engine never backtracks, so a failed loop
	 * attempt aborts the whole match rather than retrying with stale counter state; the slot is
	 * therefore already 0 whenever a loop is freshly (re-)entered. Always has exactly one successor
	 * (whatever follows the loop), so unlike {@link LoopMatcherConstruct} this is Single-dispatching.
	 */
	static final class EndLoopMatcherConstruct extends SingleDispatchingMatcherConstruct {
		final int quantifiableIndex;
		final int min;

		EndLoopMatcherConstruct(int quantifiableIndex, int min, int flags, MatcherConstruct next) {
			super(flags, next);
			this.quantifiableIndex = quantifiableIndex;
			this.min = min;
		}

		boolean match(Matcher matcher, int peeked) {
			int loopCount = matcher.quantifiableCounts[quantifiableIndex];
			matcher.quantifiableCounts[quantifiableIndex] = 0;
			if (loopCount < min) {
				return false;
			}
			return matchNext(matcher, peeked);
		}
	}

	static final class BeginCaptureMatcherConstruct extends SingleDispatchingMatcherConstruct {
		final int captureConstructIndex;

		BeginCaptureMatcherConstruct(PatternConstruct owner, int captureConstructIndex, MatcherConstruct next) {
			super(owner, next);
			this.captureConstructIndex = captureConstructIndex;
		}

		/**
		 * Internal (non-self-registering) variant used by a capturing loop's entry point -- see
		 * {@link DispatchMatcherConstruct}'s loop-flavored constructor.
		 */
		BeginCaptureMatcherConstruct(int captureConstructIndex, int flags, MatcherConstruct next) {
			super(flags, next);
			this.captureConstructIndex = captureConstructIndex;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			matcher.captureGroups[captureConstructIndex] = new Group(matcher.pos);
			return matchNext(matcher, peeked);
		}
	}

	static final class EndCaptureMatcherConstruct extends SingleDispatchingMatcherConstruct {
		final int captureConstructIndex;

		EndCaptureMatcherConstruct(PatternConstruct.CaptureEndMarker owner, int captureConstructIndex, MatcherConstruct next) {
			super(owner, next);
			this.captureConstructIndex = captureConstructIndex;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			Group group = matcher.captureGroups[captureConstructIndex];
			group.result = matcher.input.substring(group.inputStartIndex, matcher.pos);
			return matchNext(matcher, peeked);
		}
	}

	/**
	 * Reached once the whole pattern has matched. Whether that's actually a *complete* match
	 * depends on which Matcher operation is running: {@code matches()} requires consuming the
	 * whole region, while {@code lookingAt()}/{@code find()} only need a matched prefix -- see
	 * {@link Matcher#requireFullMatch}, set immediately before each match attempt. This is the one
	 * place that flag is read; every other node just cares whether the pattern's own structure was
	 * satisfied, not how much of the region is left over. Has no successor at all, so it extends
	 * neither Single- nor Multi-dispatching.
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

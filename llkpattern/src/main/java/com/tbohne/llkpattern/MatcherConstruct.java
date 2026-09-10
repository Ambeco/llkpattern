package com.tbohne.llkpattern;

import com.google.common.annotations.VisibleForTesting;
import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import com.tbohne.llkpattern.Matcher.Group;
import com.tbohne.llkpattern.PatternConstruct.BoundaryConstruct.BoundaryEnum;
import com.tbohne.llkpattern.PatternConstruct.ComplexCharacter;
import com.tbohne.llkpattern.PatternConstruct.QuantifiedUnion;
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
	 * PatternConstruct -- e.g. the plain alternation dispatch wrapped inside a capturing loop's or
	 * capturing union's Begin/EndCapture pair (see {@code QuantifiableConstruct.buildLoopMatcher}
	 * and {@code QuantifiedUnion.buildMatcher}). Skips
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
	 * consuming it: a union's {@code |}, a loop's entry dispatch, and a loop's own re-check
	 * ({@link LoopMatcherConstruct}, reached as the body's own continuation instead of self-
	 * registering) -- see {@code QuantifiableConstruct.buildLoopMatcher}. See the class doc above for why
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
	 * built directly by {@code QuantifiableConstruct.buildLoopMatcher} -- also what a quantified
	 * construct's own entry point compiles to: picking a branch doesn't consume a character, the
	 * chosen path's own matcher does.
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
		 * Self-registering variant that takes an already-resolved ({@code MatcherConstruct}-valued,
		 * not {@code PatternConstruct}-valued) dispatch map directly, instead of populating one from
		 * an entry map -- used by {@code QuantifiableConstruct.buildLoopMatcher} to build a quantified
		 * construct's own entry point, once every body candidate is compiled and (for a capturing
		 * loop) substituted for the shared {@link BeginCaptureMatcherConstruct} -- see that method's
		 * doc and design.md's opcode section.
		 */
		DispatchMatcherConstruct(PatternConstruct owner, MutableCodePointMap<MatcherConstruct> dispatchMap, @Nullable MatcherConstruct elseValue) {
			super(owner);
			this.dispatchMap = dispatchMap;
			this.dispatchMap.setElseValue(elseValue);
		}

		private void populate(CodePointMap<PatternConstruct> entryMap, @Nullable PatternConstruct entryElse) {
			// dispatchMap starts empty and entryMap's ranges are already ascending, so appendSorted's
			// O(1)-amortized bulk path applies here too (just a per-entry value transform, not a
			// filter or reorder). forEachRange(), not entrySet(), to avoid a Range/Entry/Iterator
			// allocation per range.
			entryMap.forEachRange((min, max, value) -> dispatchMap.appendSorted(min, max, value.matcher));
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
	 * Compiled as a loop body's own continuation -- reached only that way (see {@code
	 * QuantifiableConstruct.buildLoopMatcher}), never as the loop's actual entry point (a separate,
	 * plain {@link DispatchMatcherConstruct} built afterward handles the very first attempt -- see
	 * design.md's opcode section). {@code dispatchMap} is built directly from merging the body's and
	 * {@code next}'s own entry points (each range resolved to its real {@code MatcherConstruct}
	 * target, or to {@code nextMatcher} for "exit"), so one {@code getNext()} call both picks where
	 * to go AND -- by comparing the result against {@code nextMatcher} -- which bound to enforce:
	 * continuing re-checks {@code max} (this node used to be paired with a separate node that only
	 * checked {@code min} on the way out; the two are folded into one class since they're really the
	 * same decision seen from either side).
	 */
	static final class LoopMatcherConstruct extends MultiDispatchingMatcherConstruct {
		final int quantifiableIndex;
		final int min;
		final int max;
		final MatcherConstruct nextMatcher;

		LoopMatcherConstruct(PatternConstruct owner, int quantifiableIndex, int min, int max, MatcherConstruct nextMatcher) {
			super(owner);
			this.quantifiableIndex = quantifiableIndex;
			this.min = min;
			this.max = max;
			this.nextMatcher = nextMatcher;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			// Unconditional: reaching this node at all means a body pass (the very first, or another
			// re-check after a prior successful one) has just finished, so this always represents one
			// more completed iteration -- regardless of which way the dispatch below then decides to
			// go.
			int loopCount = ++matcher.quantifiableCounts[quantifiableIndex];
			MatcherConstruct next = getNext(matcher, peeked);
			if (next == null) {
				return false;
			}
			if (next != nextMatcher) {
				if (loopCount >= max) {
					return false;
				}
			} else {
				if (loopCount < min) {
					return false;
				}
				// Never backtracks, so a failed attempt aborts the whole match rather than retrying
				// with stale counter state -- this reset (only on the successful exit path) is enough
				// to guarantee the slot is already 0 whenever this loop is next freshly (re-)entered.
				matcher.quantifiableCounts[quantifiableIndex] = 0;
			}
			return next.match(matcher, peeked);
		}
	}

	static final class BeginCaptureMatcherConstruct extends SingleDispatchingMatcherConstruct {
		final int captureConstructIndex;

		BeginCaptureMatcherConstruct(PatternConstruct owner, int captureConstructIndex, MatcherConstruct next) {
			super(owner, next);
			this.captureConstructIndex = captureConstructIndex;
		}

		/**
		 * Internal (non-self-registering) variant used by a capturing loop's shared "begin the next
		 * iteration" node -- see {@code QuantifiableConstruct.buildLoopMatcher}.
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

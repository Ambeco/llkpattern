package com.tbohne.llkpattern;

import com.google.common.annotations.VisibleForTesting;
import com.tbohne.llkpattern.CodePointSet.MutableCodePointSet;
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
 * <p><b>Flattened dispatch (2026-09-18 experiment, branch {@code flatten-matcher-dispatch}):</b>
 * every node carries its own optional {@link #entrySet}/{@link #failedEntry} pair, checked BEFORE
 * {@link #matchBody} runs (see {@link #match}) -- there is no separate fork/dispatch node any
 * more. A chain of candidates (a union's branches, a loop's body parts) is built by giving each
 * candidate's own compiled {@code MatcherConstruct} an {@code entrySet} (its own entry point) and
 * a {@code failedEntry} pointing at the next candidate in priority order (or the chain's final
 * fallback) -- see {@code PatternConstruct}'s chain-building helpers. {@code entrySet} is set via
 * the owning {@link PatternConstruct}'s {@code dispatchEntrySet}/{@code dispatchFailedEntry}
 * fields, read by the owner-based constructor below, so no subclass constructor needs to change
 * just to participate in a chain.
 *
 * <p>{@code entrySet} is deliberately checked with a PLAIN {@link CodePointSet#contains} (see
 * {@link #containsEntry}), never {@link #containsFolded} -- under {@code CASE_INSENSITIVE},
 * folding is baked into {@code entrySet} itself at chain-construction time (see {@code
 * PatternConstruct#effectiveEntrySet}), specifically so an exact match anywhere in a chain always
 * wins over a folded match earlier in it (the same priority rule the old {@code
 * ForkingMatcherConstruct}-based design enforced via a two-pass exact-then-fold chain -- see
 * notes.md's entry on this experiment for the case that motivated it: {@code (?i:[a-z]+)X}
 * against {@code "ABCX"} must not let the loop body's folded claim on {@code X} pre-empt the
 * exact literal {@code X} that follows it). A node's OWN {@link #matchBody} still uses {@link
 * #containsFolded} on its own (unfolded) data where relevant (e.g. {@link
 * SingleCharMatcherConstruct}) -- that's what makes it independently correct when reached
 * standalone (no entry gating at all, e.g. a plain sequence element), not just as a chain
 * candidate.
 */
abstract class MatcherConstruct {
	// The CASE_INSENSITIVE/UNICODE_CASE/etc. flags in effect where this node's PatternConstruct was
	// parsed (see PatternConstruct#flags) -- NOT necessarily the pattern-wide
	// Ll1Pattern.compile(pattern, flags) flags. This is what makes an inline "(?i:...)" actually
	// scope case-insensitivity to just that group instead of the whole pattern: see "Inline flag
	// toggles don't actually locally scope anything" in remaining_work.md.
	final int flags;

	// See the class doc's "Flattened dispatch" section. Both null for the overwhelming majority of
	// nodes (anything not currently the head of a chain candidate) -- match() below then always
	// runs matchBody() unconditionally, identical to every node's old behavior.
	final @Nullable CodePointSet entrySet;
	final @Nullable MatcherConstruct failedEntry;

	/**
	 * @param owner the PatternConstruct this MatcherConstruct implements. Assigning {@code
	 *     owner.matcher = this} here, before subclass constructors resolve any dependencies, is
	 *     what breaks cycles -- see the class doc. {@code owner.dispatchEntrySet}/{@code
	 *     owner.dispatchFailedEntry} (set by a chain builder just before {@code owner.compile(...)}
	 *     is called, left {@code null} otherwise) become this node's own {@link #entrySet}/{@link
	 *     #failedEntry} -- see the class doc's "Flattened dispatch" section. This is what lets
	 *     every existing subclass constructor participate in a chain with no signature change of
	 *     its own.
	 */
	MatcherConstruct(PatternConstruct owner) {
		owner.matcher = this;
		this.flags = owner.flags;
		this.entrySet = owner.dispatchEntrySet;
		this.failedEntry = owner.dispatchFailedEntry;
	}

	/**
	 * For internal/synthetic nodes that aren't the externally-visible entry point of any single
	 * PatternConstruct -- e.g. the plain alternation dispatch wrapped inside a capturing loop's or
	 * capturing union's Begin/EndCapture pair (see {@code QuantifiableConstruct.buildLoopMatcher}
	 * and {@code QuantifiedUnion.buildMatcher}). Skips self-registration since there's no single
	 * owning construct to register into, so the caller must supply the local flags directly
	 * (normally the owning construct's own {@code flags}); never itself a chain candidate, so
	 * {@code entrySet}/{@code failedEntry} are always {@code null} here.
	 */
	MatcherConstruct(int flags) {
		this.flags = flags;
		this.entrySet = null;
		this.failedEntry = null;
	}

	/**
	 * Checks {@link #entrySet} (if any), deferring to {@link #failedEntry} on a miss, then runs
	 * {@link #matchBody}. See the class doc's "Flattened dispatch" section -- this is the one place
	 * the fork that used to be {@code ForkingMatcherConstruct}'s own job now lives, folded into
	 * every node instead of a separate node type.
	 */
	final boolean match(Matcher matcher, int peeked) {
		if (containsEntry(entrySet, peeked)) {
			return matchBody(matcher, peeked);
		}
		return failedEntry != null && failedEntry.match(matcher, peeked);
	}

	/** This node's own matching behavior, run only once {@link #entrySet} (if any) has passed. */
	abstract boolean matchBody(Matcher matcher, int peeked);

	/**
	 * Plain (unfolded) membership in {@code entrySet}, {@code null} treated as "always matches" (no
	 * gating at all -- the overwhelming majority of nodes). {@code -1} (Matcher's "no more input"
	 * sentinel -- see {@code Matcher#peek}) is never a member of any real {@code entrySet}, same
	 * guard as {@link #containsFolded} -- an inverted set's fill must not report it "in".
	 * Deliberately not {@link #containsFolded}: {@code entrySet} already has any CASE_INSENSITIVE
	 * folding baked in at chain-construction time -- see {@code PatternConstruct#effectiveEntrySet}
	 * and this class's own doc.
	 */
	private static boolean containsEntry(@Nullable CodePointSet entrySet, int peeked) {
		return entrySet == null || (peeked != -1 && entrySet.contains(peeked));
	}

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
	 * member of {@code ranges}. Used by a node's own {@link #matchBody} where it still needs a real,
	 * runtime-folded membership test against its own (unfolded) data -- {@link
	 * SingleCharMatcherConstruct} being the main example -- as opposed to {@link #entrySet}'s
	 * already-fold-baked, plain-{@code contains} check (see {@link #containsEntry}).
	 */
	static boolean containsFolded(CodePointSet ranges, int peeked, int flags) {
		// -1 (Matcher's "no more input" sentinel -- see Matcher#peek) is checked FIRST and
		// unconditionally: a negated class (e.g. "[^a-z]") is an inverted set, and its fill
		// legitimately covers every real code point it doesn't explicitly exclude -- membership
		// here has to see that fill (hence contains(), a real query), but -1 is never a real code
		// point, so it must never be reported a "member" of even a total (inverted) set. This is the
		// same domain guard ComplexCharacter#validRanges() used to provide via clamping a Guava
		// RangeSet.
		if (peeked == -1) {
			return false;
		}
		if (ranges.contains(peeked)) {
			return true;
		}
		if ((flags & Ll1Pattern.CASE_INSENSITIVE) == 0) {
			return false;
		}
		boolean unicode = (flags & Ll1Pattern.UNICODE_CASE) != 0;
		int upper = unicode ? Character.toUpperCase(peeked) : foldAsciiUpper(peeked);
		int lower = unicode ? Character.toLowerCase(peeked) : foldAsciiLower(peeked);
		return (upper != peeked && ranges.contains(upper)) || (lower != peeked && ranges.contains(lower));
	}

	/**
	 * {@code exact}, plus (under {@code CASE_INSENSITIVE}) every other-case fold of each of its
	 * members that isn't already claimed -- exactly -- by {@code excludeFromFold}. This is what
	 * lets a chain-candidate node's {@link #entrySet} be checked with a plain, unfolded {@link
	 * #containsEntry} at match time (see this class's own doc) while still enforcing the rule that
	 * an exact match anywhere in the chain beats a folded match earlier in it: {@code
	 * excludeFromFold} is the union of every OTHER candidate's own exact entry set in the same
	 * chain (see {@code PatternConstruct}'s chain-building call sites), so this candidate's folded
	 * claim on a code point another candidate exactly owns is dropped, leaving that code point free
	 * for the exact owner's own (unfolded) {@code entrySet} to claim instead. A fold collision
	 * between two candidates' folded (non-exact) claims is deliberately NOT resolved here -- that's
	 * settled by ordinary chain priority (whichever candidate's {@code entrySet} is checked first
	 * wins), same as the old two-pass fork-chain design.
	 *
	 * <p>No-op (returns {@code exact} directly, no allocation) when {@code flags} isn't {@code
	 * CASE_INSENSITIVE} -- the common case, and the whole point of baking folding in here rather
	 * than re-checking it on every match attempt.
	 */
	static CodePointSet effectiveEntrySet(CodePointSet exact, @Nullable CodePointSet excludeFromFold, int flags) {
		if ((flags & Ll1Pattern.CASE_INSENSITIVE) == 0) {
			return exact;
		}
		MutableCodePointSet result = new ArrayCodePointSet();
		result.addAll(exact);
		boolean unicode = (flags & Ll1Pattern.UNICODE_CASE) != 0;
		exact.forEachRange((min, max) -> {
			for (int cp = min; cp < max; cp++) {
				addFoldUnlessExcluded(result, cp, unicode ? Character.toUpperCase(cp) : foldAsciiUpper(cp), excludeFromFold);
				addFoldUnlessExcluded(result, cp, unicode ? Character.toLowerCase(cp) : foldAsciiLower(cp), excludeFromFold);
			}
		});
		return result;
	}

	private static void addFoldUnlessExcluded(
			MutableCodePointSet result, int original, int folded, @Nullable CodePointSet excludeFromFold) {
		if (folded != original && (excludeFromFold == null || !excludeFromFold.contains(folded))) {
			result.add(folded);
		}
	}

	/**
	 * Sets {@code owner.matcher} to {@code target} directly when {@code owner} has no dispatch
	 * gating of its own ({@code owner.dispatchEntrySet}/{@code owner.dispatchFailedEntry} both
	 * null -- the common case), or wraps it in a {@link PassThroughMatcherConstruct} when it does.
	 * Needed anywhere a construct's own {@code buildMatcher()} would otherwise just alias {@code
	 * matcher = someOtherConstruct.matcher} (e.g. {@code Sequence}, a bare flags-only {@code
	 * QuantifiedUnion}, {@code buildFlattenedChain}'s own {@code owner} handling): {@code target}
	 * may already be fully compiled (or, for a chain's own head, gated for an INNER reason
	 * unrelated to {@code owner}'s own OUTER gating), so retrofitting {@code owner}'s dispatch
	 * fields onto it after the fact wouldn't work -- {@code owner}'s gating has to live on a node
	 * of its own instead.
	 */
	static MatcherConstruct aliasOrPassThrough(PatternConstruct owner, MatcherConstruct target) {
		if (owner.dispatchEntrySet == null && owner.dispatchFailedEntry == null) {
			owner.matcher = target;
			return target;
		}
		return new PassThroughMatcherConstruct(owner, target);
	}

	/**
	 * A zero-width forwarding node -- see {@link #aliasOrPassThrough}'s own doc for when this is
	 * needed instead of a plain alias.
	 */
	static final class PassThroughMatcherConstruct extends SingleDispatchingMatcherConstruct {
		PassThroughMatcherConstruct(PatternConstruct owner, MatcherConstruct next) {
			super(owner, next);
		}

		@Override
		boolean matchBody(Matcher matcher, int peeked) {
			return matchNext(matcher, peeked);
		}
	}

	/**
	 * Base class for the (large majority of) nodes that only ever have one possible successor,
	 * known at construction time -- matching input at that node never depends on *which* character
	 * was seen to decide where to go next, only whether matching succeeded at all.
	 *
	 * <p>{@code next} is safe to be a plain {@code final MatcherConstruct} here because none of this
	 * class's subclasses are ever the self-referential node in a cycle -- that role belongs
	 * exclusively to a loop's own {@link LoopMatcherConstruct} (which sidesteps needing a mutable
	 * field of its own by indirecting through a {@code PatternConstruct}'s {@code matcher} field
	 * instead -- see its own doc) -- so a Single-dispatching node's successor is always some other
	 * node that's already fully built (or at least already self-registered) by the time this
	 * constructor runs.
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
	 * Matches exactly one code point against {@code validRanges} (a character class -- {@code .}, a
	 * literal single character, or {@code [...]}), then advances to whatever comes next. A pure
	 * membership test, not a dispatch: every member character leads to the same single successor.
	 */
	static final class SingleCharMatcherConstruct extends SingleDispatchingMatcherConstruct {
		final CodePointSet validRanges;

		SingleCharMatcherConstruct(ComplexCharacter owner) {
			super(owner, owner.next.matcher);
			this.validRanges = owner.validRanges();
		}

		@Override
		boolean matchBody(Matcher matcher, int peeked) {
			if (!containsFolded(validRanges, peeked, flags)) {
				return false;
			}
			return matchNext(matcher, matcher.consume1CodePoint());
		}
	}

	/**
	 * Matches a fixed literal string exactly, then advances to whatever comes next. Compares the
	 * whole {@code value} against the input in one call rather than code-point-at-a-time:
	 * {@code String#regionMatches} is a JIT intrinsic on every JVM this project targets, so this is
	 * both simpler and faster than the character-loop version it replaced. Case-insensitive matching
	 * still needs two different strategies, since {@code regionMatches(true, ...)}'s notion of
	 * "ignore case" is full Unicode case-folding (via {@code Character#toUpperCase}/{@code
	 * #toLowerCase}) -- exactly {@code UNICODE_CASE}'s own definition, but NOT what plain {@code
	 * CASE_INSENSITIVE} (without {@code UNICODE_CASE}) means here: that's ASCII-only folding (see
	 * {@link #foldAsciiUpper}/{@link #foldAsciiLower}), which leaves non-ASCII characters alone --
	 * something {@code regionMatches(true, ...)} would get wrong (e.g. folding a non-ASCII character
	 * that has a Unicode case mapping but no ASCII one). That case falls back to a manual per-{@code
	 * char} loop (not per-code-point: ASCII folding never touches anything outside {@code a-z}/{@code
	 * A-Z}, so treating a surrogate pair as two separate {@code char}s compares correctly without
	 * ever needing to decode one).
	 */
	static final class LiteralMatcherConstruct extends SingleDispatchingMatcherConstruct {
		// A real String, not the CharSequence LiteralString.value itself may be (a zero-copy
		// CharBuffer view, for a literal run PatternParser could read straight off the pattern
		// text -- see that field's own doc): LiteralString.buildMatcher() calls value.toString()
		// once per compile to get here, deliberately, so match() below -- called once per match
		// *attempt*, not once per compile -- can use String#regionMatches, a real JIT intrinsic
		// (vectorized comparison), plus String#charAt/length's direct field/array reads. A
		// CharSequence-typed `value` here once meant a hand-written per-char loop instead (no
		// intrinsic) for every case below, which measurably cost real match-time CPU on Android
		// (java.nio.CharBuffer's own charAt/length aren't free either) for a win that only ever
		// existed at compile time -- not worth paying for on every match attempt afterward.
		final String value;

		LiteralMatcherConstruct(PatternConstruct owner, String value) {
			super(owner, owner.next.matcher);
			this.value = value;
		}

		boolean matchBody(Matcher matcher, int peeked) {
			int end = matcher.pos + value.length();
			if (end > matcher.regionEnd) {
				return false;
			}
			boolean matches;
			if ((flags & Ll1Pattern.CASE_INSENSITIVE) == 0) {
				matches = matcher.input.regionMatches(matcher.pos, value, 0, value.length());
			} else if ((flags & Ll1Pattern.UNICODE_CASE) != 0) {
				matches = matcher.input.regionMatches(true, matcher.pos, value, 0, value.length());
			} else {
				matches = asciiFoldRegionMatches(matcher.input, matcher.pos, value);
			}
			if (!matches) {
				return false;
			}
			if (end < matcher.regionEnd
					&& Character.isHighSurrogate(value.charAt(value.length() - 1))
					&& Character.isLowSurrogate(matcher.input.charAt(end))) {
				// `value` ends on an unpaired high surrogate, but the input keeps going with a real
				// low surrogate right there -- the input's actual code point at this position is the
				// combined supplementary one, not the lone surrogate `value` means to match. Every
				// other position is safe (two positions' raw units can only agree if their
				// surrogate-pairing structure agrees too, since pairing is a pure function of the
				// unit values themselves); only right at `value`'s own end does the comparison above
				// stop looking one unit before it would matter.
				return false;
			}
			return matchNext(matcher, matcher.consumeCodeUnits(value.length()));
		}

		private static boolean asciiFoldRegionMatches(String input, int offset, String value) {
			int len = value.length();
			for (int i = 0; i < len; i++) {
				char a = input.charAt(offset + i);
				char b = value.charAt(i);
				if (a != b && foldAsciiUpper(a) != foldAsciiUpper(b)) {
					return false;
				}
			}
			return true;
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
		boolean matchBody(Matcher matcher, int peeked) {
			int base = captureConstructIndex * 2;
			int start = matcher.captureGroups[base];
			int end = matcher.captureGroups[base + 1];
			if (start < 0) {
				// The referenced group never participated in the match (e.g. it's in a sibling
				// alternation branch that wasn't taken) -- java.util.regex treats an unparticipated
				// group's backreference as never matching, not as matching the empty string.
				return false;
			}
			if (start == end) {
				return matchNext(matcher, peeked);
			}
			// Compared straight against matcher.input by index rather than materializing the
			// captured text as its own String/CharSequence first -- there's nothing here that needs
			// one, and a backreference can be matched repeatedly (e.g. inside a loop), so avoiding an
			// allocation per comparison (not just per capture) matters more than it would for a
			// one-shot use.
			String input = matcher.input;
			int i = start;
			do {
				int next = input.codePointAt(i);
				int units = Character.isSupplementaryCodePoint(next) ? 2 : 1;
				if (!codePointsMatch(next, peeked, flags)) {
					return false;
				}
				peeked = matcher.consumeCodeUnits(units);
				i += units;
			} while (i < end);
			return matchNext(matcher, peeked);
		}
	}

	/**
	 * Length (in chars) of the line terminator starting at {@code input.charAt(index)}, or 0 if
	 * there isn't one there -- {@code "\r\n"} counts as a single 2-char terminator, matching
	 * {@code java.util.regex}'s default (non-{@code UNIX_LINES}) set: {@code \n}, {@code \r},
	 * {@code \r\n}, {@code }, {@code  }, {@code  }. Under {@code UNIX_LINES},
	 * only {@code \n} counts. Never looks past {@code limit} (the region end, per this engine's
	 * "opaque bounds" stance -- see {@code Matcher#peekPrevious()}). Shared by {@link
	 * BoundaryMatcherConstruct} ({@code \Z}) and {@link LineBoundaryMatcherConstruct} ({@code $}).
	 */
	private static int lineTerminatorLengthAt(Matcher matcher, int flags) {
		if (matcher.pos >= matcher.regionEnd) {
			return 0;
		}
		// matcher.peeked, not input.charAt(index): both call sites always pass matcher.pos as
		// `index`, and every char this checks against is BMP, so the already-computed code point
		// at that position (see Matcher.peeked's own doc) doubles as the char directly -- one
		// fewer input.charAt/codePointAt call on this method's own hot path.
		int c = matcher.peeked;
		if (c == '\n') {
			return 1;
		}
		if ((flags & Ll1Pattern.UNIX_LINES) != 0) {
			return 0;
		}
		if (c == '\r') {
			return (matcher.pos + 1 < matcher.regionEnd && matcher.input.charAt(matcher.pos + 1) == '\n') ? 2 : 1;
		}
		return (c == '' || c == ' ' || c == ' ') ? 1 : 0;
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
		return (c == '' || c == ' ' || c == ' ') ? 1 : 0;
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
		int len = lineTerminatorLengthAt(matcher, flags);
		return len > 0 && matcher.pos + len == matcher.regionEnd;
	}

	static final class BoundaryMatcherConstruct extends SingleDispatchingMatcherConstruct {
		final BoundaryEnum type;

		BoundaryMatcherConstruct(PatternConstruct owner, BoundaryEnum type) {
			super(owner, owner.next.matcher);
			this.type = type;
		}

		@Override
		boolean matchBody(Matcher matcher, int peeked) {
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
		boolean matchBody(Matcher matcher, int peeked) {
			boolean matchesHere;
			if (isLineBegin) {
				matchesHere = matcher.pos == matcher.regionStart
						|| ((flags & Ll1Pattern.MULTILINE) != 0
								&& lineTerminatorLengthBefore(matcher.input, matcher.pos, matcher.regionStart, matcher.regionEnd, flags) > 0);
			} else {
				matchesHere = (flags & Ll1Pattern.MULTILINE) == 0
						? matchesEndExceptTerminator(matcher, flags)
						: (matcher.pos == matcher.regionEnd
								|| lineTerminatorLengthAt(matcher, flags) > 0);
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
		/** Whether {@code matchBody()} needs to independently check {@code matcher.peekPrevious()}. */
		enum PriorWordBoundaryMatchType {
			Unchecked,
			PriorMustBeWord,
			PriorMustBeNonWord
		}

		/**
		 * Whether/how {@code matchBody()} needs to check {@code peeked} -- either against a fixed
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

		final CodePointSet wordSet;
		final PriorWordBoundaryMatchType priorMustBeWord;
		final PeekWordBoundaryMatchType peekMustBeWord;

		WordBoundaryMatcherConstruct(
				PatternConstruct owner,
				CodePointSet wordSet,
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

		// Static, with `wordSet` passed as a parameter, rather than an instance method reading
		// `this.wordSet` -- part of the same experiment as ArrayCodePointSet#floorIndex (see its own
		// doc); no measurable difference found here either (see notes.md's dated entry).
		private static boolean isWordChar(CodePointSet wordSet, int codePoint) {
			return codePoint >= 0 && wordSet.contains(codePoint);
		}

		@Override
		boolean matchBody(Matcher matcher, int peeked) {
			// peekPrevious() is only actually called when some check below needs it -- checkPrior
			// is exactly that: either the prior side has a fixed target of its own, or the peek
			// side needs to compare against it. checkPeek is the mirror image, for symmetry/clarity
			// (peeked itself is already available for free, but isWordChar(peeked) is not free).
			boolean checkPrior = priorMustBeWord != PriorWordBoundaryMatchType.Unchecked
					|| peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeSameAsPrior
					|| peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeOppositePrior;
			boolean priorIsWord = checkPrior && isWordChar(wordSet, matcher.peekPrevious());
			boolean checkPeek = peekMustBeWord != PeekWordBoundaryMatchType.Unchecked;
			boolean peekIsWord = checkPeek && isWordChar(wordSet, peeked);

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
	 * QuantifiableConstruct.buildLoopMatcher}), never as the loop's actual entry point (a loop's
	 * body chain head IS its own entry point in this flattened design -- see this class's own doc
	 * and {@code QuantifiableConstruct.buildLoopMatcher}'s doc for why no separate entry chain is
	 * needed any more). Conceptually: "one more body iteration just finished -- continue (retry the
	 * body) if under {@code max}, otherwise force an exit (via {@link #exitNode}, which itself
	 * enforces {@code min})." Neither this node nor {@link #exitNode} test code-point membership at
	 * all any more -- that's entirely the body chain's own {@link #entrySet}/{@link #failedEntry}
	 * job now (the body naturally defers to {@code exitNode} on its own when it doesn't match,
	 * whether that's the very first attempt or a re-check after {@code min} iterations) -- see
	 * design.md's "Quantifier/loop compilation" section for the up-to-date picture.
	 *
	 * <p>Kept as its own top-level class rather than folded into the body chain directly, because
	 * its "continue" successor -- the body chain's own head -- genuinely isn't known until AFTER
	 * this node has already self-registered onto the {@link PatternConstruct.LoopBackMarker} it
	 * owns (breaking the construction-time cycle every loop body creates: the body's own compiled
	 * matcher loops back to this very node). Rather than adding a mutable field to sidestep that,
	 * {@code continuation} is a plain {@code final PatternConstruct} reference, and {@code
	 * matchBody()} reads {@code continuation.matcher} -- reusing the SAME self-registration
	 * mechanism every other {@code PatternConstruct}/{@code MatcherConstruct} pair in this codebase
	 * already relies on ({@code PatternConstruct.matcher} is the one place in this whole design
	 * that's allowed to be filled in after the fact) instead of inventing a second one scoped to
	 * this class.
	 */
	static final class LoopMatcherConstruct extends MatcherConstruct {
		final int quantifiableIndex;
		final int max;
		final PatternConstruct continuation;
		final MatcherConstruct exitNode;

		LoopMatcherConstruct(
				PatternConstruct owner, int quantifiableIndex, int max,
				PatternConstruct continuation, MatcherConstruct exitNode) {
			super(owner);
			this.quantifiableIndex = quantifiableIndex;
			this.max = max;
			this.continuation = continuation;
			this.exitNode = exitNode;
		}

		@Override
		boolean matchBody(Matcher matcher, int peeked) {
			// Unconditional: reaching this node at all means a body pass (the very first, or another
			// re-check after a prior successful one) has just finished, so this always represents one
			// more completed iteration -- regardless of which way the choice below then decides to go.
			int loopCount = ++matcher.quantifiableCounts[quantifiableIndex];
			return loopCount < max
					? continuation.matcher.match(matcher, peeked)
					: exitNode.match(matcher, peeked);
		}

		@VisibleForTesting
		MatcherConstruct getContinuation() { return continuation.matcher; }
	}

	/**
	 * A loop's own "stop iterating" node -- reached either because the body chain (see {@link
	 * LoopMatcherConstruct}'s doc) naturally didn't match at all, or because {@link
	 * LoopMatcherConstruct} forced a stop after {@code max} iterations. Enforces {@code min}
	 * (failing the whole match if too few iterations happened) and, on success, resets the shared
	 * counter before dispatching to whatever really follows the loop -- see design.md's
	 * "Quantifier/loop compilation" section. Never gated by its own {@code entrySet}/{@code
	 * failedEntry} (always {@code null}) -- every code-point decision that used to live in a
	 * combined loop/exit dispatch table now lives entirely in the body chain's own entry checks.
	 */
	static final class LoopMatcherExit extends MatcherConstruct {
		final int quantifiableIndex;
		final int min;
		final MatcherConstruct next;

		LoopMatcherExit(int flags, int quantifiableIndex, int min, MatcherConstruct next) {
			super(flags);
			this.quantifiableIndex = quantifiableIndex;
			this.min = min;
			this.next = next;
		}

		@Override
		boolean matchBody(Matcher matcher, int peeked) {
			if (matcher.quantifiableCounts[quantifiableIndex] < min) {
				return false;
			}
			// Never backtracks, so a failed attempt aborts the whole match rather than retrying
			// with stale counter state -- this reset (only on the successful exit path) is enough
			// to guarantee the slot is already 0 whenever this loop is next freshly (re-)entered.
			matcher.quantifiableCounts[quantifiableIndex] = 0;
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
		boolean matchBody(Matcher matcher, int peeked) {
			int base = captureConstructIndex * 2;
			matcher.captureGroups[base] = matcher.pos;
			// Reset the end slot too: re-entering a capture inside a loop must fully overwrite the
			// previous iteration's entry, not just its start, or a stale end from that earlier
			// iteration would linger if (impossibly, given this engine's forward-only structure) this
			// iteration's own EndCaptureMatcherConstruct somehow didn't run.
			matcher.captureGroups[base + 1] = -1;
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
		boolean matchBody(Matcher matcher, int peeked) {
			// Just records the end index -- no substring materialized here anymore. The captured
			// text is built lazily by Matcher#group(int), only if a caller actually asks for it (see
			// allocation sampling in benchmarks/Intel-i7-9750H_llkMatch_alloc_sampling.txt), and
			// BackReferenceMatcherConstruct above compares directly against these indices without
			// ever needing a String/CharSequence view at all.
			matcher.captureGroups[captureConstructIndex * 2 + 1] = matcher.pos;
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
		boolean matchBody(Matcher matcher, int peeked) {
			return !matcher.requireFullMatch || matcher.pos == matcher.regionEnd;
		}
	}
}

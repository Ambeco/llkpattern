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
 * <p>{@code entrySet} is checked with a PLAIN {@link CodePointSet#contains} (see {@link
 * #containsEntry}) -- under {@code CASE_INSENSITIVE}, folding is baked into {@code entrySet} itself
 * at chain-construction time (see {@code MatcherConstruct#foldedEntrySet}). Because {@code
 * checkDisjoint} rejects any fold overlap between chain candidates at compile time (e.g. {@code
 * (?i:[a-z]+)X}), no chain priority ordering between folded and exact claims is ever needed. A
 * character class's own member set has its case folding baked in at parse time too (see {@link
 * CaseFolding}), so {@link SingleCharMatcherConstruct} is a plain membership test.
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
		// Miss path only: a gated dispatch that fails at end of input has looked past the end
		// (Matcher#hitEnd).
		if (peeked == -1) {
			matcher.hitEnd = true;
		}
		return failedEntry != null && failedEntry.match(matcher, peeked);
	}

	/** This node's own matching behavior, run only once {@link #entrySet} (if any) has passed. */
	abstract boolean matchBody(Matcher matcher, int peeked);

	/**
	 * Plain (unfolded) membership in {@code entrySet}, {@code null} treated as "always matches" (no
	 * gating at all -- the overwhelming majority of nodes). {@code -1} (Matcher's "no more input"
	 * sentinel -- see {@code Matcher#peek}) is never a member of any real {@code entrySet}, same
	 * guard as {@link SingleCharMatcherConstruct} -- an inverted set's fill must not report it "in".
	 * {@code entrySet} already has any CASE_INSENSITIVE folding baked in at chain-construction time
	 * -- see {@code PatternConstruct#checkDisjoint} and this class's own doc.
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
	 * {@code exact}, plus (under {@code CASE_INSENSITIVE}) every character in a case-equivalence class
	 * with one of its members (see {@link CaseFolding#expand}). This is what lets a chain-candidate node's {@link #entrySet} be checked with a plain,
	 * unfolded {@link #containsEntry} at match time (see this class's own doc). Also what {@code
	 * PatternConstruct#checkDisjoint} compares, so a fold collision between two candidates is a
	 * compile-time ambiguity like any other overlap, never resolved by chain priority.
	 *
	 * <p>No-op (returns {@code exact} directly, no allocation) when {@code flags} isn't {@code
	 * CASE_INSENSITIVE} -- the common case.
	 */
	static CodePointSet foldedEntrySet(CodePointSet exact, int flags) {
		if ((flags & Ll1Pattern.CASE_INSENSITIVE) == 0) {
			return exact;
		}
		return CaseFolding.expand(exact, CaseFolding.isUnicodeCase(flags));
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
			// -1 (Matcher's "no more input" sentinel -- see Matcher#peek) is never a real member, even
			// of a negated class whose fill would otherwise report it "in".
			if (peeked == -1 || !validRanges.contains(peeked)) {
				if (peeked == -1) {
					matcher.hitEnd = true;
				}
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
				// Only a hit-end if the input that IS left agrees with value so far -- a mismatch
				// before the end never reads that far (java.util.regex's Slice behaves the same).
				if (remainingInputIsPrefixOfValue(matcher)) {
					matcher.hitEnd = true;
				}
				return false;
			}
			boolean matches;
			if ((flags & Ll1Pattern.CASE_INSENSITIVE) == 0) {
				matches = matcher.input.regionMatches(matcher.pos, value, 0, value.length());
			} else if ((flags & Ll1Pattern.UNICODE_CASE) != 0) {
				matches = matcher.input.regionMatches(true, matcher.pos, value, 0, value.length());
			} else {
				matches = asciiFoldRegionMatches(matcher.input, matcher.pos, value, value.length());
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

		private boolean remainingInputIsPrefixOfValue(Matcher matcher) {
			int available = matcher.regionEnd - matcher.pos;
			if ((flags & Ll1Pattern.CASE_INSENSITIVE) == 0) {
				return matcher.input.regionMatches(matcher.pos, value, 0, available);
			} else if ((flags & Ll1Pattern.UNICODE_CASE) != 0) {
				return matcher.input.regionMatches(true, matcher.pos, value, 0, available);
			}
			return asciiFoldRegionMatches(matcher.input, matcher.pos, value, available);
		}

		private static boolean asciiFoldRegionMatches(String input, int offset, String value, int len) {
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
				// group's backreference as never matching, not as matching the empty string. Nothing
				// has been consumed yet, so -- as below -- it's safe to defer to failedEntry (this
				// node's own loop-exit/next-union-candidate, when it's a chain candidate at all)
				// rather than failing the whole match outright.
				return failedEntry != null && failedEntry.match(matcher, peeked);
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
					// java.util.regex's BackRef checks the whole group's length against the input
					// left BEFORE comparing anything, so a too-short remainder is a hit-end even if
					// it would also have mismatched.
					if (matcher.pos + (end - i) > matcher.regionEnd) {
						matcher.hitEnd = true;
					}
					// A mismatch on the very FIRST code point of this attempt (i == start) hasn't
					// consumed anything yet, so it's exactly as safe to defer to failedEntry (this
					// backreference's own loop-exit, when it's compiled as a loop body part -- see
					// QuantifiableConstruct.buildLoopMatcher's per-part dispatchFailedEntry wiring,
					// unchanged by this) as an entrySet miss would have been -- entrySet only gates on
					// the group's overall (possibly multi-valued) first-character set, e.g.
					// `([ab])\1?`, so this is the actual, precise check that set was too coarse to
					// make. A mismatch AFTER already consuming one or more matching code points of a
					// multi-character captured group, by contrast, has irreversibly committed input
					// this engine can't un-consume -- deliberately a hard failure here (`return
					// false`), the exact same "no backtracking" limitation as a plain multi-character
					// loop body failing mid-iteration (see KnownDivergenceTest's
					// multiCharLoopBodyThatFailsMidIterationIsNotRetried, and `ab(ab)?` vs "aba").
					return i == start && failedEntry != null && failedEntry.match(matcher, peeked);
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
		if (matcher.pos >= matcher.anchorEnd) {
			return 0;
		}
		// matcher.peeked, not input.charAt(index): both call sites always pass matcher.pos as
		// `index`, and every char this checks against is BMP, so the already-computed code point
		// at that position (see Matcher.peeked's own doc) doubles as the char directly -- one
		// fewer input.charAt/codePointAt call on this method's own hot path.
		// (Except when anchoring bounds are off and pos is at regionEnd, where peeked is the
		// end-of-region sentinel but the input goes on.)
		int c = matcher.pos < matcher.regionEnd ? matcher.peeked : matcher.input.charAt(matcher.pos);
		if (c == '\n') {
			return 1;
		}
		if ((flags & Ll1Pattern.UNIX_LINES) != 0) {
			return 0;
		}
		if (c == '\r') {
			return (matcher.pos + 1 < matcher.anchorEnd &&matcher.input.charAt(matcher.pos + 1) == '\n') ? 2 : 1;
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
		if (matcher.pos == matcher.anchorEnd) {
			return true;
		}
		int len = lineTerminatorLengthAt(matcher, flags);
		return len > 0 && matcher.pos + len == matcher.anchorEnd;
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
					matchesHere = matcher.pos == matcher.anchorStart;
					break;
				case InputEnd: // \z: always the true end of input, MULTILINE has no effect.
					matchesHere = matcher.pos == matcher.anchorEnd;
					break;
				case InputEndExceptTerminator: // \Z
					matchesHere = matchesEndExceptTerminator(matcher, flags);
					break;
				default:
					// Every BoundaryEnum value is handled above -- this is only reachable if a new one
					// is ever added without updating this switch.
					throw new AssertionError("Unhandled BoundaryEnum: " + type);
			}
			if (matchesHere && type != BoundaryEnum.InputBegin) {
				// \z only hits the end; \Z (like $) also could be broken by more input.
				matcher.hitEnd = true;
				matcher.requireEnd |= type == BoundaryEnum.InputEndExceptTerminator;
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
			boolean atEnd = false;
			if (isLineBegin) {
				if ((flags & Ll1Pattern.MULTILINE) != 0 && matcher.pos == matcher.anchorEnd) {
					// java.util.regex never matches a MULTILINE ^ at the end of input (even after a
					// terminator, or in empty input), and counts the attempt as hitting the end.
					matcher.hitEnd = true;
					return false;
				}
				matchesHere = matcher.pos == matcher.anchorStart
						|| ((flags & Ll1Pattern.MULTILINE) != 0
								&& lineTerminatorLengthBefore(matcher.input, matcher.pos, matcher.anchorStart, matcher.anchorEnd, flags) > 0);
			} else {
				// Without MULTILINE every $ match is at the end or before the final terminator, and
				// java.util.regex flags both; with it only an actual end-of-input match is flagged.
				if ((flags & Ll1Pattern.MULTILINE) == 0) {
					matchesHere = matchesEndExceptTerminator(matcher, flags);
					atEnd = matchesHere;
				} else {
					atEnd = matcher.pos == matcher.anchorEnd;
					matchesHere = atEnd || lineTerminatorLengthAt(matcher, flags) > 0;
				}
			}
			if (atEnd) {
				matcher.hitEnd = true;
				matcher.requireEnd = true;
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
		final boolean isWordBoundary; // true: \b, false: \B

		WordBoundaryMatcherConstruct(
				PatternConstruct owner,
				CodePointSet wordSet,
				PriorWordBoundaryMatchType priorMustBeWord,
				PeekWordBoundaryMatchType peekMustBeWord,
				boolean isWordBoundary) {
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
			this.isWordBoundary = isWordBoundary;
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
			int ahead = matcher.peekForBoundary();
			if (ahead == -1) {
				// java.util.regex's Bound looks at the character after the position even when this
				// engine's compile-time classification only needs the one before it.
				matcher.hitEnd = true;
				matcher.requireEnd = true;
			}
			if (peeked == -1 && ahead != -1) {
				// Transparent bounds, at regionEnd: the compile-time classification below assumes the
				// character next consumed is the one at pos, but nothing can consume past the region, so
				// test the real boundary here; whatever follows then fails (and flags hitEnd) on its own.
				boolean boundary = isWordChar(wordSet, matcher.peekPrevious()) != isWordChar(wordSet, ahead);
				return boundary == isWordBoundary && matchNext(matcher, peeked);
			}
			boolean checkPrior = priorMustBeWord !=PriorWordBoundaryMatchType.Unchecked
					|| peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeSameAsPrior
					|| peekMustBeWord == PeekWordBoundaryMatchType.PeekMustBeOppositePrior;
			boolean priorIsWord = checkPrior && isWordChar(wordSet, matcher.peekPrevious());
			boolean checkPeek = peekMustBeWord != PeekWordBoundaryMatchType.Unchecked;
			boolean peekIsWord = checkPeek && isWordChar(wordSet, ahead);

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

	/**
	 * A reluctant loop's own "prefer to stop here" check -- inserted (see {@code
	 * QuantifiableConstruct.buildLoopMatcher}) in place of an unconditional continue/loop-back, for
	 * exactly the cases where stopping early is provably safe: {@code min} has been satisfied AND
	 * {@link #exitNode}'s own continuation is a zero-width path that unconditionally reaches {@link
	 * EndMatcherConstruct} (see {@link #exitIsPureEnd}) -- so whether the exit actually succeeds
	 * only depends on {@link Matcher#requireFullMatch}, read here at match time since one compiled
	 * pattern serves {@code matches()}, {@code find()}, and {@code lookingAt()} alike. Never
	 * speculative: unlike a backtracking engine's "try the shorter match, undo if it fails" reluctant
	 * loop, this never runs {@link #exitNode} unless success is already guaranteed, so none of
	 * {@code exitNode}'s side effects (resetting the loop counter, ending an enclosing capture) ever
	 * need undoing.
	 */
	static final class ReluctantLoopGate extends MatcherConstruct {
		final int quantifiableIndex;
		final int min;
		final MatcherConstruct exitNode;
		final MatcherConstruct bodyHead;

		ReluctantLoopGate(
				int flags, int quantifiableIndex, int min, MatcherConstruct exitNode, MatcherConstruct bodyHead) {
			super(flags);
			this.quantifiableIndex = quantifiableIndex;
			this.min = min;
			this.exitNode = exitNode;
			this.bodyHead = bodyHead;
		}

		@Override
		boolean matchBody(Matcher matcher, int peeked) {
			// Under requireFullMatch (matches()), exiting is still safe once pos already reached
			// regionEnd -- exitIsPureEnd(next) already guarantees the rest of the pattern needs no
			// further input, so if there's none left to require, stopping here is exactly what a
			// backtracking engine's reluctant loop does too, and (unlike letting the body run one more,
			// doomed attempt) avoids spuriously peeking past the end and setting Matcher#hitEnd.
			if (matcher.quantifiableCounts[quantifiableIndex] >= min
					&& (!matcher.requireFullMatch || matcher.pos == matcher.regionEnd)) {
				return exitNode.match(matcher, peeked);
			}
			return bodyHead.match(matcher, peeked);
		}
	}

	/**
	 * Conservative check for whether {@code node} is a zero-width path that unconditionally reaches
	 * {@link EndMatcherConstruct} without depending on the next input code point -- i.e. whether
	 * taking it right now is guaranteed to succeed (modulo {@code requireFullMatch}, which the caller
	 * checks separately). Used only by {@link ReluctantLoopGate}'s construction, to decide whether a
	 * reluctant loop's exit path is safe to try eagerly instead of always continuing greedily.
	 * Deliberately conservative -- returns {@code false} (rather than trying to reason further) for
	 * anything not provably safe, such as a zero-width assertion ({@code $}, {@code \b}), a
	 * lookaround, or another loop that isn't a guaranteed no-op: a false negative here just leaves
	 * that shape greedy, this engine's existing (correct-for-{@code matches()}) default, never wrong.
	 */
	static boolean exitIsPureEnd(MatcherConstruct node) {
		// Checked FIRST, before any type-specific case below: a chain-candidate node's own entrySet
		// (e.g. the head of a following `b?`'s own body, OR a PassThroughMatcherConstruct standing in
		// for a gated owner -- see aliasOrPassThrough) always takes precedence over what that node
		// would otherwise do when its own gate misses. That entrySet was itself checked for
		// disjointness against OUR loop's body by the very checkDisjoint call that is about to gate
		// this loop (see QuantifiableConstruct#buildLoopMatcher's `extraEntrySet`) -- so whenever this
		// exit path is actually taken with a peeked code point that's in our body's own entry set,
		// this node's entrySet is guaranteed to miss, and the match cascades to failedEntry exactly as
		// if the body itself had failed to match and fallen through to this same exitNode naturally.
		// (For any OTHER peeked code point -- one our body wouldn't have consumed either -- taking
		// this node's own gated path directly, rather than falling through to it, is exactly what
		// should happen; either way, `failedEntry`'s own safety, not this node's `matchBody`, is what
		// this recursion needs to prove.) Recursing into failedEntry rather than stopping here is what
		// lets this see past an intervening optional part (`a+?b?`, `[ab]+?c?`) to the real zero-width
		// tail beyond it.
		if (node.entrySet != null) {
			return node.failedEntry != null && exitIsPureEnd(node.failedEntry);
		}
		if (node instanceof EndMatcherConstruct) {
			return true;
		}
		if (node instanceof EndCaptureMatcherConstruct) {
			return exitIsPureEnd(((EndCaptureMatcherConstruct) node).next);
		}
		if (node instanceof PassThroughMatcherConstruct) {
			return exitIsPureEnd(((PassThroughMatcherConstruct) node).next);
		}
		if (node instanceof LoopMatcherExit) {
			LoopMatcherExit exit = (LoopMatcherExit) node;
			return exit.min == 0 && exitIsPureEnd(exit.next);
		}
		if (node instanceof ReluctantLoopGate) {
			return exitIsPureEnd(((ReluctantLoopGate) node).exitNode);
		}
		return false;
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

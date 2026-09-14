package com.tbohne.llkpattern;

import com.google.common.annotations.VisibleForTesting;
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
 * handful of nodes that genuinely branch on the next code point -- a union's {@code |}, a loop's
 * own entry point, and a loop's own "keep looping vs. exit" choice -- are all built from chains of
 * {@link ForkingMatcherConstruct}, a plain 2-way if/else on set membership: there is no N-way
 * dispatch-table node anywhere in this engine. Per CLAUDE.md's rule for this class, every field on
 * every {@code MatcherConstruct} -- {@code ForkingMatcherConstruct} included -- stays genuinely
 * {@code final}; the one place a fork's successor genuinely isn't known until after it
 * self-registers to break a construction-time cycle (a loop's own back edge -- see {@link
 * LoopMatcherConstruct}) indirects through its owning {@code PatternConstruct}'s own already-mutable
 * {@code matcher} field instead of adding a mutable field here, so no node's own fields ever need
 * the "immutable by contract, not by the compiler" caveat this design used to require of its one
 * dispatch-table node.
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
	 * member of {@code ranges}. Used both by {@link SingleCharMatcherConstruct} (in place of a
	 * dispatch map: a character class has exactly one successor regardless of *which* member
	 * character was seen, so all it needs is a yes/no membership test) and by {@link
	 * ForkingMatcherConstruct}'s own membership test.
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
		boolean match(Matcher matcher, int peeked) {
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
		// A CharSequence, not a String -- see LiteralString.value's own doc for why (a zero-copy
		// CharBuffer view for the common case, rather than a materialized copy). This is also why
		// match() below can't just delegate to String.regionMatches(...) the way it used to:
		// String.regionMatches only accepts a String for the other side of the comparison, so every
		// case here (exact, Unicode-case-insensitive, ASCII-fold-insensitive) is now a manual
		// char-by-char loop instead.
		final CharSequence value;

		LiteralMatcherConstruct(PatternConstruct owner, CharSequence value) {
			super(owner, owner.next.matcher);
			this.value = value;
		}

		boolean match(Matcher matcher, int peeked) {
			int end = matcher.pos + value.length();
			if (end > matcher.regionEnd) {
				return false;
			}
			boolean matches;
			if ((flags & Ll1Pattern.CASE_INSENSITIVE) == 0) {
				matches = regionMatches(matcher.input, matcher.pos, value);
			} else if ((flags & Ll1Pattern.UNICODE_CASE) != 0) {
				matches = unicodeFoldRegionMatches(matcher.input, matcher.pos, value);
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

		private static boolean regionMatches(String input, int offset, CharSequence value) {
			int len = value.length();
			for (int i = 0; i < len; i++) {
				if (input.charAt(offset + i) != value.charAt(i)) {
					return false;
				}
			}
			return true;
		}

		// Same algorithm java.lang.String#regionMatches(true, ...) itself uses (a plain == check,
		// then upper-casing both sides, then also lower-casing the upper-cased pair) -- replicated
		// here since that method only accepts a String for the other side, EXCEPT for one thing
		// this per-char version can't just copy: java.lang.Character#toUpperCase(char)/toLowerCase(char)
		// can't handle supplementary code points at all (see their own doc -- a lone surrogate isn't
		// assigned any case mapping, so it folds to itself), yet String#regionMatches(true, ...)
		// still correctly case-folds e.g. Deseret letters. Its real implementation
		// (StringUTF16.compareCodePointCI, found while tracking down why a naive port of this
		// algorithm failed Deseret specifically) special-cases a surrogate pair on both sides by
		// combining it into one code point and folding that instead -- replicated below.
		private static boolean unicodeFoldRegionMatches(String input, int offset, CharSequence value) {
			int len = value.length();
			for (int i = 0; i < len; i++) {
				char a = input.charAt(offset + i);
				char b = value.charAt(i);
				if (Character.isHighSurrogate(b) && i + 1 < len && Character.isLowSurrogate(value.charAt(i + 1))
						&& Character.isHighSurrogate(a) && offset + i + 1 < input.length()
						&& Character.isLowSurrogate(input.charAt(offset + i + 1))) {
					int codePointA = Character.toCodePoint(a, input.charAt(offset + i + 1));
					int codePointB = Character.toCodePoint(b, value.charAt(i + 1));
					i++; // consumed both units of this pair, not just one
					if (codePointA == codePointB) {
						continue;
					}
					int upperCpA = Character.toUpperCase(codePointA);
					int upperCpB = Character.toUpperCase(codePointB);
					if (upperCpA == upperCpB || Character.toLowerCase(upperCpA) == Character.toLowerCase(upperCpB)) {
						continue;
					}
					return false;
				}
				if (a == b) {
					continue;
				}
				char upperA = Character.toUpperCase(a);
				char upperB = Character.toUpperCase(b);
				if (upperA == upperB) {
					continue;
				}
				if (Character.toLowerCase(upperA) == Character.toLowerCase(upperB)) {
					continue;
				}
				return false;
			}
			return true;
		}

		private static boolean asciiFoldRegionMatches(String input, int offset, CharSequence value) {
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
	 * A single 2-way fork: goes to {@code next} if the next code point is a member of {@code
	 * memberSet}, or to {@code otherwise} if not -- without consuming any input itself. Chains of
	 * these are how this engine now expresses every genuine branch on the next code point -- a plain
	 * union's {@code |} branches, and a loop's own entry point (see {@code
	 * PatternConstruct.buildForkChain}: one fork per candidate but the last, tried in order, falling
	 * through to {@code otherwise} -- either the next fork in the chain, or the final fallback
	 * target. The chain's actual tail (the last candidate) is never itself wrapped in a fork: when
	 * there's a real catch-all branch, that candidate becomes the second-to-last fork's own {@code
	 * otherwise} target directly; when there's no catch-all, the last candidate's own compiled
	 * matcher is dispatched to directly and unconditionally instead -- by definition of "not
	 * claiming a catch-all", that matcher already re-verifies membership as its own first action
	 * (whatever character/literal/nested check that involves), so a redundant wrapping fork would
	 * only ever end up testing something whose answer is already implied. A loop's own "keep
	 * looping vs. exit" choice is a separate, related node -- {@link LoopMatcherConstruct} -- kept
	 * as its own top-level class rather than a subclass of this one, since its one successor
	 * genuinely isn't known until after it self-registers to break a construction-time cycle; see
	 * that class's own doc for how it stays {@code final} anyway, by indirecting through a
	 * {@code PatternConstruct}'s own already-mutable {@code matcher} field instead of adding any new
	 * mutable-field escape hatch here. There is no N-way dispatch-table node anywhere in this engine
	 * any more; every branch, however many-way it conceptually is, is expressed as a chain of these
	 * 2-way forks.
	 *
	 * <p>Every field here is {@code final}, per CLAUDE.md's rule for this class: {@code
	 * buildForkChain} always builds tail-to-head, so every fork's successor is already fully
	 * resolved by the time that fork is constructed. Reuses each candidate's own already-computed
	 * {@code entryMap} as {@code memberSet} directly (no new {@code CodePointMap} allocation)
	 * wherever possible, so ambiguity between candidates is NOT re-checked here -- it's the
	 * caller's job (still {@code PatternConstruct.mergeEntryPoints}) to have already rejected any
	 * overlap between candidates before a chain is ever built from them; a fork chain's inherent
	 * first-wins priority would otherwise silently accept an ambiguous pattern.
	 */
	static final class ForkingMatcherConstruct extends MatcherConstruct {
		final CodePointSet memberSet;
		final MatcherConstruct next;
		final MatcherConstruct otherwise;

		/** Self-registering variant -- used for the head of a chain that is some construct's own matcher. */
		ForkingMatcherConstruct(PatternConstruct owner, CodePointSet memberSet, MatcherConstruct next, MatcherConstruct otherwise) {
			super(owner);
			this.memberSet = memberSet;
			this.next = next;
			this.otherwise = otherwise;
		}

		/** Internal (non-self-registering) variant -- every other fork in a chain. */
		ForkingMatcherConstruct(int flags, CodePointSet memberSet, MatcherConstruct next, MatcherConstruct otherwise) {
			super(flags);
			this.memberSet = memberSet;
			this.next = next;
			this.otherwise = otherwise;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			return containsFolded(memberSet, peeked, flags)
					? next.match(matcher, peeked)
					: otherwise.match(matcher, peeked);
		}

		@VisibleForTesting
		MatcherConstruct getNext() { return next; }

		@VisibleForTesting
		MatcherConstruct getOtherwise() { return otherwise; }
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
		boolean match(Matcher matcher, int peeked) {
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
	 * QuantifiableConstruct.buildLoopMatcher}), never as the loop's actual entry point (a separate
	 * {@link ForkingMatcherConstruct} chain built afterward handles the very first attempt -- see
	 * design.md's opcode section). Conceptually a 2-way fork on {@code FIRST(body)} (does the next
	 * code point belong to the loop body at all?): on membership this always means "continue" -- so
	 * it's exactly where the {@code max} bound gets enforced, before dispatching onward; on
	 * non-membership it always means "exit", so {@code min} is enforced (and the counter reset)
	 * before dispatching to {@code otherwise} (the loop's real {@code next.matcher}). Folding both
	 * bound checks into one node (rather than a separate continue-checking and exit-checking pair)
	 * works because, from this node's position, the fork's own two outcomes ARE exactly "continue"
	 * and "exit" -- there's no third possibility, and no need to compare the winning target's
	 * identity against anything the way an N-way dispatch table used to have to.
	 *
	 * <p>Kept as its own top-level class rather than a {@link ForkingMatcherConstruct} subclass,
	 * because its "continue" successor -- the body-part-selection chain -- genuinely isn't known
	 * until AFTER this node has already self-registered onto the {@link
	 * PatternConstruct.LoopBackMarker} it owns (breaking the construction-time cycle every loop
	 * body creates: the body's own compiled matcher loops back to this very node). Rather than
	 * adding a mutable field to sidestep that, {@code continuation} is a
	 * plain {@code final PatternConstruct} reference, and {@code match()} reads {@code
	 * continuation.matcher} -- reusing the SAME self-registration mechanism every other
	 * {@code PatternConstruct}/{@code MatcherConstruct} pair in this codebase already relies on
	 * ({@code PatternConstruct.matcher} is the one place in this whole design that's allowed to be
	 * filled in after the fact) instead of inventing a second one scoped to this class. By the time
	 * {@code match()} ever actually runs, {@code continuation.matcher} is guaranteed non-null --
	 * {@code QuantifiableConstruct.buildLoopMatcher} resolves it before compiling anything that
	 * could reach this node at match time.
	 *
	 * <p>{@code match()} deliberately does NOT just test {@code memberSet} the way a plain {@link
	 * ForkingMatcherConstruct} does: under {@code CASE_INSENSITIVE}, a literal immediately following
	 * the loop can be the folded counterpart of a body character (e.g. {@code (?i:[a-z]+)X} against
	 * {@code "ABCX"}: uppercase {@code X} folds to {@code x}, which IS in {@code [a-z]}) -- exact
	 * (unfolded) membership on EITHER side must win before folding gets a say at all, exactly like
	 * the old merged-dispatch-table design's single flat lookup used to check every candidate's real
	 * keys before ever trying a folded retry (see notes.md for how this was first found, while
	 * migrating this class off that table). {@code exitSet} (the real {@code next}'s own entry
	 * point) exists solely so the exact side of that priority can be checked without needing a
	 * combined table -- once exact fails on both sides, folding is only ever tried against {@code
	 * memberSet}: a fold-match against {@code exitSet} would still just mean "exit", which is already
	 * what happens by default once {@code memberSet} doesn't fold-match either.
	 */
	static final class LoopMatcherConstruct extends MatcherConstruct {
		final int quantifiableIndex;
		final int min;
		final int max;
		final CodePointSet memberSet;
		final PatternConstruct continuation;
		final MatcherConstruct otherwise;
		final CodePointSet exitSet;

		LoopMatcherConstruct(
				PatternConstruct owner, int quantifiableIndex, int min, int max,
				CodePointSet memberSet, PatternConstruct continuation, MatcherConstruct otherwise,
				CodePointSet exitSet) {
			super(owner);
			this.quantifiableIndex = quantifiableIndex;
			this.min = min;
			this.max = max;
			this.memberSet = memberSet;
			this.continuation = continuation;
			this.otherwise = otherwise;
			this.exitSet = exitSet;
		}

		@Override
		boolean match(Matcher matcher, int peeked) {
			// Unconditional: reaching this node at all means a body pass (the very first, or another
			// re-check after a prior successful one) has just finished, so this always represents one
			// more completed iteration -- regardless of which way the fork below then decides to go.
			int loopCount = ++matcher.quantifiableCounts[quantifiableIndex];
			boolean goContinue;
			if (peeked != -1 && memberSet.contains(peeked)) {
				goContinue = true; // exact (unfolded) body membership -- always wins outright.
			} else if (peeked != -1 && exitSet.contains(peeked)) {
				goContinue = false; // exact (unfolded) exit membership -- also wins outright.
			} else if (peeked != -1 && (flags & Ll1Pattern.CASE_INSENSITIVE) != 0) {
				// Neither side claims this code point exactly -- only now does CASE_INSENSITIVE
				// folding get a say, checked against `memberSet` only (see class doc above).
				boolean unicode = (flags & Ll1Pattern.UNICODE_CASE) != 0;
				int upper = unicode ? Character.toUpperCase(peeked) : foldAsciiUpper(peeked);
				int lower = unicode ? Character.toLowerCase(peeked) : foldAsciiLower(peeked);
				goContinue = (upper != peeked && memberSet.contains(upper))
						|| (lower != peeked && memberSet.contains(lower));
			} else {
				goContinue = false;
			}
			if (goContinue) {
				return loopCount < max && continuation.matcher.match(matcher, peeked);
			}
			if (loopCount < min) {
				return false;
			}
			// Never backtracks, so a failed attempt aborts the whole match rather than retrying
			// with stale counter state -- this reset (only on the successful exit path) is enough
			// to guarantee the slot is already 0 whenever this loop is next freshly (re-)entered.
			matcher.quantifiableCounts[quantifiableIndex] = 0;
			return otherwise.match(matcher, peeked);
		}

		@VisibleForTesting
		MatcherConstruct getContinuation() { return continuation.matcher; }
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
		boolean match(Matcher matcher, int peeked) {
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
		boolean match(Matcher matcher, int peeked) {
			return !matcher.requireFullMatch || matcher.pos == matcher.regionEnd;
		}
	}
}

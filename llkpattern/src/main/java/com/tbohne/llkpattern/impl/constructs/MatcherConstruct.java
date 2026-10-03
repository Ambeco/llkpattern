package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.UnicodeFlags;
import com.tbohne.llkpattern.impl.unicode.CaseFolding;
import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.Ll1Pattern;
import com.tbohne.llkpattern.Matcher;

import com.google.common.annotations.VisibleForTesting;
import com.tbohne.llkpattern.impl.constructs.BoundaryPatternConstruct.BoundaryEnum;
import com.tbohne.llkpattern.impl.constructs.ComplexCharacterPatternConstruct;
import com.tbohne.llkpattern.impl.constructs.QuantifiedUnionPatternConstruct;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

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
public abstract class MatcherConstruct {
	// The CASE_INSENSITIVE/UNICODE_CASE/etc. flags in effect where this node's PatternConstruct was
	// parsed (see PatternConstruct#flags) -- NOT necessarily the pattern-wide
	// Ll1Pattern.compile(pattern, flags) flags. This is what makes an inline "(?i:...)" actually
	// scope case-insensitivity to just that group instead of the whole pattern: see "Inline flag
	// toggles don't actually locally scope anything" in remaining_work.md.
	final int flags;

	// See the class doc's "Flattened dispatch" section. Both null for the overwhelming majority of
	// nodes (anything not currently the head of a chain candidate) -- match() below then always
	// runs matchBody() unconditionally, identical to every node's old behavior.
	public final @Nullable CodePointSet entrySet;
	public final @Nullable MatcherConstruct failedEntry;

	// Folded in from the former SingleDispatchingMatcherConstruct (merged 2026-09-24 -- see that
	// class's old doc, kept below on the constructors that fill this in explicitly): the single,
	// statically-known successor for the large majority of nodes, whose own matchBody() calls
	// `next.match(matcher, peeked)` directly (no separate matchNext() wrapper -- one less frame on
	// the match-time call stack). A node with no single successor of its own (a loop's own
	// LoopMatcherConstruct/LoopExitMatcherConstruct/ReluctantLoopMatcherConstruct, each of which dispatches
	// via its own differently-named field(s) instead -- continuation/exitNode -- since it has more
	// than one possible successor; or EndMatcherConstruct, which has none at all) uses one of the
	// two below constructors that don't take a next, and gets `this` as a harmless, never-read
	// default -- see those constructors' own doc for why `this` (rather than `null`) is exactly
	// right here, including for EndMatcherConstruct specifically.
	final MatcherConstruct next;

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
	@SuppressWarnings("assignment") // `next = this`: a never-read self-loop, see the doc above
	MatcherConstruct(PatternConstruct owner) {
		owner.registerMatcher(this);
		this.flags = owner.flags;
		this.entrySet = owner.dispatchEntrySet;
		this.failedEntry = owner.dispatchFailedEntry;
		// `this` here is legal (unlike as an ARGUMENT to a super(...) call): we're already inside
		// MatcherConstruct's own constructor body, past Object's own super() call, so `this` refers
		// to the concrete subclass instance under construction -- e.g. EndMatcherConstruct, whose
		// matchBody() never reads `next` at all, making `next = this` a self-loop that's simply
		// never taken rather than a null a careless future reader might dereference.
		this.next = this;
	}

	/**
	 * For internal/synthetic nodes that aren't the externally-visible entry point of any single
	 * PatternConstruct -- e.g. the plain alternation dispatch wrapped inside a capturing loop's or
	 * capturing union's Begin/EndCapture pair (see {@code QuantifiablePatternConstruct.buildLoopMatcher}
	 * and {@code QuantifiedUnionPatternConstruct.buildMatcher}). Skips self-registration since there's no single
	 * owning construct to register into, so the caller must supply the local flags directly
	 * (normally the owning construct's own {@code flags}); never itself a chain candidate, so
	 * {@code entrySet}/{@code failedEntry} are always {@code null} here.
	 */
	@SuppressWarnings("assignment") // `next = this`: a never-read self-loop, see the doc above
	MatcherConstruct(int flags) {
		this.flags = flags;
		this.entrySet = null;
		this.failedEntry = null;
		this.next = this; // see the (PatternConstruct) constructor's own doc for why `this`.
	}

	/** Like {@link #MatcherConstruct(PatternConstruct)}, for a node whose own single, statically-
	 *  known successor is {@code next} -- folded in from the former SingleDispatchingMatcherConstruct
	 *  (see {@link #next}'s own doc). */
	MatcherConstruct(PatternConstruct owner, MatcherConstruct next) {
		owner.registerMatcher(this);
		this.flags = owner.flags;
		this.entrySet = owner.dispatchEntrySet;
		this.failedEntry = owner.dispatchFailedEntry;
		this.next = next;
	}

	/** Like {@link #MatcherConstruct(int)}, for a synthetic node whose own single, statically-known
	 *  successor is {@code next} -- folded in from the former SingleDispatchingMatcherConstruct (see
	 *  {@link #next}'s own doc). */
	MatcherConstruct(int flags, MatcherConstruct next) {
		this.flags = flags;
		this.entrySet = null;
		this.failedEntry = null;
		this.next = next;
	}

	@VisibleForTesting
	public MatcherConstruct getNext() { return next; }

	/**
	 * Checks {@link #entrySet} (if any), deferring to {@link #failedEntry} on a miss, then runs
	 * {@link #matchBody}. See the class doc's "Flattened dispatch" section -- this is the one place
	 * the fork that used to be {@code ForkingMatcherConstruct}'s own job now lives, folded into
	 * every node instead of a separate node type.
	 */
	public final boolean match(Matcher matcher, int peeked) {
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

	// Default: not a node that can be safely "seen through" for exitAssertionChain's reluctant-loop
	// early-exit safety proof -- overridden by EndMatcherConstruct (the successful base case) and
	// the handful of zero-width-assertion/wrapper types that ARE safely passable-through. A plain
	// virtual method here, rather than the `instanceof` chain this replaced (2026-09-27): matches
	// skipZeroWidthEntrySet's own conversion in PatternConstruct.java for the same reason -- see
	// that method's own doc and documents/notes.md's 2026-09-26 entries for the measured cost of
	// an ever-growing `instanceof` chain. Recurse via the static two-arg
	// `collectExitAssertionChain(node, chain)` helper below, not `next.collectExitAssertionChain
	// (chain)` directly, so `next`'s own `entrySet` precedence (checked by that static helper) is
	// still honored -- this method only ever runs for a node already confirmed to have no gating
	// `entrySet` of its own.
	boolean collectExitAssertionChain(List<ZeroWidthAssertionGuard> chain) {
		return false;
	}

	/**
	 * Plain (unfolded) membership in {@code entrySet}, {@code null} treated as "always matches" (no
	 * gating at all -- the overwhelming majority of nodes). {@code -1} (Matcher's "no more input"
	 * sentinel -- see {@code Matcher#peek}) is never a member of any real {@code entrySet}, same
	 * guard as {@link SingleCharMatcherConstruct} -- an inverted set's fill must not report it "in".
	 * {@code entrySet} already has any CASE_INSENSITIVE folding baked in at chain-construction time
	 * -- see {@code PatternConstruct#checkDisjoint} and this class's own doc.
	 */
	static boolean containsEntry(@Nullable CodePointSet entrySet, int peeked) {
		return entrySet == null || (peeked != -1 && entrySet.contains(peeked));
	}

	static int foldAsciiUpper(int codePoint) {
		return (codePoint >= 'a' && codePoint <= 'z') ? codePoint - ('a' - 'A') : codePoint;
	}

	static int foldAsciiLower(int codePoint) {
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
		if ((flags & UnicodeFlags.CASE_INSENSITIVE) == 0) {
			return false;
		}
		if ((flags & UnicodeFlags.UNICODE_CASE) != 0) {
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
		if ((flags & UnicodeFlags.CASE_INSENSITIVE) == 0) {
			return exact;
		}
		return CaseFolding.expand(exact, CaseFolding.isUnicodeCase(flags));
	}

	/**
	 * Sets {@code owner.matcher} to {@code target} directly when {@code owner} has no dispatch
	 * gating of its own ({@code owner.dispatchEntrySet}/{@code owner.dispatchFailedEntry} both
	 * null -- the common case), or wraps it in a {@link PassThroughMatcherConstruct} when it does.
	 * Needed anywhere a construct's own {@code buildMatcher()} would otherwise just alias {@code
	 * matcher = someOtherConstruct.matcher} (e.g. {@code SequencePatternConstruct}, a bare flags-only {@code
	 * QuantifiedUnionPatternConstruct}, {@code buildFlattenedChain}'s own {@code owner} handling): {@code target}
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

	// A single-successor node (the large majority of nodes below) just extends MatcherConstruct
	// directly, via the (PatternConstruct, MatcherConstruct)/(int, MatcherConstruct) constructors --
	// see MatcherConstruct's own class doc's "Flattened dispatch" section and #next's own doc
	// (former SingleDispatchingMatcherConstruct, merged into the base class 2026-09-24: matching
	// input at one of these nodes never depends on *which* character was seen to decide where to go
	// next, only whether matching succeeded at all, so there was no real behavior these classes
	// needed that MatcherConstruct itself couldn't just provide directly).
	//
	// next.match(...) is called as a plain virtual call, not via a MethodHandle -- an earlier version
	// of this design bound one via findSpecial per successor, on the theory that an
	// invokespecial-style direct call would let the JIT inline it more readily than an ordinary
	// virtual dispatch. Reverted 2026-09-07 (per the project owner, after discussion elsewhere): a
	// MethodHandle invocation on a non-static receiver isn't reliably inlined by any JVM, and is
	// frequently *slower* than a plain virtual call even on newer Android runtimes -- there was no
	// actual benefit to trade against the construction-time cost and the Java-8/Android-API-26
	// compatibility contortions (see design.md's "Direct-call MethodHandle binding" section for that
	// history, kept for the record even though the conclusion was to not do this).

	/**
	 * Matches exactly one code point against {@code validRanges} (a character class -- {@code .}, a
	 * literal single character, or {@code [...]}), then advances to whatever comes next. A pure
	 * membership test, not a dispatch: every member character leads to the same single successor.
	 */

	/**
	 * Matches {@code \X} -- one whole extended grapheme cluster starting at the current position,
	 * via {@link GraphemeCluster#nextBoundary}, then advances to whatever comes next. Always
	 * consumes at least one code point when there's any input left (a cluster is never empty), so
	 * {@code entrySet} is always a hit here -- this node's own membership test is really just "is
	 * there any input left at all," mirroring JDK 27's {@code Pattern.XGrapheme#match}: {@code
	 * hitEnd} is set only when there's no input left to start a cluster with, never merely because
	 * the consumed cluster happens to reach {@code regionEnd} (a faithful port, not a considered
	 * choice -- see that class's own doc for why).
	 */

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

	/**
	 * Matches whatever {@code captureConstructIndex}'s group actually captured last, then advances
	 * to whatever comes next -- {@code \1}/{@code \k<name>}, resolved to a fixed
	 * {@code captureConstructIndex} at parse time (see {@code BackReferencePatternConstruct}).
	 */

	/**
	 * Length (in chars) of the line terminator starting at {@code input.charAt(index)}, or 0 if
	 * there isn't one there -- {@code "\r\n"} counts as a single 2-char terminator, matching
	 * {@code java.util.regex}'s default (non-{@code UNIX_LINES}) set: {@code \n}, {@code \r},
	 * {@code \r\n}, {@code }, {@code  }, {@code  }. Under {@code UNIX_LINES},
	 * only {@code \n} counts. Never looks past {@code limit} (the region end, per this engine's
	 * "opaque bounds" stance -- see {@code Matcher#peekPrevious()}). Shared by {@link
	 * BoundaryMatcherConstruct} ({@code \Z}) and {@link LineBoundaryMatcherConstruct} ({@code $}).
	 */
	static int lineTerminatorLengthAt(Matcher matcher, int flags) {
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
			// The '\n' of a "\r\n" pair is not a terminator start: the pair is one unit, so $/\Z
			// must not hold between its halves (java.util.regex agrees).
			boolean secondHalfOfCrLf = (flags & Ll1Pattern.UNIX_LINES) == 0
					&& matcher.pos > matcher.anchorStart
					&& matcher.input.charAt(matcher.pos - 1) == '\r';
			return secondHalfOfCrLf ? 0 : 1;
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
	static int lineTerminatorLengthBefore(String input, int index, int floor, int limit, int flags) {
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
	static boolean matchesEndExceptTerminator(Matcher matcher, int flags) {
		if (matcher.pos == matcher.anchorEnd) {
			return true;
		}
		int len = lineTerminatorLengthAt(matcher, flags);
		return len > 0 && matcher.pos + len == matcher.anchorEnd;
	}

	/**
	 * A zero-width, side-effect-free match-time predicate for a construct that also has a full
	 * {@code SingleDispatchingMatcherConstruct#matchBody} of its own (which additionally sets
	 * {@code hitEnd}/{@code requireEnd} and dispatches to {@code next}) -- implemented by {@link
	 * WordBoundaryMatcherConstruct}, {@link LineBoundaryMatcherConstruct}, {@link
	 * LookbehindMatcherConstruct}, and {@link GraphemeBoundaryMatcherConstruct}, the assertion
	 * types position-dependent enough that {@link
	 * #exitAssertionChain} needs to evaluate them directly, in ADVANCE of actually committing to the
	 * exit path they gate (see that method's own doc, and {@link ReluctantLoopMatcherConstruct}'s).
	 * Never called from ordinary dispatch -- {@code matchBody} keeps its own independent (and
	 * pre-existing, differentially tested) logic rather than being rewritten in terms of this, to
	 * keep this addition isolated from that hot, already-correct path.
	 */

	/**
	 * Shared base for the {@link ZeroWidthAssertionGuard} implementers whose own {@link
	 * #collectExitAssertionChain} override is identical: add {@code this} to the chain (it already
	 * implements {@link ZeroWidthAssertionGuard}) and keep recursing through {@code next} --
	 * {@link WordBoundaryMatcherConstruct}, {@link LineBoundaryMatcherConstruct}, {@link
	 * LookbehindMatcherConstruct}, {@link GraphemeBoundaryMatcherConstruct}.
	 *
	 * <p>EXPERIMENTAL (2026-09-27, project owner's idea -- see remaining_work.md/notes.md): the
	 * {@code PatternConstruct} counterpart of this merge ({@code ZeroWidthAssertionPatternConstruct}) has
	 * the full rationale and caveats. Same idea here: one shared, {@code final} {@code
	 * collectExitAssertionChain} implementation instead of four separate (but identical) ones, so
	 * all four subclasses dispatch to the exact same compiled method -- an attempt to reduce that
	 * call site's megamorphism, not yet known whether it actually helps.
	 */

	/**
	 * {@code ^} (line begin) / {@code $} (line end): without {@code MULTILINE}, exactly {@code \A}/
	 * {@code \Z} (see {@link #matchesEndExceptTerminator}); under {@code MULTILINE}, {@code ^} also
	 * matches immediately after any line terminator ({@link #lineTerminatorLengthBefore}, a
	 * backward scan -- the mirror of {@code \Z}'s forward one) and {@code $} immediately before any
	 * line terminator ({@link #lineTerminatorLengthAt}). See design.md's "Boundary matching"
	 * section.
	 */

	/**
	 * {@code \b} (word boundary) / {@code \B} (non-word-boundary): unlike every other construct,
	 * whether this matches depends on the character just BEFORE the current position, not just the
	 * one at/after it -- see design.md's "Boundary matching" section. The general case needs to
	 * inspect both {@code matcher.peekPrevious()} and {@code peeked} and compare their "is this a
	 * word character" classifications; but per the project owner (2026-09-07), \b/\B very often sits
	 * next to a literal character or character class that is statically always-word or
	 * always-non-word, in which case only ONE side needs checking at match time. {@code
	 * WordBoundaryPatternConstruct.buildMatcher()} does that compile-time classification (and folds the fully
	 * statically-known case into either a compile error or a zero-width no-op, never even
	 * constructing one of these) -- this class just interprets whichever of the two enums below ended
	 * up not {@code Unchecked}.
	 */

	/**
	 * A {@code \b}/{@code \B} whose both neighbours were statically known and always satisfy it
	 * (see {@code WordBoundaryPatternConstruct.buildMatcher()}), so nothing needs checking at match time --
	 * except under transparent bounds at {@code regionEnd}, where the statically-known following
	 * character can't actually be consumed (nothing consumes past the region) and the real next
	 * character decides whether {@code java.util.regex} gets as far as flagging {@code hitEnd}.
	 */

	/**
	 * {@code (?<=X)}/{@code (?<!X)}, restricted at parse time to a body {@code X} that always
	 * matches exactly one code point -- see {@code LookbehindPatternConstruct}'s own doc
	 * and design.md's "Boundary matching" section. Unlike {@code WordBoundaryMatcherConstruct}, this
	 * never sets {@code hitEnd}/{@code requireEnd}: it only ever looks backward via {@code
	 * matcher.peekPrevious()}, so (unlike \b/\B, which also peeks forward) nothing about its result
	 * can change with more input ahead. When the body was wrapped in a capturing group ({@code
	 * captureConstructIndex >= 0}), this node "self-captures" the one code point behind {@code
	 * matcher.pos} directly -- writing {@code captureGroups[]} itself -- rather than going through
	 * the general {@code BeginCaptureMatcherConstruct}/{@code EndCaptureMatcherConstruct} machinery;
	 * that's safe here specifically because the body is provably exactly one code point wide, so
	 * there's no possibility of a partial (begin-without-end) write the way a general capturing
	 * construct has to guard against.
	 */

	/**
	 * {@code \b{g}} (grapheme boundary) -- see {@code GraphemeBoundaryPatternConstruct}'s
	 * own doc and design.md's "Extended grapheme clusters" section. Unlike {@code
	 * WordBoundaryMatcherConstruct}, there's no statically-known-neighbor optimization: the general
	 * check is always run. Three positions are handled without ever calling {@link
	 * GraphemeCluster#isBoundary} at all, mirroring JDK 27's own {@code Pattern.GraphemeBound}
	 * exactly: the true (region) start is always a boundary; past the true (region) end is always a
	 * boundary too, but additionally sets {@code hitEnd}/{@code requireEnd} (a longer suffix could
	 * always change a grapheme-boundary answer, unlike a 1-code-point lookbehind, which only ever
	 * looks backward); anywhere strictly between the two delegates to the real check.
	 */

	/**
	 * A loop's own "continue or stop at max" node, used for a GREEDY loop and also for a reluctant
	 * loop where stopping early isn't provably safe (see {@link ReluctantLoopMatcherConstruct} for
	 * the reluctant-safe counterpart, used instead of this one -- never both -- when it is).
	 * Compiled as a loop body's own continuation -- reached only that way (see
	 * {@code QuantifiablePatternConstruct.buildLoopMatcher}), never as the loop's actual entry point (a
	 * loop's body chain head IS its own entry point in this flattened design -- see this class's own
	 * doc and {@code QuantifiablePatternConstruct.buildLoopMatcher}'s doc for why no separate entry chain is
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
	 * this node has already self-registered onto the {@link LoopBackPatternConstruct} it
	 * owns (breaking the construction-time cycle every loop body creates: the body's own compiled
	 * matcher loops back to this very node). Rather than adding a mutable field to sidestep that,
	 * {@code continuation} is a plain {@code final PatternConstruct} reference, and {@code
	 * matchBody()} reads {@code continuation.matcher} -- reusing the SAME self-registration
	 * mechanism every other {@code PatternConstruct}/{@code MatcherConstruct} pair in this codebase
	 * already relies on ({@code PatternConstruct.matcher} is the one place in this whole design
	 * that's allowed to be filled in after the fact) instead of inventing a second one scoped to
	 * this class.
	 */

	/**
	 * EXPERIMENT (2026-09-24): a loop's own externally-visible entry point, used INSTEAD OF the body
	 * chain's head ({@code bodyHead}) directly, for the narrow case where {@code bodyHead}'s own
	 * {@link #entrySet} check is provably redundant on first entry -- see {@code
	 * QuantifiablePatternConstruct.buildLoopMatcher}'s own doc for the eligibility conditions (a single-
	 * alternative, non-capturing, {@code min >= 1} loop) and design.md's "LoopFirstEntryMatcherConstruct"
	 * section for why those conditions matter and can't currently be relaxed.
	 *
	 * <p>Calls {@code bodyHead.matchBody(...)} directly -- not {@code bodyHead.match(...)} -- so
	 * {@code bodyHead}'s own {@link #entrySet}/{@link #failedEntry} check never runs on this path.
	 * This node itself carries no gate of its own ({@code entrySet}/{@code failedEntry} both {@code
	 * null}, via the synthetic-node constructor) -- whatever already got THIS node its own call (an
	 * outer chain candidate's entrySet check via {@link #aliasOrPassThrough}, or nothing at all for
	 * an ungated top-level loop) already establishes everything {@code bodyHead}'s own check would
	 * have reconfirmed, by the eligibility conditions above. {@code bodyHead} itself is unchanged
	 * and keeps its own gate -- the loop-back re-entry path ({@code
	 * QuantifiablePatternConstruct.buildLoopMatcher}'s {@code continueMarker.matcher = bodyHead}) still goes
	 * straight to it, since THAT path has no such outer guarantee.
	 */

	/**
	 * The gate for a union branch that can END the whole pattern without consuming anything (a
	 * nullable tail, e.g. {@code a*} at the end of the pattern) -- see {@code
	 * PatternConstruct#elseIsEndOfFind}. Admits {@code peeked} if it is in {@code explicit} (the
	 * branch's real first characters), or if end-of-find is satisfied here: any position under
	 * {@code lookingAt()}/{@code find()} (a match may stop wherever it likes), only end of input under
	 * {@code matches()} -- the same mode split {@link EndMatcherConstruct} applies. Otherwise defers
	 * to {@code fallback} (the next sibling), exactly like an {@link #entrySet} miss. So
	 * {@code a*|b} on {@code "b"} is {@code ""} under {@code find()} and {@code "b"} under {@code
	 * matches()}, like {@code java.util.regex}. Its own {@code entrySet} is {@code null} (this node
	 * does the gating in {@link #matchBody}), and the branch behind it is compiled ungated.
	 */

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

	/**
	 * The reluctant counterpart to {@link LoopMatcherConstruct}, used INSTEAD of it (never both)
	 * for a reluctant loop where stopping early is provably safe -- {@code min} has been satisfied
	 * AND {@link #exitNode}'s own continuation is a zero-width path that unconditionally reaches
	 * {@link EndMatcherConstruct} (see {@link #exitIsPureEnd}), decided once at compile time by
	 * {@code QuantifiablePatternConstruct.buildLoopMatcher}. Unlike {@link LoopMatcherConstruct}, this node
	 * IS the loop's own externally-visible entry point as well as the body's loop-back continuation
	 * target (both roles resolve to the exact same instance) -- merging the two roles this way is
	 * what lets the "should I stop here" check run before the very first iteration too (needed for
	 * {@code min == 0} loops like {@code a*?}/{@code a??}, which must be able to skip the body
	 * entirely on the very first attempt), not just after each completed one.
	 *
	 * <p>Because this node is reached both as the fresh entry point (zero iterations done) and as
	 * the post-iteration continuation, {@link #quantifiableCounts}[idx] no longer counts completed
	 * iterations directly -- every VISIT increments it unconditionally, so its value is always
	 * {@code completedIterations + 1}. {@link #shiftedMin}/{@link #shiftedMax} are {@code min}/
	 * {@code max} pre-shifted by that same +1 (capped, not wrapped, for an unbounded {@code max})
	 * so every comparison against the shifted count stays correct without ever needing to tell the
	 * two invocation paths apart. The loop's own {@link LoopExitMatcherConstruct} (reached directly via the
	 * body's own {@code failedEntry} when it doesn't match at all, bypassing this node) is built
	 * with the SAME shifted {@code min} for consistency -- see
	 * {@code QuantifiablePatternConstruct.buildLoopMatcher}.
	 *
	 * <p>Whether the exit actually succeeds also depends on {@link Matcher#requireFullMatch}, read
	 * here at match time since one compiled pattern serves {@code matches()}, {@code find()}, and
	 * {@code lookingAt()} alike. Never speculative: unlike a backtracking engine's "try the shorter
	 * match, undo if it fails" reluctant loop, this never runs {@link #exitNode} unless success is
	 * already guaranteed, so none of {@code exitNode}'s side effects (resetting the loop counter,
	 * ending an enclosing capture) ever need undoing.
	 *
	 * <p>{@link #exitAssertionChain} (possibly empty, possibly {@code null} -- see {@link
	 * MatcherConstruct#exitAssertionChain}'s own doc) is this node's own extra, non-speculative
	 * safety check for the case where {@code exitNode}'s guaranteed-success proof runs through one
	 * or more {@code \b}/{@code \B}/{@code ^}/{@code $} assertions rather than reaching {@code End}
	 * directly: each guard is evaluated (side-effect-free, via {@code peek}/{@code peekPrevious})
	 * BEFORE this node commits to the exit, so a false result here just means try again -- continue
	 * greedily like any other not-yet-provable-safe iteration, never a rollback of anything.
	 */

	/**
	 * Conservative check for whether {@code node} is a zero-width path that unconditionally reaches
	 * {@link EndMatcherConstruct} without depending on the next input code point (the empty-chain
	 * case) OR reaches it after only a chain of {@code \b}/{@code \B}/{@code ^}/{@code $}/lookbehind
	 * zero-width assertions (the non-empty-chain case) -- i.e. whether taking it right now, or right after
	 * those assertions independently check out, is guaranteed to succeed (modulo {@code
	 * requireFullMatch}, which the caller checks separately). Used only when deciding whether to
	 * build a {@link ReluctantLoopMatcherConstruct} (instead of a plain {@link
	 * LoopMatcherConstruct}) for a reluctant loop, i.e. whether its exit path is safe to try eagerly
	 * instead of always continuing greedily -- see that class's own doc for how the returned chain
	 * is then evaluated at match time, via {@link ZeroWidthAssertionGuard#holdsHere}, BEFORE
	 * actually committing to the exit ({@code exitNode} in {@link ReluctantLoopMatcherConstruct}),
	 * never speculatively: design.md's "Speculative reluctant-loop exit with rollback" alternative
	 * explains why check-then-commit is safe here where a genuinely speculative try/roll-back
	 * wouldn't be.
	 *
	 * <p>Deliberately conservative -- returns {@code null} (rather than trying to reason further)
	 * for anything not provably safe, such as a lookaround or another loop that isn't a guaranteed
	 * no-op, or an assertion type other than the four handled below (e.g. {@code \A}/{@code \z}/
	 * {@code \Z}, whose own admission at an interior exit position isn't analyzed here -- out of
	 * scope for now, see remaining_work.md): a {@code null} result here just leaves that shape
	 * greedy, this engine's existing (correct-for-{@code matches()}) default, never wrong.
	 */
	final @Nullable List<ZeroWidthAssertionGuard> exitAssertionChain() {
		List<ZeroWidthAssertionGuard> chain = new ArrayList<>();
		return collectExitAssertionChain(this, chain) ? chain : null;
	}

	static boolean collectExitAssertionChain(
			MatcherConstruct node, List<ZeroWidthAssertionGuard> chain) {
		// Checked FIRST, before any type-specific dispatch: a chain-candidate node's own entrySet
		// (e.g. the head of a following `b?`'s own body, OR a PassThroughMatcherConstruct standing in
		// for a gated owner -- see aliasOrPassThrough) always takes precedence over what that node
		// would otherwise do when its own gate misses. That entrySet was itself checked for
		// disjointness against OUR loop's body by the very checkDisjoint call that is about to gate
		// this loop (see QuantifiablePatternConstruct#buildLoopMatcher's `extraEntrySet`) -- so whenever this
		// exit path is actually taken with a peeked code point that's in our body's own entry set,
		// this node's entrySet is guaranteed to miss, and the match cascades to failedEntry exactly as
		// if the body itself had failed to match and fallen through to this same exitNode naturally.
		// (For any OTHER peeked code point -- one our body wouldn't have consumed either -- taking
		// this node's own gated path directly, rather than falling through to it, is exactly what
		// should happen; either way, `failedEntry`'s own safety, not this node's `matchBody`, is what
		// this recursion needs to prove.) Recursing into failedEntry rather than stopping here is what
		// lets this see past an intervening optional part (`a+?b?`, `[ab]+?c?`) to the real zero-width
		// tail beyond it. This entrySet precedence is common to every node type, so it stays here
		// rather than being duplicated into each type-specific collectExitAssertionChain override
		// below -- only once that's settled does per-type dispatch (now a virtual call, not an
		// `instanceof` chain -- see that method's own doc) take over.
		if (node.entrySet != null) {
			return node.failedEntry != null && collectExitAssertionChain(node.failedEntry, chain);
		}
		return node.collectExitAssertionChain(chain);
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
}

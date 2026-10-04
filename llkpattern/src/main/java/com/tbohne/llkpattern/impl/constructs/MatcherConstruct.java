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
	// Flags in effect where the owning PatternConstruct was written (not the pattern-wide flags), so an
	// inline "(?i:...)" scopes case-insensitivity to its group.
	final int flags;

	// Non-null only on a chain candidate's head; see the class doc's "Flattened dispatch".
	public final @Nullable CodePointSet entrySet;
	public final @Nullable MatcherConstruct failedEntry;

	// The single successor for most nodes. Nodes without one (loops, EndMatcherConstruct) get `this`, never read.
	// Subclasses call next.match(...) as a plain virtual call: a MethodHandle was reverted (2026-09-07) as no
	// faster and not reliably inlined (design.md "Direct-call MethodHandle binding").
	final MatcherConstruct next;

	// Registers on `owner` FIRST, before resolving dependencies, to break compile cycles (see class doc);
	// the owner's dispatch fields become this node's entrySet/failedEntry.
	@SuppressWarnings("assignment") // `next = this`: a never-read self-loop, see the doc above
	MatcherConstruct(PatternConstruct owner) {
		owner.registerMatcher(this);
		this.flags = owner.flags;
		this.entrySet = owner.dispatchEntrySet;
		this.failedEntry = owner.dispatchFailedEntry;
		// Never-read self-loop for nodes (e.g. EndMatcherConstruct) with no successor, instead of a null.
		this.next = this;
	}

	// For synthetic nodes with no owning PatternConstruct (e.g. the dispatch inside a capture Begin/End
	// pair): no self-registration, so the caller supplies the flags; never a chain candidate.
	@SuppressWarnings("assignment") // `next = this`: a never-read self-loop, see the doc above
	MatcherConstruct(int flags) {
		this.flags = flags;
		this.entrySet = null;
		this.failedEntry = null;
		this.next = this;
	}

	MatcherConstruct(PatternConstruct owner, MatcherConstruct next) {
		owner.registerMatcher(this);
		this.flags = owner.flags;
		this.entrySet = owner.dispatchEntrySet;
		this.failedEntry = owner.dispatchFailedEntry;
		this.next = next;
	}

	MatcherConstruct(int flags, MatcherConstruct next) {
		this.flags = flags;
		this.entrySet = null;
		this.failedEntry = null;
		this.next = next;
	}

	@VisibleForTesting
	public final MatcherConstruct getNext() { return next; }

	// Checks entrySet (if any), deferring to failedEntry on a miss, then runs matchBody.
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

	// Whether a reluctant loop's exit can see through this node (see exitAssertionChain). Overridden by
	// EndMatcherConstruct and the passable zero-width types. Recurse via the static helper, not
	// next.collectExitAssertionChain, so next's own entrySet precedence is honored.
	boolean collectExitAssertionChain(List<ZeroWidthAssertionGuard> chain) {
		return false;
	}

	// Plain membership; null means "no gating". -1 (no input) is never a member, even of an inverted
	// set. CASE_INSENSITIVE folding is already baked into entrySet.
	private static boolean containsEntry(@Nullable CodePointSet entrySet, int peeked) {
		return entrySet == null || (peeked != -1 && entrySet.contains(peeked));
	}

	static int foldAsciiUpper(int codePoint) {
		return (codePoint >= 'a' && codePoint <= 'z') ? codePoint - ('a' - 'A') : codePoint;
	}

	private static int foldAsciiLower(int codePoint) {
		return (codePoint >= 'A' && codePoint <= 'Z') ? codePoint + ('a' - 'A') : codePoint;
	}

	// Whether two code points match under flags' CASE_INSENSITIVE/UNICODE_CASE, for literals compared directly.
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

	// `exact` plus, under CASE_INSENSITIVE, every character in a case-equivalence class with a member
	// (CaseFolding#expand). Lets entrySet use a plain contains(), and makes a fold collision between chain
	// candidates a compile-time ambiguity in checkDisjoint. Returns `exact` unchanged otherwise.
	static CodePointSet foldedEntrySet(CodePointSet exact, int flags) {
		if ((flags & UnicodeFlags.CASE_INSENSITIVE) == 0) {
			return exact;
		}
		return CaseFolding.expand(exact, CaseFolding.isUnicodeCase(flags));
	}

	// Sets owner.matcher to target directly, or wraps it in a PassThroughMatcherConstruct when owner has
	// dispatch gating of its own: target may already be compiled, or gated for an inner reason, so
	// owner's gating can't be retrofitted onto it.
	static MatcherConstruct aliasOrPassThrough(PatternConstruct owner, MatcherConstruct target) {
		if (owner.dispatchEntrySet == null && owner.dispatchFailedEntry == null) {
			owner.matcher = target;
			return target;
		}
		return new PassThroughMatcherConstruct(owner, target);
	}

	// Length (in chars) of the line terminator at matcher.pos, or 0. "\r\n" is one 2-char terminator;
	// under UNIX_LINES only '\n' counts. Never looks past the region (opaque bounds).
	static int lineTerminatorLengthAt(Matcher matcher, int flags) {
		if (matcher.pos >= matcher.anchorEnd) {
			return 0;
		}
		// matcher.peeked doubles as the char (every char checked is BMP), except with anchoring bounds off
		// at regionEnd, where peeked is the end sentinel but the input goes on.
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

	// Mirror of lineTerminatorLengthAt: length of the terminator ending exactly at index (for MULTILINE
	// ^). Never looks before `floor` or at/past `limit`.
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
			// A lone '\r' ends a terminator only if not followed by '\n', else it is half of a "\r\n" pair.
			boolean startsCrLf = index < limit && input.charAt(index) == '\n';
			return startsCrLf ? 0 : 1;
		}
		return (c == '' || c == ' ' || c == ' ') ? 1 : 0;
	}

	// \Z: end of input, or just before a final line terminator reaching exactly anchorEnd. Also $ without
	// MULTILINE.
	static boolean matchesEndExceptTerminator(Matcher matcher, int flags) {
		if (matcher.pos == matcher.anchorEnd) {
			return true;
		}
		int len = lineTerminatorLengthAt(matcher, flags);
		return len > 0 && matcher.pos + len == matcher.anchorEnd;
	}

	/**
	 * The chain of zero-width assertions through which this node unconditionally reaches {@link
	 * EndMatcherConstruct} (empty if it reaches End directly), or null if that isn't provable. Decides
	 * whether a reluctant loop may exit early (see {@link ReluctantLoopMatcherConstruct}). Each guard
	 * is checked via {@link ZeroWidthAssertionGuard#holdsHere} BEFORE committing to the exit, never
	 * speculatively (design.md "Speculative reluctant-loop exit with rollback").
	 *
	 * <p>Conservative: null for anything not provably safe, such as a lookaround, another loop that
	 * isn't a guaranteed no-op, or {@code \A}/{@code \z}/{@code \Z} (interior-exit admission isn't
	 * analyzed). That just leaves the shape greedy, the correct default.
	 */
	final @Nullable List<ZeroWidthAssertionGuard> exitAssertionChain() {
		List<ZeroWidthAssertionGuard> chain = new ArrayList<>();
		return collectExitAssertionChain(this, chain) ? chain : null;
	}

	static boolean collectExitAssertionChain(
			MatcherConstruct node, List<ZeroWidthAssertionGuard> chain) {
		// Checked FIRST: a chain candidate's own entrySet (e.g. the head of a following `b?`, or a
		// PassThroughMatcherConstruct for a gated owner) takes precedence over what the node would do on a
		// gate miss. checkDisjoint already proved it disjoint from OUR loop's body (see
		// QuantifiablePatternConstruct#buildLoopMatcher's extraEntrySet), so on this exit path the gate
		// misses and cascades to failedEntry, as if the body had failed. Recursing into failedEntry sees
		// past an optional part (`a+?b?`) to the real zero-width tail. Per-type dispatch (a virtual call,
		// not an `instanceof` chain) only takes over once that is settled.
		if (node.entrySet != null) {
			return node.failedEntry != null && collectExitAssertionChain(node.failedEntry, chain);
		}
		return node.collectExitAssertionChain(chain);
	}
}

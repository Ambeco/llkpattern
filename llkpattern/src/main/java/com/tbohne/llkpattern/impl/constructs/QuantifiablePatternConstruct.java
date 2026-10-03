package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.ArrayCodePointSet;
import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;
import com.tbohne.llkpattern.PatternSyntaxException;

import com.tbohne.llkpattern.impl.unicode.CodePointSet.MutableCodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import java.util.ArrayList;
import java.util.List;

public abstract class QuantifiablePatternConstruct extends PatternConstruct {
	final String pattern;
	public int min = 1;
	public int max = 1;
	public int quantifiableIndex = -1;
	// Set by PatternParser#parseQuantifiable when a trailing '?' follows the quantifier itself
	// (e.g. "a+?"). Possessive '+' stays a no-op: this engine's no-backtrack greedy loop already
	// makes the greedy/possessive choice unobservable (nothing to backtrack into), so possessive
	// syntax is accepted purely for compatibility, not compiled differently.
	public boolean reluctant = false;
	// Set by PatternParser#parseQuantifiable when a trailing '+' follows the quantifier itself
	// (e.g. "a++"). Unlike reluctant, this doesn't change buildLoopMatcher's own matcher graph --
	// see `reluctant`'s doc above -- but it DOES exempt the loop from the zero-width-assertion
	// ambiguity check buildLoopMatcher runs for plain greedy syntax (see that method's own doc):
	// java.util.regex's possessive quantifier never backtracks either, so this engine's
	// always-non-backtracking compilation already agrees with it, with nothing to reject.
	public boolean possessive = false;

	QuantifiablePatternConstruct(String pattern, int startIndex) {
		super(startIndex);
		this.pattern = pattern;
	}

	QuantifiablePatternConstruct(String pattern, int startIndex, int endIndex) {
		super(startIndex, endIndex);
		this.pattern = pattern;
	}

	/** True for a "plain" {@code {1,1}} construct -- i.e. no real repetition/optionality. */
	public boolean isUnquantified() {
		return min == 1 && max == 1;
	}

	/**
	 * {@link #elseIsEndOfFind} for the quantified case: {@link #buildLoopEntryMap}'s catch-all comes
	 * from whichever single candidate ({@code body} part, or {@code next} when the loop can be
	 * skipped) claimed it -- {@link #mergeEntryPoints} rejects two.
	 */
	final boolean loopElseIsEndOfFind(List<PatternConstruct> body, PatternConstruct next) {
		if (max != 0) {
			for (int i = 0; i < body.size(); i++) {
				if (body.get(i).claimsEntryElse()) {
					return body.get(i).elseIsEndOfFind();
				}
			}
		}
		return (max == 0 || min == 0) && next.claimsEntryElse() && next.elseIsEndOfFind();
	}

	/** {@link #elseIsResidual} for the quantified case, mirroring {@link #loopElseIsEndOfFind}. */
	final boolean loopElseIsResidual(List<PatternConstruct> body, PatternConstruct next) {
		if (max != 0) {
			for (int i = 0; i < body.size(); i++) {
				if (body.get(i).claimsEntryElse()) {
					return body.get(i).elseIsResidual();
				}
			}
		}
		return (max == 0 || min == 0) && next.claimsEntryElse() && next.elseIsResidual();
	}

	/**
	 * Computes this construct's own entry point for the quantified ({@code
	 * !isUnquantified()}) case -- called from {@code buildEntryMap}. {@code FIRST(body)} (the
	 * union of {@code body}'s own entry points, each body part's {@code next} pointed at {@link
	 * #loopBodyTarget} since continuing the loop always eventually routes back here), unioned with
	 * {@code next}'s own entry point when {@code min == 0} (skipping this construct entirely is
	 * valid). Deliberately reads ONLY entry points, never {@code compile()}s anything -- see
	 * design.md's "Entry-point computation vs. matcher compilation" section for why that's what
	 * lets a loop nested inside another loop's body resolve without forcing a cycle.
	 */
	void buildLoopEntryMap(List<PatternConstruct> body, PatternConstruct next, int captureConstructIndex) {
		if (max == 0) {
			// `X{0}` never matches X at all: its entry point is exactly `next`'s.
			MergedEntries skipped = mergeEntryPoints(pattern, List.of(), next, "loop part");
			entryMap = skipped.ranges;
			if (skipped.entryElse() != null) {
				entryElse = this;
			}
			return;
		}
		PatternConstruct target = loopBodyTarget(captureConstructIndex);
		for (int i = 0; i < body.size(); i++) {
			body.get(i).next = target;
		}
		// `body` handed straight to mergeEntryPoints, with `next` merged in via its own `extra`
		// parameter instead of first being copied into a new ArrayList<>(body) just to append it
		// -- see that overload's own doc.
		MergedEntries result = mergeEntryPoints(pattern, body, min == 0 ? next : null, "loop part");
		entryMap = result.ranges; // already Boolean-valued -- see mergeEntryPoints' own doc.
		if (result.entryElse() != null) {
			entryElse = this;
		}
	}

	@MonotonicNonNull LoopBackPatternConstruct loopBackMarker;
	@MonotonicNonNull PatternConstruct loopBodyTargetCache;

	/**
	 * The stable stand-in for "loop back to this construct's own entry point", used as every body
	 * part's {@code next} during entry-point computation ({@link #buildLoopEntryMap}) -- for a
	 * capturing loop, wrapped in a {@link CaptureEndPatternConstruct} first, since finishing one iteration
	 * must end the capture before looping back. Memoized as a field, rather than built fresh in
	 * each of {@link #buildLoopEntryMap}/{@link #buildLoopMatcher} separately, so that a NESTED
	 * capturing body part's own {@code CaptureEndPatternConstruct} -- constructed once, during entry-point
	 * computation, with this object as its {@code realNext} -- resolves against the exact same
	 * marker instance that {@link #buildLoopMatcher} later fills in with a real {@code .matcher}.
	 * Pointing body parts at {@code this} (the loop construct itself) directly, as this used to,
	 * meant a nested capturing group's {@code CaptureEndPatternConstruct} permanently captured {@code this}
	 * as {@code realNext} -- but {@code this.matcher} isn't set until the whole loop has finished
	 * compiling, well after that marker's own {@code buildMatcher()} reads it at match-build time,
	 * throwing a {@code NullPointerException} (a quantified group whose sole body is a capturing
	 * group, e.g. {@code ((x))*}) -- see remaining_work.md's now-fixed entry on this.
	 */
	private PatternConstruct loopBodyTarget(int captureConstructIndex) {
		if (loopBodyTargetCache == null) {
			loopBackMarker = new LoopBackPatternConstruct(startIndex, this);
			loopBackMarker.flags = flags;
			if (captureConstructIndex == -1) {
				loopBodyTargetCache = loopBackMarker;
			} else {
				PatternConstruct captureEnd = new CaptureEndPatternConstruct(startIndex, captureConstructIndex, loopBackMarker);
				captureEnd.flags = flags;
				loopBodyTargetCache = captureEnd;
			}
		}
		return loopBodyTargetCache;
	}

	/**
	 * Builds the actual loop matcher graph -- see design.md's "Quantifier/loop compilation"
	 * section (flattened-dispatch experiment, 2026-09-18: see {@code MatcherConstruct}'s own
	 * class doc for the overall design this replaced). Three nodes are involved:
	 *
	 * <ol>
	 *   <li>The body's own dispatch chain (one node per {@code body} element, built via the same
	 *       {@code dispatchEntrySet}/{@code dispatchFailedEntry} mechanism {@link
	 *       #buildFlattenedChain} uses for a plain union -- see {@code MatcherConstruct}'s own
	 *       "Flattened dispatch" doc) -- its head IS both this construct's own externally-visible
	 *       entry point AND the loop-back target for a completed iteration, with no separate
	 *       chain needed for either any more: a body part's own {@code failedEntry} naturally
	 *       falls through to {@code exitNode} on a genuine non-match, at ANY position -- the very
	 *       first attempt (where a {@code min == 0} loop skipping itself entirely is just
	 *       {@code exitNode} immediately allowing that, since its own {@code min} check doesn't
	 *       care how it was reached) exactly as much as a later re-check.
	 *   <li>{@link LoopMatcherConstruct} (greedy) or {@link
	 *       ReluctantLoopMatcherConstruct} (reluctant, only when {@link
	 *       MatcherConstruct#exitIsPureEnd} proves stopping early is safe -- see that class's own
	 *       doc), self-registered onto a {@link LoopBackPatternConstruct} BEFORE the body compiles against
	 *       it, breaking the construction-time cycle every loop body creates (the same
	 *       self-registration-first trick {@code MatcherConstruct}'s class doc describes). The
	 *       greedy node is reached only as a completed body iteration's own continuation, and its
	 *       whole job is enforcing {@code max}: dispatch back to the body's own head (another
	 *       attempt) if under it, or straight to {@code exitNode} (forcing a stop) if not. The
	 *       reluctant node is ALSO this construct's own externally-visible entry point (see its own
	 *       doc for why, and for the {@code min}/{@code max} "+1" shift that makes reusing one node
	 *       for both roles safe) -- neither tests code-point membership at all.
	 *   <li>{@link LoopExitMatcherConstruct}, this loop's "stop iterating" node --
	 *       enforces {@code min} (the reluctant-safe case's own {@code min} pre-shifted the same
	 *       way, to stay consistent with the counter's shifted meaning) and, on success, dispatches
	 *       to {@code next}'s own matcher.
	 * </ol>
	 *
	 * <p>When {@code captureConstructIndex != -1} (the construct is <i>also</i> a capturing group,
	 * e.g. {@code (a)*}), the capture must re-fire every iteration -- last iteration wins, per real
	 * regex semantics -- and must fire identically whether this is the very first attempt or a
	 * re-check; since both now share the exact same body-chain head node, that's just a single
	 * shared {@link BeginCaptureMatcherConstruct} wrapping it. Each body part is
	 * compiled against a {@link CaptureEndPatternConstruct} standing in for the {@link LoopBackPatternConstruct}
	 * above, so finishing one iteration records the captured substring before looping back rather
	 * than looping back directly.
	 */
	void buildLoopMatcher(List<PatternConstruct> body, PatternConstruct next, int captureConstructIndex) {
		if (max == 0) {
			// The body is never compiled: `X{0}` is a no-op, and its capture group (if any) stays unset.
			MatcherConstruct.aliasOrPassThrough(this, next.matcher());
			return;
		}
		boolean capturing = captureConstructIndex != -1;

		// Ambiguity check only, on entry points alone -- no compiling. Unlike the old
		// ForkingMatcherConstruct-based design, dispatch fields must be set on each body part
		// BEFORE it's ever compiled (compile() memoizes on first call), so this can't reuse a
		// helper that compiles as a side effect. `next` is included as `extra` so this also
		// validates that no body part is ambiguous with `next` itself, needed on every re-check,
		// not just the min==0 entry case buildLoopEntryMap already validated.
		// Plain greedy only (never reluctant or possessive -- see skipZeroWidthEntrySet's own
		// `checkAssertions` doc): computing bodyLastCharSet is wasted work for the other two cases,
		// since skipZeroWidthEntrySet(..., false, ...) never reads it.
		boolean checkAssertionAmbiguity = !reluctant && !possessive;
		CodePointSet bodyLastCharSet = checkAssertionAmbiguity ? unionLastCharSet(body) : null;
		CodePointSet nextEntrySet = next.skipZeroWidthEntrySet(checkAssertionAmbiguity, bodyLastCharSet);
		CodePointSet[] gates = checkDisjoint(pattern, flags, body, next, nextEntrySet, "loop part");
		boolean hasResidualPart = narrowResidualGates(body, next, gates);

		// Reuses the SAME LoopBackPatternConstruct (and, when capturing, the same wrapping CaptureEndPatternConstruct)
		// buildLoopEntryMap already handed to each body part as `next` -- see loopBodyTarget()'s own
		// doc for why identity, not just equal content, matters here: a nested capturing body part's
		// own CaptureEndPatternConstruct (built during entry-point computation) is permanently pointed at
		// whichever object loopBodyTarget() returned then, so this must be the exact same instance,
		// not a fresh one, or that nested marker's `realNext.matcher` would never get filled in.
		PatternConstruct bodyCompileTarget = loopBodyTarget(captureConstructIndex);
		LoopBackPatternConstruct marker = loopBackMarker;
		if (marker == null) {
			throw new IllegalStateException("buildLoopMatcher() ran before loopBodyTarget() created the loop's back marker "
					+ "(did you mean to call loopBodyTarget(captureConstructIndex) first?)");
		}

		// Decided once, here, before either the exit node or the marker-owned node is built --
		// both need to already know which case they're in. See ReluctantLoopMatcherConstruct's own
		// doc for the "+1" shift this drives: reached both as the loop's fresh entry (zero
		// iterations done) and as the post-iteration continuation, so its own quantifiableCounts
		// slot counts VISITS, not completed iterations, and LoopExitMatcherConstruct's `min` check (reached
		// directly via the body's own failedEntry, bypassing the marker-owned node entirely) has to
		// agree on that same shifted meaning to stay consistent.
		@Nullable List<ZeroWidthAssertionGuard> exitAssertionChain =
				reluctant ? next.matcher().exitAssertionChain() : null;
		boolean reluctantSafe = exitAssertionChain != null;
		int shiftedMin = plusOneCapped(min);
		int shiftedMax = plusOneCapped(max);
		LoopExitMatcherConstruct exitNode = new LoopExitMatcherConstruct(
				flags, quantifiableIndex, reluctantSafe ? shiftedMin : min, min == 0, next.matcher());

		// A second, distinct marker from `marker` above -- `marker.matcher` is already claimed by
		// the marker-owned node itself; this one's `.matcher` is where that node's "continue"
		// successor (the body chain's own head, not resolvable until after the body compiles)
		// ends up, resolved via ordinary direct assignment further down, exactly like every other
		// forward reference in this file -- no bespoke mutable field needed on either
		// LoopMatcherConstruct or ReluctantLoopMatcherConstruct (see their own class docs).
		LoopContinuePatternConstruct continueMarker = new LoopContinuePatternConstruct(startIndex);
		continueMarker.flags = flags;
		MatcherConstruct loopNode = reluctantSafe
				? new ReluctantLoopMatcherConstruct(
						marker, quantifiableIndex, shiftedMin, shiftedMax, continueMarker, exitNode,
						exitAssertionChain)
				: new LoopMatcherConstruct(marker, quantifiableIndex, max, continueMarker, exitNode);

		if (capturing) {
			bodyCompileTarget.compile(marker);
		}

		// Body parts chain to each other tail-to-front, same mechanism as buildFlattenedChain --
		// but unlike a plain union's own final candidate, a loop body part can never be left
		// ungated: "doesn't match" always has somewhere real to go (exitNode, which itself
		// enforces `min` and may allow an immediate min==0 skip), never just "the whole match
		// fails" the way a truly catch-all-less union's last branch can rely on.
		//
		// When capturing, each part's OWN entry gate must be checked BEFORE the capture's start
		// index is recorded -- not after, the way a single shared BeginCaptureMatcherConstruct
		// wrapping the whole body chain's head would do it (tried first, reverted: it recorded a
		// capture start even on a min==0 loop's very first, ultimately-zero-iteration attempt,
		// since the shared wrapper ran unconditionally before the body's own gate ever got a say
		// -- see notes.md's entry on this). So each part gets its own throwaway {@link
		// LoopBodyPartGatePatternConstruct} carrying the gate instead, with the capture wrapped INSIDE it
		// (compiled ungated, since gating already happened by the time it runs).
		MatcherConstruct bodyTail = exitNode;
		for (int i = body.size() - 1; i >= 0; i--) {
			PatternConstruct part = body.get(i);
			CodePointSet partEntrySet = gates[i];
			if (capturing) {
				MatcherConstruct rawPartMatcher = part.compile(bodyCompileTarget);
				LoopBodyPartGatePatternConstruct gateMarker = new LoopBodyPartGatePatternConstruct(startIndex);
				gateMarker.flags = flags;
				gateMarker.dispatchEntrySet = partEntrySet;
				gateMarker.dispatchFailedEntry = bodyTail;
				bodyTail = new BeginCaptureMatcherConstruct(gateMarker, captureConstructIndex, rawPartMatcher);
			} else {
				part.dispatchEntrySet = partEntrySet;
				part.dispatchFailedEntry = bodyTail;
				bodyTail = part.compile(bodyCompileTarget);
			}
		}
		MatcherConstruct bodyHead = bodyTail;
		continueMarker.matcher = bodyHead;

		// A greedy loop's own externally-visible entry point is exactly the body chain's head --
		// see the class doc above for why no separate entry-only chain is needed. A reluctant-safe
		// loop's entry point is `loopNode` itself instead (built above, before the body even
		// compiled) -- ReluctantLoopMatcherConstruct's own doc explains why it needs to run before
		// the very first iteration too, not just after each completed one.
		//
		// EXPERIMENT (2026-09-24, see design.md's "LoopFirstEntryMatcherConstruct"
		// section): for a single-alternative, non-capturing, min>=1 loop, bodyHead's own entrySet
		// check is PROVABLY redundant on first entry specifically -- buildLoopEntryMap's own
		// `min == 0 ? next : null` means this loop's externally-exposed entry point (whatever an
		// outer chain candidate's own gate, or an ungated top-level loop's own lack of one,
		// already established before calling here) is EXACTLY gates[0], the same set bodyHead
		// would re-check. Re-entry (the loop-back path via continueMarker/LoopMatcherConstruct,
		// wired above) is UNAFFECTED -- it still goes straight to the real bodyHead, which keeps
		// its own gate, since that path has no outer guarantee at all. LoopFirstEntryMatcherConstruct
		// calls bodyHead.matchBody() directly (bypassing bodyHead.match()'s own entrySet check)
		// rather than becoming a full duplicate node -- see its own doc for why this can't
		// currently be generalized to a multi-alternative body or a min==0 loop.
		boolean singleAlternativeUngatedFirstEntryEligible =
				!reluctantSafe && !capturing && body.size() == 1 && min >= 1 && !hasResidualPart;
		MatcherConstruct entryPoint = reluctantSafe
				? loopNode
				: singleAlternativeUngatedFirstEntryEligible
						? new LoopFirstEntryMatcherConstruct(flags, bodyHead)
						: bodyHead;
		MatcherConstruct.aliasOrPassThrough(this, entryPoint);
	}

	/**
	 * Gives each residual body part ({@code .}, see {@link #elseIsResidual}) its real gate: whatever
	 * it accepts that no other body part and not {@code next} (the last {@code gates} slot) claims,
	 * so {@code .+b} loops over {@code [^b]}. {@link #checkDisjoint} left such a part's gate empty,
	 * as the residual claims nothing explicitly. Two residual claimants in a row ({@code .+.}) would
	 * have the body swallow everything and leave the exit nothing, so that is rejected here (a
	 * nullable loop's version was already rejected by {@link #buildLoopEntryMap}'s merge). A
	 * possessive loop can't take the residual either: {@code java.util.regex} really does swallow
	 * the later part's characters there (so {@code .++b} can never match), and silently matching
	 * as {@code [^b]++b} would be a worse divergence than the ambiguity error.
	 * Returns whether any part was residual: such a loop's entry is not just {@code gates[0]}, so
	 * {@code LoopFirstEntryMatcherConstruct}'s "outer gate already checked it" proof doesn't hold.
	 */
	private boolean narrowResidualGates(List<PatternConstruct> body, PatternConstruct next, CodePointSet[] gates) {
		boolean any = false;
		for (int i = 0; i < body.size(); i++) {
			PatternConstruct part = body.get(i);
			if (!part.claimsEntryElse() || !part.elseIsResidual()) {
				continue;
			}
			any = true;
			if (next.claimsEntryElse() && next.elseIsResidual()) {
				throw PatternSyntaxException.throwWithReferences(
						pattern,
						next.startIndex,
						"loop part starting at index ", part.startIndex,
						" allows any other character, but the construct after the loop, starting at index ",
						next.startIndex,
						", does too, which is ambiguous (did you mean to exclude what the later part needs,"
								+ " e.g. [^b]+b instead of .+b?)");
			}
			CodePointSet accept = part.firstCharSet();
			if (possessive) {
				for (int j = 0; j < gates.length; j++) {
					if (j != i && (accept == null ? !gates[j].isEmpty() : accept.intersects(gates[j]))) {
						throw PatternSyntaxException.throwWithReferences(
								pattern,
								part.startIndex,
								"possessive loop part starting at index ", part.startIndex,
								" allows any other character, so it would swallow characters the construct"
										+ " after it (or a sibling part) needs, and java.util.regex never gives"
										+ " those back from a possessive loop either -- did you mean a greedy"
										+ " quantifier, or a character class excluding them (e.g. [^b]++b)?");
					}
				}
			}
			MutableCodePointSet gate = new ArrayCodePointSet(ArrayCodePointSet.capacityHint(accept, gates, i));
			if (accept == null) {
				gate.invert();
			} else {
				gate.insertAll(accept);
			}
			for (int j = 0; j < gates.length; j++) {
				if (j != i) {
					gate.removeAll(gates[j]);
				}
			}
			gates[i] = gate;
		}
		return any;
	}

	/**
	 * {@code n + 1}, capped (not wrapped) at {@link Integer#MAX_VALUE} -- the "+1" shift {@link
	 * ReluctantLoopMatcherConstruct} needs for both {@code min} and {@code max}
	 * (see its own doc). A literal {@code n + 1} would silently overflow to {@link
	 * Integer#MIN_VALUE} for an unbounded {@code max} (e.g. {@code a+?}/{@code a*?}, where {@code
	 * max == Integer.MAX_VALUE}), which would make every {@code count < shiftedMax} comparison
	 * false immediately and break every unbounded reluctant loop.
	 */
	private static int plusOneCapped(int n) {
		return n == Integer.MAX_VALUE ? Integer.MAX_VALUE : n + 1;
	}
}

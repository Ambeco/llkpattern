package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.ArrayCodePointSet;
import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;
import com.tbohne.llkpattern.PatternSyntaxException;

import com.tbohne.llkpattern.impl.unicode.CodePointSet.MutableCodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import java.util.List;

public abstract class QuantifiablePatternConstruct extends PatternConstruct {
	final String pattern;
	public int min = 1;
	public int max = 1;
	public int quantifiableIndex = -1;
	// Trailing '?' after the quantifier ("a+?").
	public boolean reluctant = false;
	// Trailing '+' ("a++"). Compiled like greedy, but exempt from the zero-width-assertion ambiguity
	// check buildLoopMatcher runs for greedy: java.util.regex's possessive never backtracks either.
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

	// elseIsEndOfFind for the quantified case: the catch-all comes from whichever single candidate (a body
	// part, or next when the loop is skippable) claimed it; mergeEntryPoints rejects two.
	final boolean loopElseIsEndOfFind(ConstructList body, PatternConstruct next) {
		if (max != 0) {
			for (int i = 0, n = body.size; i < n; i++) {
				if (body.items[i].claimsEntryElse()) {
					return body.items[i].elseIsEndOfFind();
				}
			}
		}
		return (max == 0 || min == 0) && next.claimsEntryElse() && next.elseIsEndOfFind();
	}

	/** {@link #elseIsResidual} for the quantified case, mirroring {@link #loopElseIsEndOfFind}. */
	final boolean loopElseIsResidual(ConstructList body, PatternConstruct next) {
		if (max != 0) {
			for (int i = 0, n = body.size; i < n; i++) {
				if (body.items[i].claimsEntryElse()) {
					return body.items[i].elseIsResidual();
				}
			}
		}
		return (max == 0 || min == 0) && next.claimsEntryElse() && next.elseIsResidual();
	}

	// Entry point for the quantified case: FIRST(body) (each part's next pointed at loopBodyTarget, since
	// continuing routes back here), plus next's when min == 0. Reads ONLY entry points, never compile()s,
	// so a loop nested in another's body resolves without forcing a cycle (design.md "Entry-point
	// computation vs. matcher compilation").
	void buildLoopEntryMap(ConstructList body, PatternConstruct next, int captureConstructIndex) {
		if (max == 0) {
			// `X{0}` never matches X at all: its entry point is exactly `next`'s.
			MergedEntries skipped = mergeEntryPoints(pattern, ConstructList.EMPTY, next, "loop part");
			entryMap = skipped.ranges;
			if (skipped.entryElse() != null) {
				entryElse = this;
			}
			return;
		}
		PatternConstruct target = loopBodyTarget(captureConstructIndex);
		for (int i = 0, n = body.size; i < n; i++) {
			body.items[i].next = target;
		}
		MergedEntries result = mergeEntryPoints(pattern, body, min == 0 ? next : null, "loop part");
		entryMap = result.ranges;
		if (result.entryElse() != null) {
			entryElse = this;
		}
	}

	private @MonotonicNonNull LoopBackPatternConstruct loopBackMarker;
	private @MonotonicNonNull PatternConstruct loopBodyTargetCache;

	// The stable stand-in for "loop back to this construct's entry point": every body part's next during
	// entry-point computation, wrapped in a CaptureEndPatternConstruct for a capturing loop (an iteration
	// must end the capture first). Memoized so a NESTED capturing part's CaptureEndPatternConstruct,
	// built then with this as realNext, resolves against the same marker buildLoopMatcher later fills in.
	// Pointing parts at `this` directly left that marker reading an unset this.matcher (NPE for `((x))*`).
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
	 * Builds the loop matcher graph (design.md "Quantifier/loop compilation"). Three nodes:
	 *
	 * <ol>
	 *   <li>The body's dispatch chain, one node per {@code body} element via {@code
	 *       dispatchEntrySet}/{@code dispatchFailedEntry} (see {@code MatcherConstruct}'s "Flattened
	 *       dispatch"). Its head is BOTH this construct's entry point and the loop-back target: a body
	 *       part's {@code failedEntry} falls through to {@code exitNode} on a non-match at any position
	 *       (a {@code min == 0} loop skipping itself is just {@code exitNode} allowing it).
	 *   <li>{@link LoopMatcherConstruct} (greedy) or {@link ReluctantLoopMatcherConstruct} (reluctant,
	 *       only when {@code MatcherConstruct#exitAssertionChain} proves stopping early is safe),
	 *       self-registered onto a {@link LoopBackPatternConstruct} BEFORE the body compiles against it,
	 *       breaking the construction-time cycle. The greedy node is only a completed iteration's
	 *       continuation, enforcing {@code max}. The reluctant node is ALSO the entry point (see its doc
	 *       for the "+1" shift that makes that safe). Neither tests code-point membership.
	 *   <li>{@link LoopExitMatcherConstruct}: enforces {@code min} (pre-shifted likewise in the
	 *       reluctant-safe case) and dispatches to {@code next}.
	 * </ol>
	 *
	 * <p>When {@code captureConstructIndex != -1} (e.g. {@code (a)*}) the capture re-fires every
	 * iteration, last wins. Each body part compiles against a {@link CaptureEndPatternConstruct}
	 * standing in for the {@link LoopBackPatternConstruct}, so finishing an iteration records the
	 * capture before looping back.
	 */
	void buildLoopMatcher(ConstructList body, PatternConstruct next, int captureConstructIndex) {
		if (max == 0) {
			// The body is never compiled: `X{0}` is a no-op, and its capture group (if any) stays unset.
			MatcherConstruct.aliasOrPassThrough(this, next.matcher());
			return;
		}
		boolean capturing = captureConstructIndex != -1;

		// Ambiguity check on entry points alone, no compiling: dispatch fields must be set on each body part
		// BEFORE it is compiled (memoized), so this can't use a helper that compiles. `next` is included as
		// `extra` because every re-check needs it, not just the min == 0 entry case buildLoopEntryMap
		// validated. The assertion check applies to plain greedy only: skipZeroWidthEntrySet never reads
		// bodyLastCharSet otherwise.
		boolean checkAssertionAmbiguity = !reluctant && !possessive;
		CodePointSet bodyLastCharSet = checkAssertionAmbiguity ? unionLastCharSet(body) : null;
		CodePointSet nextEntrySet = next.skipZeroWidthEntrySet(checkAssertionAmbiguity, bodyLastCharSet);
		CodePointSet[] gates = checkDisjoint(pattern, flags, body, next, nextEntrySet, "loop part");
		boolean hasResidualPart = narrowResidualGates(body, next, gates);

		// Must be the SAME marker (and CaptureEndPatternConstruct) buildLoopEntryMap gave each body part as
		// next: a nested capturing part's CaptureEndPatternConstruct points at it permanently, and only that
		// instance gets its matcher filled in (see loopBodyTarget).
		PatternConstruct bodyCompileTarget = loopBodyTarget(captureConstructIndex);
		LoopBackPatternConstruct marker = loopBackMarker;
		if (marker == null) {
			throw new IllegalStateException("buildLoopMatcher() ran before loopBodyTarget() created the loop's back marker "
					+ "(did you mean to call loopBodyTarget(captureConstructIndex) first?)");
		}

		// Decided first: both the exit node and the marker-owned node need it. A reluctant-safe node counts
		// VISITS, not completed iterations (see ReluctantLoopMatcherConstruct), so LoopExitMatcherConstruct's
		// min, reached directly via the body's failedEntry, must use the same shifted meaning.
		@Nullable List<ZeroWidthAssertionGuard> exitAssertionChain =
				reluctant ? next.matcher().exitAssertionChain() : null;
		boolean reluctantSafe = exitAssertionChain != null;
		int shiftedMin = plusOneCapped(min);
		int shiftedMax = plusOneCapped(max);
		LoopExitMatcherConstruct exitNode = new LoopExitMatcherConstruct(
				flags, quantifiableIndex, reluctantSafe ? shiftedMin : min, min == 0, next.matcher());

		// A second marker: `marker.matcher` is claimed by loopNode; this one's .matcher receives loopNode's
		// "continue" successor (the body chain head, unresolvable until the body compiles) by assignment.
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

		// Body parts chain tail-to-front like buildFlattenedChain, but a part is never ungated: "doesn't match"
		// always has somewhere to go (exitNode enforces min, allowing an immediate min == 0 skip).
		//
		// When capturing, each part's OWN gate must run BEFORE the capture start is recorded: one shared
		// Begin wrapping the chain head recorded a start even on a min == 0 loop's zero-iteration attempt
		// (reverted; notes.md). So each part gets a throwaway LoopBodyPartGatePatternConstruct carrying the
		// gate, with the capture INSIDE it (compiled ungated).
		MatcherConstruct bodyTail = exitNode;
		for (int i = body.size - 1; i >= 0; i--) {
			PatternConstruct part = body.items[i];
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

		// A greedy loop's entry point is the body chain's head; a reluctant-safe loop's is loopNode.
		//
		// EXPERIMENT (2026-09-24, design.md "LoopFirstEntryMatcherConstruct"): for a single-alternative,
		// non-capturing, min >= 1 loop, bodyHead's own entrySet check is provably redundant on FIRST entry,
		// since the loop's exposed entry point is exactly gates[0] (buildLoopEntryMap's min == 0 ? next :
		// null) and whatever called us already checked it. Loop-back re-entry still goes to the real
		// bodyHead and its gate. LoopFirstEntryMatcherConstruct calls bodyHead.matchBody() directly; see its
		// doc for why this can't be generalized.
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
	 * Gives each residual body part ({@code .}, see {@link #elseIsResidual}) its real gate: what it
	 * accepts that no other part and not {@code next} (the last {@code gates} slot) claims, so {@code
	 * .+b} loops over {@code [^b]}. Two residual claimants in a row ({@code .+.}) would leave the exit
	 * nothing, so that is rejected (a nullable loop's version is rejected by {@link
	 * #buildLoopEntryMap}'s merge). A possessive loop can't take the residual either: java.util.regex
	 * really swallows the later part's characters there ({@code .++b} never matches), and silently
	 * matching as {@code [^b]++b} would be a worse divergence than the error.
	 *
	 * <p>Returns whether any part was residual: such a loop's entry isn't just {@code gates[0]}, so
	 * {@code LoopFirstEntryMatcherConstruct}'s "outer gate already checked it" proof doesn't hold.
	 */
	private boolean narrowResidualGates(ConstructList body, PatternConstruct next, CodePointSet[] gates) {
		boolean any = false;
		for (int i = 0, n = body.size; i < n; i++) {
			PatternConstruct part = body.items[i];
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

	// n + 1, capped: a literal n + 1 overflows for an unbounded max (a+?, a*?), making every
	// count < shiftedMax false.
	private static int plusOneCapped(int n) {
		return n == Integer.MAX_VALUE ? Integer.MAX_VALUE : n + 1;
	}
}

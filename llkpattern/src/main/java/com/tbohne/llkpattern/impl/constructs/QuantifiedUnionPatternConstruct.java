package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.ArrayCodePointSet;
import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.CodePointSet.MutableCodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class QuantifiedUnionPatternConstruct extends QuantifiablePatternConstruct {
	public int captureConstructIndex = 0;
	public String captureName = "";
	// Pre-sized to 4, not the JDK default of 10 -- corpus measurement (2026-09-27) found 99.63%
	// of QuantifiedUnions end up with <=4 elements (mean 1.38), so the default's first-`add`
	// grow to 10 wastes far more capacity than it saves growth copies for the rare larger case.
	public final List<PatternConstruct> constructs = new ArrayList<>(4);

	// The real (non-identity-rewritten) catch-all candidate this union's OWN fork chain falls
	// back to in buildMatcher() -- see that method below. Needed because the inherited
	// entryElse field is deliberately re-keyed onto `this` (like SequencePatternConstruct's own fix, see its
	// doc), for ancestors' identity checks -- but buildMatcher() reads the real candidate
	// identity, not `this`, to resolve the actual MatcherConstruct target the fallback should
	// dispatch to. (buildMatcher() otherwise builds its fork chain by walking `constructs`
	// directly -- see mergeEntryPoints' own doc for why nothing here needs a
	// PatternConstruct-valued entry map of its own any more.)
	@Nullable PatternConstruct rawEntryElse;

	// The unquantified-and-non-empty case's actual compile target (`next` itself, or a
	// CaptureEndPatternConstruct for a capturing group) -- computed once in buildEntryMap() (cheaply, no
	// compile() calls) and reused by buildMatcher() to actually compile the branches against it.
	// Kept as a field rather than recomputed, since buildMatcher() needs the SAME CaptureEndPatternConstruct
	// instance buildEntryMap() already used to compute rawEntryElse's identity.
	// @Nullable only because it has no meaningful value before buildEntryMap() runs -- by the
	// time buildMatcher() reads it (unguarded), compile()'s ensureEntryPointBuilt() guarantees
	// buildEntryMap() already has, in this (unquantified, non-empty-constructs) branch.
	@MonotonicNonNull PatternConstruct compileTarget;

	/** {@link #compileTarget}, set by {@link #buildEntryMap} before any {@link #buildMatcher} reads it. */
	PatternConstruct compileTarget() {
		PatternConstruct t = compileTarget;
		if (t == null) {
			throw new IllegalStateException("QuantifiedUnionPatternConstruct at pattern index " + startIndex
					+ " read compileTarget before buildEntryMap() ran (did you mean to override "
					+ "needsEntryPointBeforeMatcher() to return true?)");
		}
		return t;
	}

	public QuantifiedUnionPatternConstruct(String pattern, int startIndex) {
		super(pattern, startIndex);
	}

	private boolean isCapturing() {
		return captureConstructIndex != -1;
	}

	@Override
	boolean claimsEntryElse() {
		if (isUnquantified() && constructs.isEmpty()) {
			// Bare flags-only group ("(?i)", no body) -- same aliasing as buildEntryMap: passes
			// straight through to `next` (this union contributes nothing of its own). Safe even
			// though `next` could resolve back to an ancestor loop still under construction (see
			// buildLoopEntryMap's `part.next = this`) -- whatever `next` turns out to be, if it's
			// itself a QuantifiablePatternConstruct it keeps the state-checked default below, so the
			// cycle is still caught there, just one level further down.
			return next().claimsEntryElse();
		}
		return super.claimsEntryElse();
	}

	@Override
	boolean elseIsEndOfFind() {
		if (!isUnquantified()) {
			return loopElseIsEndOfFind(constructs, next());
		}
		if (constructs.isEmpty()) {
			return next().elseIsEndOfFind();
		}
		return rawEntryElse != null && rawEntryElse.elseIsEndOfFind();
	}

	@Override
	boolean elseIsResidual() {
		if (!isUnquantified()) {
			return loopElseIsResidual(constructs, next());
		}
		if (constructs.isEmpty()) {
			return next().elseIsResidual();
		}
		return rawEntryElse != null && rawEntryElse.elseIsResidual();
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		if (!isUnquantified()) {
			buildLoopEntryMap(constructs, next, captureConstructIndex);
			return;
		}
		if (constructs.isEmpty()) {
			// Bug fix (2026-09-06): a bare flags-only group ("(?s)", no ":", no body) is the
			// only way to reach this constructor with an empty `constructs` list -- every other
			// path (a real "()"/"(?:)"/"(?<name>)") goes through parseUnion(), which rejects an
			// empty body via throwEmptySequence before a QuantifiedUnionPatternConstruct with zero constructs can
			// ever exist. Previously this fell through to compileAndMergeCandidates() with an
			// empty candidate list, producing an empty entryMap/entryElse -- i.e. a
			// fork chain that matches nothing at all, silently breaking the
			// surrounding sequence ("(?s)abx" stopped matching "abx"). A bare flags group is
			// zero-width and always succeeds -- its only job was toggling `flags` for
			// PatternParser, already done by the caller -- so just pass through to `next` exactly
			// as an empty SequencePatternConstruct element would, instead of compiling as its own dispatch node.
			// Aliased directly -- entryMap's values are always Boolean `true` regardless of which
			// construct built it (see entryMap's own doc), so there's no PatternConstruct identity
			// to lose by sharing next's own map instead of copying its entries.
			entryMap = next.getEntryPointMap();
			if (next.getEntryElse() != null) {
				entryElse = this;
			}
			// matcher isn't assigned here (unlike the pre-split design) -- next.matcher may not be
			// built yet at this point (see design.md's "Entry-point computation vs. matcher
			// compilation" section); buildMatcher() assigns it once next really is compiled.
			return;
		}

		// Determine every branch's compile target (tail-to-front relative to this union: each
		// branch's "next" is this union's own "next" -- or, for a capturing group, a marker that
		// ends the capture before reaching the real next -- since choosing a branch doesn't itself
		// consume anything) and merge their entry points, rejecting any two branches that could
		// both match the same next code point -- the core LL(1) restriction this library is built
		// on. Deliberately doesn't compile() anything here (branches, or compileTarget itself) --
		// see design.md's "Entry-point computation vs. matcher compilation" section; buildMatcher()
		// does the real compiling, once `next` is guaranteed to already be compiled.
		PatternConstruct target = next;
		if (isCapturing()) {
			target = new CaptureEndPatternConstruct(startIndex, captureConstructIndex, next);
			target.flags = flags;
		}
		compileTarget = target;
		for (PatternConstruct part : constructs) {
			part.next = target;
		}
		MergedEntries result = mergeEntryPoints(pattern, constructs, "union subpattern");
		rawEntryElse = result.entryElse();
		// Re-keyed onto `this` rather than kept as whatever nested candidate built each range --
		// see SequencePatternConstruct.buildEntryMap's doc for why (same fix, same reason: a containing loop's
		// "e.getValue() != next" exit-vs-continue identity check must see THIS union, not one of
		// its branches' own leaves, whenever this union is passed as some ancestor's `next`).
		if (rawEntryElse != null) {
			entryElse = this;
		}
		// Safe to alias directly (unlike entryElse just above): result.ranges is already
		// Boolean-valued -- see mergeEntryPoints' own doc -- so there's no PatternConstruct
		// identity to lose by sharing it as-is instead of re-keying/copying.
		entryMap = result.ranges;
	}

	@Override
	void buildMatcher() {
		if (!isUnquantified()) {
			buildLoopMatcher(constructs, next(), captureConstructIndex);
			return;
		}
		if (constructs.isEmpty()) {
			// Bare flags-only group -- see buildEntryMap()'s matching case. `next` is guaranteed
			// compiled by now (tail-to-front compile order), unlike when buildEntryMap() ran.
			MatcherConstruct.aliasOrPassThrough(this, next().matcher());
			return;
		}
		if (isCapturing()) {
			// compileTarget (a CaptureEndPatternConstruct) must itself be compiled before the branches below,
			// since building its own EndCaptureMatcherConstruct needs `next.matcher` -- guaranteed
			// available now (unlike when buildEntryMap() computed compileTarget's entry point).
			compileTarget().compile(next());
		}
		// Uses rawEntryElse (the real, non-identity-rewritten candidate), not the
		// (rekeyed-to-`this`) entryMap/entryElse fields -- see rawEntryElse's doc: for the capturing
		// case, `this.matcher` isn't set yet at this point; for the non-capturing case, the flattened
		// chain's head node itself becomes `this.matcher`, so resolving branches through the
		// rekeyed-to-`this` entryMap would resolve every entry back to this very node (an infinite
		// self-dispatch loop) instead of to the actual branch matchers. Compiled here, deliberately
		// with no dispatch gating of its own (dispatchEntrySet/dispatchFailedEntry left null), and
		// EXCLUDED from the ordinary candidate list handed to buildFlattenedChain below -- unlike the
		// old fork-chain design (which could cheaply wrap the SAME already-compiled, ungated
		// candidate.matcher in two different fork nodes -- one at its own list position, one as the
		// tail fallback -- since gating lived in the separate fork objects, not the node itself), this
		// flattened design bakes gating into the candidate's own single compiled node, so the same
		// node can't simultaneously be "gated at its natural position" and "the ungated final
		// fallback". Dropping it from the ordinary list is only a behavior change when rawEntryElse
		// ALSO claims real (non-empty) explicit ranges of its own (e.g. a nullable branch reaching the end of
		// the pattern) -- those ranges are still checked for disjointness against every sibling's, by
		// buildFlattenedChain's `elseCandidate` argument (mergeEntryPoints does NOT check overlap), so
		// nothing goes unvalidated. The tail is only reached once every sibling's gate has missed, so a
		// sibling can't claim those code points either, and their order relative to siblings is moot.
		//
		// EXCEPT an end-of-find catch-all (a branch that can complete the whole pattern without
		// consuming anything, e.g. `a*` at the end of the pattern): that one is NOT lowest-priority --
		// `java.util.regex` takes the first alternative that succeeds, and under find()/lookingAt() a
		// branch that can end here succeeds whatever follows. So it keeps its own list position (in
		// chainCandidates, behind a mode-aware gate) and there is no tail fallback for it.
		PatternConstruct endOfFindCandidate =
				rawEntryElse != null && rawEntryElse.elseIsEndOfFind() ? rawEntryElse : null;
		PatternConstruct tailElse = endOfFindCandidate != null ? null : rawEntryElse;
		MatcherConstruct elseTarget = tailElse != null ? tailElse.compile(compileTarget()) : null;
		List<PatternConstruct> chainCandidates;
		if (tailElse == null) {
			chainCandidates = constructs;
		} else {
			chainCandidates = new ArrayList<>(constructs.size());
			for (PatternConstruct c : constructs) {
				if (c != tailElse) {
					chainCandidates.add(c);
				}
			}
		}
		if (isCapturing()) {
			MatcherConstruct dispatch = buildFlattenedChain(null, flags, pattern, chainCandidates, "union subpattern", compileTarget(), elseTarget, tailElse, endOfFindCandidate);
			new BeginCaptureMatcherConstruct(this, captureConstructIndex, dispatch);
		} else {
			buildFlattenedChain(this, flags, pattern, chainCandidates, "union subpattern", compileTarget(), elseTarget, tailElse, endOfFindCandidate);
		}
	}

	@Override
	final CodePointSet skipZeroWidthEntrySet(boolean checkAssertions, @Nullable CodePointSet bodyLastCharSet) {
		if (isUnquantified() && !constructs.isEmpty()) {
			MutableCodePointSet result = new ArrayCodePointSet();
			for (PatternConstruct branch : constructs) {
				result.insertAll(branch.skipZeroWidthEntrySet(checkAssertions, bodyLastCharSet));
			}
			return result;
		}
		return super.skipZeroWidthEntrySet(checkAssertions, bodyLastCharSet);
	}

	@Override
	final @Nullable CodePointSet lastCharSet() {
		if (min < 1 || constructs.isEmpty()) {
			return null;
		}
		return unionLastCharSet(constructs);
	}

	@Override
	final @Nullable CodePointSet firstCharSet() {
		if (min < 1 || constructs.isEmpty()) {
			return null;
		}
		MutableCodePointSet result = new ArrayCodePointSet();
		for (PatternConstruct branch : constructs) {
			CodePointSet branchSet = branch.firstCharSet();
			if (branchSet == null) {
				return null;
			}
			result.insertAll(branchSet);
		}
		return result;
	}

	@Override
	public final LookbehindPatternConstruct.@Nullable SingleCodePointBody resolveSingleCodePointBody() {
		if (min != 1 || max != 1 || constructs.isEmpty()) {
			return null;
		}
		boolean isCapturing = captureConstructIndex >= 0;
		if (constructs.size() == 1) {
			LookbehindPatternConstruct.SingleCodePointBody inner = constructs.get(0).resolveSingleCodePointBody();
			if (inner == null) {
				return null;
			}
			if (!isCapturing) {
				return inner;
			}
			// A capturing group can't itself wrap another capturing group here -- there's only
			// one code point behind this position for at most one group to claim.
			return inner.captureConstructIndex == -1
					? new LookbehindPatternConstruct.SingleCodePointBody(inner.codePoints, captureConstructIndex)
					: null;
		}
		// A real alternation: every branch must resolve with no capturing group of its own --
		// only the whole alternation (via an enclosing capturing group on this union) may
		// capture, e.g. (?<=(a|b)) is supported, (?<=(a)|(b)) is not.
		MutableCodePointSet result = new ArrayCodePointSet();
		for (PatternConstruct branch : constructs) {
			LookbehindPatternConstruct.SingleCodePointBody inner = branch.resolveSingleCodePointBody();
			if (inner == null || inner.captureConstructIndex != -1) {
				return null;
			}
			result.insertAll(inner.codePoints);
		}
		return new LookbehindPatternConstruct.SingleCodePointBody(result, captureConstructIndex);
	}
}

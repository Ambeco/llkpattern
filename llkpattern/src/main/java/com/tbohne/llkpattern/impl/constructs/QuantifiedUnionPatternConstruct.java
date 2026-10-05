package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.ArrayCodePointSet;
import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.CodePointSet.MutableCodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class QuantifiedUnionPatternConstruct extends QuantifiablePatternConstruct {
	public int captureConstructIndex = 0;
	public String captureName = "";
	// Pre-sized to 4: 99.63% of unions have <= 4 elements, mean 1.38 (corpus measurement, 2026-09-27).
	public final ConstructList constructs = new ConstructList(4);

	// The real catch-all candidate, which buildMatcher() needs: the inherited entryElse is re-keyed onto
	// `this` (like SequencePatternConstruct's) for ancestors' identity checks.
	private @Nullable PatternConstruct rawEntryElse;

	// Compile target for the unquantified, non-empty case (next, or a CaptureEndPatternConstruct for a
	// capturing group): computed in buildEntryMap() without compile() calls and reused by buildMatcher(),
	// which needs the SAME CaptureEndPatternConstruct instance. @Nullable only because it has no value
	// before buildEntryMap().
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
			// Bare flags-only group ("(?i)"): passes through to next, as in buildEntryMap. Safe even if next
			// resolves back to an ancestor loop under construction: a QuantifiablePatternConstruct there keeps the
			// cycle-guarded default, so the cycle is still caught one level down.
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
			// Bare flags-only group ("(?s)", no body): the only way to get empty constructs (parseUnion rejects
			// empty bodies). It is zero-width and always succeeds (the parser already toggled flags), so pass
			// through to next rather than compile as a dispatch node; an empty entry map once broke "(?s)abx".
			entryMap = next.getEntryPointMap();
			if (next.getEntryElse() != null) {
				entryElse = this;
			}
			// matcher isn't assigned here: next.matcher may not be built yet; buildMatcher() assigns it.
			return;
		}

		// Each branch's compile target is this union's next (or, for a capturing group, a marker that ends the
		// capture first), since choosing a branch consumes nothing. Merging entry points rejects any two
		// branches that could match the same next code point: the core LL(1) restriction. Doesn't compile()
		// anything (design.md "Entry-point computation vs. matcher compilation"); buildMatcher() does.
		PatternConstruct target = next;
		if (isCapturing()) {
			target = new CaptureEndPatternConstruct(startIndex, captureConstructIndex, next);
			target.flags = flags;
		}
		compileTarget = target;
		for (int ci = 0; ci < constructs.size; ci++) {
			PatternConstruct part = constructs.items[ci];
			part.next = target;
		}
		MergedEntries result = mergeEntryPoints(pattern, constructs, "union subpattern");
		rawEntryElse = result.entryElse();
		// Re-keyed onto `this` so an ancestor's identity checks see this union, not one of its branches' leaves
		// (as in SequencePatternConstruct.buildEntryMap).
		if (rawEntryElse != null) {
			entryElse = this;
		}
		// Aliased directly: entryMap is a plain set.
		entryMap = result.ranges;
	}

	@Override
	void buildMatcher() {
		if (!isUnquantified()) {
			buildLoopMatcher(constructs, next(), captureConstructIndex);
			return;
		}
		if (constructs.isEmpty()) {
			// Bare flags-only group: see buildEntryMap(). `next` is compiled by now (tail-to-front).
			MatcherConstruct.aliasOrPassThrough(this, next().matcher());
			return;
		}
		if (isCapturing()) {
			// Must compile before the branches: its EndCaptureMatcherConstruct needs next.matcher, available now.
			compileTarget().compile(next());
		}
		// Uses rawEntryElse (the real candidate), not the entryMap/entryElse fields re-keyed to `this`:
		// resolving through those would send every entry back to this node (infinite self-dispatch), since
		// the chain head becomes this.matcher. It is compiled here ungated and EXCLUDED from chainCandidates:
		// gating lives in the candidate's own single node, so it can't be both "gated at its list position"
		// and "the ungated tail fallback". That only changes behavior when rawEntryElse also claims explicit
		// ranges (e.g. a nullable branch reaching the pattern end); buildFlattenedChain's `elseCandidate`
		// still checks those against every sibling (mergeEntryPoints doesn't).
		//
		// EXCEPT an end-of-find catch-all (a branch that can complete the pattern without consuming anything,
		// e.g. `a*` at the end): java.util.regex takes the first alternative that succeeds, and under
		// find()/lookingAt() that branch succeeds whatever follows. So it keeps its list position behind a
		// mode-aware gate and gets no tail fallback.
		PatternConstruct endOfFindCandidate =
				rawEntryElse != null && rawEntryElse.elseIsEndOfFind() ? rawEntryElse : null;
		PatternConstruct tailElse = endOfFindCandidate != null ? null : rawEntryElse;
		MatcherConstruct elseTarget = tailElse != null ? tailElse.compile(compileTarget()) : null;
		ConstructList chainCandidates;
		if (tailElse == null) {
			chainCandidates = constructs;
		} else {
			chainCandidates = new ConstructList(constructs.size());
			for (int ci = 0; ci < constructs.size; ci++) {
			PatternConstruct c = constructs.items[ci];
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
			for (int ci = 0; ci < constructs.size; ci++) {
			PatternConstruct branch = constructs.items[ci];
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
		for (int ci = 0; ci < constructs.size; ci++) {
			PatternConstruct branch = constructs.items[ci];
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
			// At most one group can claim the single code point behind this position.
			return inner.captureConstructIndex == -1
					? new LookbehindPatternConstruct.SingleCodePointBody(inner.codePoints, captureConstructIndex)
					: null;
		}
		// A real alternation: every branch must resolve with no capturing group of its own; only the whole
		// alternation may capture, e.g. (?<=(a|b)) is supported, (?<=(a)|(b)) is not.
		MutableCodePointSet result = new ArrayCodePointSet();
		for (int ci = 0; ci < constructs.size; ci++) {
			PatternConstruct branch = constructs.items[ci];
			LookbehindPatternConstruct.SingleCodePointBody inner = branch.resolveSingleCodePointBody();
			if (inner == null || inner.captureConstructIndex != -1) {
				return null;
			}
			result.insertAll(inner.codePoints);
		}
		return new LookbehindPatternConstruct.SingleCodePointBody(result, captureConstructIndex);
	}
}

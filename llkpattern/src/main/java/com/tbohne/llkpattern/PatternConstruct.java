package com.tbohne.llkpattern;

import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeMap;
import com.google.common.collect.TreeRangeSet;
import com.tbohne.llkpattern.MatcherConstruct.*;
import com.tbohne.llkpattern.NamedCharClass.*;

import java.util.Map.Entry;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

abstract class PatternConstruct {
	static final RangeMap<Integer, PatternConstruct> EMPTY_MAP = TreeRangeMap.create();

	final int startIndex;
	int endIndex = -1;

	// The parser's `flags` (CASE_INSENSITIVE/UNICODE_CASE/etc.) in effect at the moment this
	// construct was parsed -- i.e. after any enclosing inline "(?i:...)" toggle has been applied,
	// and before it's restored on group exit. Mutable (not a constructor param) purely to keep
	// every existing PatternConstruct subclass constructor unchanged; PatternParser sets it right
	// after each `new` call. Propagated to this construct's compiled MatcherConstruct (see
	// MatcherConstruct's own `flags` field) so CASE_INSENSITIVE folding at match time is scoped to
	// wherever the pattern was written case-insensitively, not the whole pattern's global flags --
	// see "Inline flag toggles don't actually locally scope anything" in remaining_work.md.
	int flags = 0;

	@MonotonicNonNull MatcherConstruct matcher;

	// The construct that comes after this one -- i.e. what compile() was last called with.
	// Recorded so that, once this construct appears as a value in some ancestor's entryMap, that
	// ancestor's MatcherConstruct-building code can rely on `entryMapValue.matcher` already being
	// set (this construct's own compile() already ran, tail-to-front) rather than needing to
	// thread the continuation through again.
	@MonotonicNonNull PatternConstruct next;

	// Which PatternConstruct handles each possible next code point, once this construct (and
	// anything it can trivially skip, e.g. an optional quantifier) has matched. Populated by
	// buildEntryMap(); consumed while compiling a containing QuantifiedUnion/Sequence to detect
	// ambiguous branches, and to build the MatcherConstruct graph.
	RangeMap<Integer, PatternConstruct> entryMap = TreeRangeMap.create();
	@MonotonicNonNull PatternConstruct entryElse;


	PatternConstruct(int startIndex) {
		this.startIndex = startIndex;
	}

	PatternConstruct(int startIndex, int endIndex) {
		this.startIndex = startIndex;
		this.endIndex = endIndex;
	}

	/**
	 * Compiles this construct (and, transitively, whatever it depends on) into a MatcherConstruct
	 * graph, returning the node that represents "start matching this construct here". See
	 * design.md's "The compile() algorithm and cycle handling" section.
	 *
	 * <p>Memoized on {@link #matcher}: if it's already set -- either because this exact construct
	 * was already compiled, or because a MatcherConstruct constructor further up the call stack
	 * already self-registered here to break a cycle -- this returns immediately without redoing
	 * (or re-entering) any work.
	 */
	@Nullable MatcherConstruct compile(PatternConstruct next) {
		if (matcher != null) {
			return matcher;
		}
		this.next = next;
		buildEntryMap(next);
		if (matcher == null) {
			// buildMatcher() constructs `new SomeMatcherConstruct(this, ...)`, whose constructor's
			// first act is `this.matcher = it` (see MatcherConstruct's class doc) -- so `matcher` is
			// set as a side effect of the call below, not by assigning its return value.
			buildMatcher();
		}
		return matcher;
	}

	abstract void buildEntryMap(PatternConstruct next);

	abstract void buildMatcher();

	/** Converts a Guava RangeMap (arbitrary bound types) into a CodePointMap ({@code [min,max)}). */
	static TreeCodePointMap<PatternConstruct> toCodePointMap(RangeMap<Integer, PatternConstruct> rangeMap) {
		TreeCodePointMap<PatternConstruct> result = new TreeCodePointMap<>();
		for (Entry<Range<Integer>, PatternConstruct> e : rangeMap.asMapOfRanges().entrySet()) {
			Range<Integer> canon = e.getKey().canonical(DiscreteDomain.integers());
			result.put(canon.lowerEndpoint(), canon.upperEndpoint(), e.getValue());
		}
		return result;
	}

	/**
	 * Returns the first code point range present (with a different value) in both maps, or null
	 * if none overlap. {@code CodePointMap.intersectionRejectingConflicts} would detect the same
	 * thing, but throws immediately rather than letting us report which ranges/branches conflict.
	 */
	static @Nullable Entry<CodePointMap.Range, PatternConstruct> findFirstOverlap(
			CodePointMap<PatternConstruct> merged, CodePointMap<PatternConstruct> branch) {
		for (Entry<CodePointMap.Range, PatternConstruct> branchEntry : branch.entrySet()) {
			for (Entry<CodePointMap.Range, PatternConstruct> mergedEntry : merged.entrySet()) {
				int loMax = Math.min(branchEntry.getKey().max, mergedEntry.getKey().max);
				int hiMin = Math.max(branchEntry.getKey().min, mergedEntry.getKey().min);
				if (hiMin < loMax) {
					return new CodePointMap.ImmutableEntry<>(
							new CodePointMap.Range(hiMin, loMax), mergedEntry.getValue());
				}
			}
		}
		return null;
	}

	static TreeCodePointMap<PatternConstruct> mergeEntryMapRejectingAmbiguity(
			String pattern, TreeCodePointMap<PatternConstruct> merged, PatternConstruct branch, String branchDescription) {
		TreeCodePointMap<PatternConstruct> branchMap = toCodePointMap(branch.entryMap);
		Entry<CodePointMap.Range, PatternConstruct> conflict = findFirstOverlap(merged, branchMap);
		if (conflict != null) {
			throw PatternSyntaxException.throwWithReferences(
					pattern,
					branch.startIndex,
					branchDescription, " starting at index ", branch.startIndex,
					" accepts character(s) ",
					new PatternSyntaxException.CodePoint(conflict.getKey().min),
					"-",
					new PatternSyntaxException.CodePoint(conflict.getKey().max - 1),
					", but a prior part of the same construct already claims those, which is not allowed");
		}
		return (TreeCodePointMap<PatternConstruct>) merged.union(branchMap);
	}

	/** Result of {@link #compileAndMergeCandidates}. */
	static final class MergedEntries {
		final TreeCodePointMap<PatternConstruct> ranges;
		// Whichever candidate claimed "matches any other character" (at most one is allowed to).
		final @Nullable PatternConstruct elseCandidate;

		MergedEntries(TreeCodePointMap<PatternConstruct> ranges, @Nullable PatternConstruct elseCandidate) {
			this.ranges = ranges;
			this.elseCandidate = elseCandidate;
		}

		@Nullable PatternConstruct entryElse() {
			return elseCandidate != null ? elseCandidate.entryElse : null;
		}
	}

	/**
	 * Compiles each of {@code candidates} against {@code compileTarget} (harmless/idempotent if a
	 * candidate is already compiled -- e.g. {@code compileTarget} itself, when it's included as one
	 * of the candidates), then merges their entry ranges, rejecting the first ambiguity: two
	 * candidates whose entry ranges overlap, or two candidates that both accept "any other
	 * character". Used both for plain alternation ({@code candidates} = a union's branches) and for
	 * loop dispatch ({@code candidates} = a loop's body parts plus its own {@code next}, since
	 * "keep looping" vs "exit" must be just as unambiguous as any other branch choice).
	 */
	static MergedEntries compileAndMergeCandidates(
			String pattern, List<PatternConstruct> candidates, PatternConstruct compileTarget, String candidateNounPlural) {
		TreeCodePointMap<PatternConstruct> merged = new TreeCodePointMap<>();
		PatternConstruct elseCandidate = null;
		for (int i = 0; i < candidates.size(); i++) {
			PatternConstruct candidate = candidates.get(i);
			candidate.compile(compileTarget);
			if (candidate.entryElse != null) {
				if (elseCandidate != null) {
					throw PatternSyntaxException.throwWithReferences(
							pattern,
							candidate.startIndex,
							candidateNounPlural, " starting at index ", candidate.startIndex,
							" allows any character, but another ", candidateNounPlural,
							" starting at index ", elseCandidate.startIndex,
							" also allows any character, which is ambiguous");
				}
				elseCandidate = candidate;
			}
			merged = mergeEntryMapRejectingAmbiguity(pattern, merged, candidate, candidateNounPlural + " #" + (i + 1));
		}
		return new MergedEntries(merged, elseCandidate);
	}

	static abstract class QuantifiableConstruct extends PatternConstruct {
		final String pattern;
		int min = 1;
		int max = 1;
		int quantifiableIndex = -1;

		QuantifiableConstruct(String pattern, int startIndex) {
			super(startIndex);
			this.pattern = pattern;
		}

		QuantifiableConstruct(String pattern, int startIndex, int endIndex) {
			super(startIndex, endIndex);
			this.pattern = pattern;
		}

		/** True for a "plain" {@code {1,1}} construct -- i.e. no real repetition/optionality. */
		boolean isUnquantified() {
			return min == 1 && max == 1;
		}

		/**
		 * Builds the loop matcher graph (a plain {@code DispatchMatcherConstruct}, built via its
		 * loop-flavored constructor -- see MatcherConstruct and design.md) AND this construct's own
		 * {@code entryMap}/{@code entryElse}, for the quantified ({@code !isUnquantified()}) case.
		 * Must be called from {@code buildEntryMap} (not {@code buildMatcher}) -- the self-registering
		 * constructor needs to run, setting {@code this.matcher}, before {@code body}'s own
		 * {@code compile()} calls, since they dispatch back to {@code this} once they finish matching.
		 */
		void buildLoopEntryMapAndMatcher(List<PatternConstruct> body, PatternConstruct next) {
			buildLoopEntryMapAndMatcher(body, next, -1);
		}

		/** As above, but also a capturing group (e.g. {@code (a)*}) -- see DispatchMatcherConstruct. */
		void buildLoopEntryMapAndMatcher(List<PatternConstruct> body, PatternConstruct next, int captureConstructIndex) {
			new DispatchMatcherConstruct(this, body, next, captureConstructIndex);
		}
	}

	static final class QuantifiedUnion extends QuantifiableConstruct {
		final int parentFlags;

		int captureConstructIndex = 0;
		String captureName = "";
		final List<PatternConstruct> constructs = new ArrayList<>();
		boolean tempFlags = false;

		QuantifiedUnion(String pattern, int startIndex, int parentFlags) {
			super(pattern, startIndex);
			this.parentFlags = parentFlags;
		}

		private boolean isCapturing() {
			return captureConstructIndex != -1;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			if (!isUnquantified()) {
				buildLoopEntryMapAndMatcher(constructs, next, captureConstructIndex);
				return;
			}
			if (constructs.isEmpty()) {
				// Bug fix (2026-09-06): a bare flags-only group ("(?s)", no ":", no body) is the
				// only way to reach this constructor with an empty `constructs` list -- every other
				// path (a real "()"/"(?:)"/"(?<name>)") goes through parseUnion(), which rejects an
				// empty body via throwEmptySequence before a QuantifiedUnion with zero constructs can
				// ever exist. Previously this fell through to compileAndMergeCandidates() with an
				// empty candidate list, producing an empty entryMap/entryElse -- i.e. a
				// DispatchMatcherConstruct that matches nothing at all, silently breaking the
				// surrounding sequence ("(?s)abx" stopped matching "abx"). A bare flags group is
				// zero-width and always succeeds -- its only job was toggling `flags` for
				// PatternParser, already done by the caller -- so just pass through to `next` exactly
				// as an empty Sequence element would, instead of compiling as its own dispatch node.
				entryMap = next.entryMap;
				entryElse = next.entryElse;
				matcher = next.matcher;
				return;
			}

			// Compile every branch (tail-to-front relative to this union: each branch's "next" is
			// this union's own "next" -- or, for a capturing group, a marker that ends the capture
			// before reaching the real next -- since choosing a branch doesn't itself consume
			// anything) and merge their entry ranges, rejecting any two branches that could both
			// match the same next code point -- the core LL(1) restriction this library is built on.
			PatternConstruct compileTarget = next;
			if (isCapturing()) {
				compileTarget = new CaptureEndMarker(startIndex, captureConstructIndex, next);
				compileTarget.flags = flags;
				// Branches read `owner.next.matcher` while building their own matcher (tail-to-front),
				// so compileTarget must already be fully compiled by then -- unlike a normal `next`,
				// nothing else ever calls compile() on a freshly-constructed marker for us.
				compileTarget.compile(next);
			}
			MergedEntries result = compileAndMergeCandidates(pattern, constructs, compileTarget, "union subpattern");
			entryElse = result.entryElse();
			for (Entry<CodePointMap.Range, PatternConstruct> e : result.ranges.entrySet()) {
				entryMap.put(Range.closedOpen(e.getKey().min, e.getKey().max), e.getValue());
			}
		}

		@Override
		void buildMatcher() {
			if (!isUnquantified()) {
				return; // matcher was already built by buildLoopEntryMapAndMatcher, above.
			}
			if (isCapturing()) {
				MatcherConstruct dispatch = new DispatchMatcherConstruct(entryMap, entryElse, flags);
				new BeginCaptureMatcherConstruct(this, captureConstructIndex, dispatch);
			} else {
				new DispatchMatcherConstruct(this);
			}
		}
	}

	/**
	 * A zero-width marker inserted as a capturing group's branches' "next", so that an
	 * EndCaptureMatcherConstruct fires (recording the captured substring) right as the group's
	 * content finishes matching, before control actually reaches whatever follows the group. Has
	 * the same entry set as {@code realNext} -- inserting it must not change what characters are
	 * considered ambiguous for the group's branches.
	 */
	static final class CaptureEndMarker extends PatternConstruct {
		final int captureConstructIndex;
		final PatternConstruct realNext;

		CaptureEndMarker(int startIndex, int captureConstructIndex, PatternConstruct realNext) {
			super(startIndex);
			this.captureConstructIndex = captureConstructIndex;
			this.realNext = realNext;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			// realNext is already compiled by the time any of this marker's callers need it -- it's
			// the capturing group's own `next`, which (like any `next`) was compiled before the group
			// itself, tail-to-front.
			//
			// Bug fix (2026-09-06): this used to just alias `entryMap = realNext.entryMap` directly
			// -- but that leaves every entry's VALUE as realNext itself (whatever realNext.buildEntryMap
			// put there), not this marker. That silently broke identity checks like
			// DispatchMatcherConstruct's loop-flavored constructor's `e.getValue() == next` (used to tell "the loop is
			// exiting toward `next`" from "the loop is continuing") whenever THIS marker was passed
			// in as that `next` -- i.e. any non-quantified capturing group whose content contains its
			// own internal loop, e.g. "([a-z]+)!": the exit character got misclassified as "continue
			// the loop, dispatch straight to realNext.matcher", bypassing this marker's own
			// EndCaptureMatcherConstruct entirely, so the capture's `result` was set on entry but
			// never finalized (group(n) returned null even though the whole pattern matched). Found
			// via GroupSyntaxTest. Fixed by re-keying every range onto `this` instead of realNext,
			// same as any other PatternConstruct's own buildEntryMap does for itself.
			for (java.util.Map.Entry<Range<Integer>, PatternConstruct> e :
					realNext.entryMap.asMapOfRanges().entrySet()) {
				entryMap.put(e.getKey(), this);
			}
			entryElse = realNext.entryElse != null ? this : null;
		}

		@Override
		void buildMatcher() {
			new EndCaptureMatcherConstruct(this, captureConstructIndex, realNext.matcher);
		}
	}


	static final class Sequence extends PatternConstruct {
		final List<PatternConstruct> patterns = new ArrayList<>();

		Sequence(int startIndex) {
			super(startIndex);
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			// Compile tail-to-front: the last element's next is this sequence's own next, and each
			// earlier element's next is the element right after it (already compiled by the time we
			// get to it).
			PatternConstruct tail = next;
			for (int i = patterns.size() - 1; i >= 0; i--) {
				PatternConstruct part = patterns.get(i);
				if (part instanceof WordBoundaryConstruct && i > 0) {
					((WordBoundaryConstruct) part).priorCharSet = lastCharSet(patterns.get(i - 1));
				}
				part.compile(tail);
				tail = part;
			}
			entryMap = patterns.get(0).entryMap;
			entryElse = patterns.get(0).entryElse;
		}

		@Override
		void buildMatcher() {
			// A Sequence has no matching behavior of its own -- it's exactly whatever its first
			// element compiled to (already compiled by buildEntryMap, above).
			matcher = patterns.get(0).matcher;
		}
	}

	static final class LiteralString extends PatternConstruct {
		final String value;

		LiteralString(int startIndex, int endIndex, String value) {
			super(startIndex, endIndex);
			this.value = value;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			entryMap.put(Range.singleton(value.codePointAt(0)), this);
		}

		@Override
		void buildMatcher() {
			new LiteralMatcherConstruct(this, value);
		}
	}

	/**
	 * {@code \1}/{@code \k<name>}. {@code referencedGroup} is resolved at parse time (see
	 * PatternParser's {@code tryParseBackReference}) to the actual, already-fully-parsed
	 * {@code QuantifiedUnion} the reference points at -- forward references and references to
	 * undefined groups are rejected there, before a BackReference is ever constructed. See
	 * design.md's "Backreferences" section for the full design.
	 */
	static final class BackReference extends PatternConstruct {
		final int captureConstructIndex;
		final QuantifiedUnion referencedGroup;

		BackReference(int startIndex, int endIndex, int captureConstructIndex, QuantifiedUnion referencedGroup) {
			super(startIndex, endIndex);
			this.captureConstructIndex = captureConstructIndex;
			this.referencedGroup = referencedGroup;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			RangeSet<Integer> firstChars = firstCharSet(referencedGroup);
			if (firstChars == null) {
				// Possibly-empty (e.g. "(a*)\1") or otherwise not-statically-known referenced group --
				// fall back to the catch-all entry set rather than risk silently wrong zero-width
				// handling. See design.md's "Backreferences" section.
				entryElse = this;
				return;
			}
			for (Range<Integer> range : firstChars.asRanges()) {
				entryMap.put(range, this);
			}
		}

		@Override
		void buildMatcher() {
			new BackReferenceMatcherConstruct(this, captureConstructIndex);
		}
	}

	static final class ComplexCharacter
			extends PatternConstruct {
		RangeSet<Integer> ranges = TreeRangeSet.create();
		@Nullable PatternConstruct dotElse;

		ComplexCharacter(int startIndex, RangeSet<Integer> ranges) {
			super(startIndex);
			this.ranges = ranges;
		}

		ComplexCharacter(int startIndex, int endIndex, RangeSet<Integer> ranges) {
			super(startIndex, endIndex);
			this.ranges = ranges;
		}

		ComplexCharacter(int startIndex, int endIndex, RangeSet<Integer> ranges, boolean positiveMatch) {
			super(startIndex, endIndex);
			this.ranges = ranges;
		}

		ComplexCharacter(int startIndex, int character) {
			super(startIndex);
			ranges.add(Range.singleton(character));
		}

		ComplexCharacter(int startIndex) {
			super(startIndex);
		}

		/**
		 * {@code ranges} clamped to the actual Unicode code point domain {@code [0,
		 * MAX_CODE_POINT]}. Every {@code Range.complement()} in this codebase (negated classes via
		 * {@code [^...]}, {@code .}, and built-ins like {@code \D}/{@code \S}/{@code \W}) produces a
		 * mathematically unbounded {@code RangeSet} that extends to {@code Integer.MIN_VALUE}/{@code
		 * MAX_VALUE} -- Guava has no concept of "the codepoint domain" to bound it to. Left unclamped,
		 * such a range can swallow {@code -1}, the sentinel {@code Matcher} uses throughout for
		 * "no more input" (see {@code Matcher#peek}), making a negated class at end-of-input look
		 * like a match and crash trying to then consume a code point past the end of the string. Every
		 * caller that turns {@code ranges} into an actual dispatch/entry map (as opposed to still
		 * combining/negating them further) must go through this, not raw {@code ranges.asRanges()}.
		 */
		RangeSet<Integer> validRanges() {
			return ranges.subRangeSet(Range.closed(0, Character.MAX_CODE_POINT));
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			for (Range<Integer> range : validRanges().asRanges()) {
				entryMap.put(range, this);
			}
			entryElse = dotElse;
		}

		@Override
		void buildMatcher() {
			new SingleCharMatcherConstruct(this);
		}
	}

	static final class ComplexQuantifiedCharacter extends QuantifiableConstruct {
		final ComplexCharacter delegate;

		ComplexQuantifiedCharacter(String pattern, int startIndex, ComplexCharacter delegate) {
			super(pattern, startIndex, delegate.endIndex);
			this.delegate = delegate;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			if (!isUnquantified()) {
				buildLoopEntryMapAndMatcher(List.of(delegate), next);
				return;
			}
			delegate.compile(next);
			for (Range<Integer> range : delegate.validRanges().asRanges()) {
				entryMap.put(range, this);
			}
		}

		@Override
		void buildMatcher() {
			if (!isUnquantified()) {
				return; // matcher was already built by buildLoopEntryMapAndMatcher, above.
			}
			// Unquantified (i.e. exactly-once) case: this construct behaves exactly like its
			// delegate ComplexCharacter (already compiled by buildEntryMap, above).
			matcher = delegate.matcher;
		}
	}

	static final class BoundaryConstruct extends PatternConstruct {
		enum BoundaryEnum {
			InputBegin,
			InputEndExceptTerminator,
			InputEnd
		}

		final BoundaryEnum type;

		BoundaryConstruct(int startIndex, int endIndex, BoundaryEnum type) {
			super(startIndex, endIndex);
			this.type = type;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			entryElse = this;
		}

		@Override
		void buildMatcher() {
			new BoundaryMatcherConstruct(this, type);
		}
	}

	/**
	 * {@code ^} (line begin) / {@code $} (line end). Split out from {@link BoundaryConstruct}
	 * (2026-09-07) for the same reason {@code \b}/{@code \B} were: a real, non-stub
	 * implementation with its own logic (MULTILINE-aware line-terminator scanning), distinct
	 * enough from {@code BoundaryConstruct}'s remaining, still-unimplemented types that sharing
	 * one {@code BoundaryEnum}-keyed dispatch added indirection for no benefit. See design.md's
	 * "Boundary matching" section for the matching design.
	 */
	static final class LineBoundaryConstruct extends PatternConstruct {
		final boolean isLineBegin; // true: ^, false: $

		LineBoundaryConstruct(int startIndex, int endIndex, boolean isLineBegin) {
			super(startIndex, endIndex);
			this.isLineBegin = isLineBegin;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			entryElse = this;
		}

		@Override
		void buildMatcher() {
			new LineBoundaryMatcherConstruct(this, isLineBegin);
		}
	}

	/**
	 * {@code \b} (word boundary) / {@code \B} (non-word-boundary). Split out from {@link
	 * BoundaryConstruct} (2026-09-07) since these two are the only boundary types with an actual
	 * implementation, plus a compile-time optimization {@link BoundaryConstruct}'s other types
	 * don't need -- see design.md's "Boundary matching" section for the full design.
	 */
	static final class WordBoundaryConstruct extends PatternConstruct {
		final String pattern;
		final boolean isWordBoundary; // true: \b, false: \B

		// The set of code points that could be the last one consumed by whatever immediately
		// precedes this boundary in its enclosing Sequence, if statically known -- set by
		// Sequence.buildEntryMap (via lastCharSet(), below) before compile() runs; null (the
		// default, e.g. when this boundary opens its Sequence, or isn't in one at all) means "not
		// statically known", which is always a safe fallback, just a missed optimization.
		@Nullable RangeSet<Integer> priorCharSet;

		WordBoundaryConstruct(String pattern, int startIndex, int endIndex, boolean isWordBoundary) {
			super(startIndex, endIndex);
			this.pattern = pattern;
			this.isWordBoundary = isWordBoundary;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			entryElse = this;
		}

		private enum Wordness {
			WORD,
			NON_WORD,
			UNKNOWN
		}

		private static Wordness classify(@Nullable RangeSet<Integer> set, RangeSet<Integer> wordSet) {
			if (set == null) {
				return Wordness.UNKNOWN;
			}
			if (wordSet.enclosesAll(set)) {
				return Wordness.WORD;
			}
			if (wordSet.complement().enclosesAll(set)) {
				return Wordness.NON_WORD;
			}
			return Wordness.UNKNOWN;
		}

		@Override
		void buildMatcher() {
			// See design.md's "Boundary matching" section and the class doc for
			// WordBoundaryMatcherConstruct for the full optimization rationale. In brief: both sides
			// of the boundary (the character just consumed, and the one about to be) are classified
			// as always-word/always-non-word/unknown at compile time; whichever side is statically
			// known doesn't need to be checked at match time at all.
			RangeSet<Integer> wordSet = RegexCharacterClass.w.get(flags);
			Wordness prior = classify(priorCharSet, wordSet);
			RangeSet<Integer> peekRanges = null;
			if (next.entryElse == null) {
				peekRanges = TreeRangeSet.create();
				for (Range<Integer> range : next.entryMap.asMapOfRanges().keySet()) {
					peekRanges.add(range);
				}
			}
			Wordness peek = classify(peekRanges, wordSet);

			if (prior != Wordness.UNKNOWN && peek != Wordness.UNKNOWN) {
				boolean isBoundaryHere = (prior != peek);
				if (isBoundaryHere != isWordBoundary) {
					throw PatternSyntaxException.throwWithReferences(
							pattern,
							startIndex,
							(isWordBoundary ? "\\b" : "\\B"),
							" at index ", startIndex,
							" can never match: the preceding and following characters are ",
							(isBoundaryHere ? "always different word-ness" : "always the same word-ness"),
							" here, which is the opposite of what ",
							(isWordBoundary ? "\\b" : "\\B"),
							" requires");
				}
				// Statically always satisfied: a zero-width no-op, so just pass straight through.
				matcher = next.matcher;
				return;
			}

			WordBoundaryMatcherConstruct.PriorWordBoundaryMatchType priorMatchType;
			WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType peekMatchType;
			if (peek == Wordness.UNKNOWN && prior == Wordness.UNKNOWN) {
				// Neither side is statically known: fall back to comparing both at match time.
				priorMatchType = WordBoundaryMatcherConstruct.PriorWordBoundaryMatchType.Unchecked;
				peekMatchType = isWordBoundary
						? WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.PeekMustBeOppositePrior
						: WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.PeekMustBeSameAsPrior;
			} else if (peek == Wordness.UNKNOWN) {
				// prior is statically known -- fold it into a fixed direction for the (already
				// available, no extra call needed) peeked character; never need matcher.peekPrevious().
				boolean priorIsWord = (prior == Wordness.WORD);
				boolean wantsWordPeek = isWordBoundary != priorIsWord;
				priorMatchType = WordBoundaryMatcherConstruct.PriorWordBoundaryMatchType.Unchecked;
				peekMatchType = wantsWordPeek
						? WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.PeekMustBeWord
						: WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.PeekMustNotBeWord;
			} else {
				// peek is statically known -- fold it into a fixed direction for matcher.peekPrevious(),
				// which is the only case that still needs the extra backward-looking call.
				boolean peekIsWord = (peek == Wordness.WORD);
				boolean wantsWordPrior = isWordBoundary != peekIsWord;
				priorMatchType = wantsWordPrior
						? WordBoundaryMatcherConstruct.PriorWordBoundaryMatchType.PriorMustBeWord
						: WordBoundaryMatcherConstruct.PriorWordBoundaryMatchType.PriorMustBeNonWord;
				peekMatchType = WordBoundaryMatcherConstruct.PeekWordBoundaryMatchType.Unchecked;
			}
			new WordBoundaryMatcherConstruct(this, wordSet, priorMatchType, peekMatchType);
		}
	}

	/**
	 * The set of code points that could be the LAST one consumed if {@code pc} matches here, if
	 * that's statically known regardless of runtime input -- used by WordBoundaryConstruct's \b/\B
	 * compile-time optimization (see design.md's "Boundary matching" section) to classify the
	 * character immediately preceding a boundary as always/never a "word" character, the same way
	 * an ordinary entryMap already classifies the character immediately following one. Returns null
	 * ("not statically known") for anything that could match zero-width -- including this method
	 * simply not recognizing the construct -- rather than chasing what an earlier sibling might
	 * contribute in that case; that's always a safe fallback, just a missed optimization.
	 */
	static @Nullable RangeSet<Integer> lastCharSet(PatternConstruct pc) {
		if (pc instanceof LiteralString) {
			String value = ((LiteralString) pc).value;
			return value.isEmpty()
					? null
					: TreeRangeSet.create(java.util.Set.of(Range.singleton(value.codePointBefore(value.length()))));
		}
		if (pc instanceof ComplexCharacter) {
			return ((ComplexCharacter) pc).validRanges();
		}
		if (pc instanceof ComplexQuantifiedCharacter) {
			ComplexQuantifiedCharacter cqc = (ComplexQuantifiedCharacter) pc;
			return cqc.min >= 1 ? cqc.delegate.validRanges() : null;
		}
		if (pc instanceof QuantifiedUnion) {
			QuantifiedUnion union = (QuantifiedUnion) pc;
			if (union.min < 1 || union.constructs.isEmpty()) {
				return null;
			}
			RangeSet<Integer> result = TreeRangeSet.create();
			for (PatternConstruct branch : union.constructs) {
				RangeSet<Integer> branchSet = lastCharSet(branch);
				if (branchSet == null) {
					return null;
				}
				result.addAll(branchSet);
			}
			return result;
		}
		if (pc instanceof Sequence) {
			List<PatternConstruct> patterns = ((Sequence) pc).patterns;
			return patterns.isEmpty() ? null : lastCharSet(patterns.get(patterns.size() - 1));
		}
		return null;
	}

	/**
	 * The set of code points that could be the FIRST one consumed if {@code pc} matches here, if
	 * that's statically known regardless of runtime input -- the mirror image of {@link
	 * #lastCharSet}, used by {@code BackReference}'s compile-time entry-set computation (see
	 * design.md's "Backreferences" section): a backreference's possible first characters are
	 * exactly the referenced group's possible first characters. Returns null ("not statically
	 * known") for anything that could match zero-width, same safe fallback as {@code lastCharSet}.
	 */
	static @Nullable RangeSet<Integer> firstCharSet(PatternConstruct pc) {
		if (pc instanceof LiteralString) {
			String value = ((LiteralString) pc).value;
			return value.isEmpty()
					? null
					: TreeRangeSet.create(java.util.Set.of(Range.singleton(value.codePointAt(0))));
		}
		if (pc instanceof ComplexCharacter) {
			return ((ComplexCharacter) pc).validRanges();
		}
		if (pc instanceof ComplexQuantifiedCharacter) {
			ComplexQuantifiedCharacter cqc = (ComplexQuantifiedCharacter) pc;
			return cqc.min >= 1 ? cqc.delegate.validRanges() : null;
		}
		if (pc instanceof QuantifiedUnion) {
			QuantifiedUnion union = (QuantifiedUnion) pc;
			if (union.min < 1 || union.constructs.isEmpty()) {
				return null;
			}
			RangeSet<Integer> result = TreeRangeSet.create();
			for (PatternConstruct branch : union.constructs) {
				RangeSet<Integer> branchSet = firstCharSet(branch);
				if (branchSet == null) {
					return null;
				}
				result.addAll(branchSet);
			}
			return result;
		}
		if (pc instanceof Sequence) {
			List<PatternConstruct> patterns = ((Sequence) pc).patterns;
			return patterns.isEmpty() ? null : firstCharSet(patterns.get(0));
		}
		return null;
	}

	static final class EndConstruct extends PatternConstruct {

		EndConstruct(int startIndex) {
			super(startIndex);
			// "The pattern's grammar is satisfied here" -- reachable regardless of what character (or
			// lack of one) comes next, matching ANY of them via entryElse rather than only registering
			// the -1 "no more input" sentinel (see Matcher#peek()). That distinction matters for a
			// loop's "should I exit" dispatch (built by merging its body's entry ranges with `next`'s,
			// same as any other branch choice): a plain "-1 only" registration made an optional loop's
			// exit path unreachable at any position with real leftover characters -- which is exactly
			// what lookingAt()/find() need (a matched prefix with more string after it), as opposed to
			// matches() (which needs the *whole region* consumed). Both are supported by the same
			// compiled graph: EndMatcherConstruct.match() enforces the stricter check only when
			// Matcher#requireFullMatch says to -- see its doc.
			entryElse = this;
			new EndMatcherConstruct(this);
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			// An EndConstruct has no "next" -- it's the sentinel marking the end of the whole pattern.
			// entryMap is populated in the constructor (compile() never reaches here -- its `matcher
			// != null` guard short-circuits immediately, since the constructor above also sets
			// `matcher`), but is written this way for anyone reading buildEntryMap for its own sake.
		}

		@Override
		void buildMatcher() {
			// matcher is already set by the constructor -- compile() never reaches this (see its
			// `if (matcher == null)` guard) but it's implemented for completeness/symmetry.
		}
	}
}

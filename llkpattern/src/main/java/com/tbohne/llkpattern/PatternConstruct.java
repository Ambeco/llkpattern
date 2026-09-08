package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import com.tbohne.llkpattern.MatcherConstruct.*;
import com.tbohne.llkpattern.NamedCharClass.*;

import java.util.Map.Entry;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

abstract class PatternConstruct {
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

	// The set of code points this construct claims as its own entry point, once it (and anything
	// it can trivially skip, e.g. an optional quantifier) has matched. Populated by buildEntryMap()
	// (lazily, via ensureEntryPointBuilt() -- see getEntryPointMap()/getEntryElse() below);
	// consumed while compiling a containing QuantifiedUnion/Sequence to detect ambiguous branches,
	// and to build the MatcherConstruct graph. Never read directly outside this construct's own
	// buildEntryMap() -- every other reader goes through the getters.
	//
	// The value type is Boolean (always TRUE) rather than PatternConstruct, even though this looks
	// exactly like a "code point -> owning construct" map: every entryMap.put() call in every
	// buildEntryMap() override below inserts `this`, never anything else, so the value carries zero
	// information -- it's always inferable from *which* construct's entryMap you're looking at, and
	// every consumer already knows that (see e.g. Sequence.buildEntryMap's own re-keying-onto-`this`
	// comment). A genuinely multi-valued map DOES exist -- QuantifiedUnion.rawEntryMap, where an
	// entry's value is which distinct branch owns it -- but that's a different field entirely.
	MutableCodePointMap<Boolean> entryMap = new ArrayCodePointMap<>();
	@MonotonicNonNull PatternConstruct entryElse;

	private static final int ENTRY_POINT_NOT_STARTED = 0;
	private static final int ENTRY_POINT_CONSTRUCTING = 1;
	private static final int ENTRY_POINT_CONSTRUCTED = 2;
	private int entryPointState = ENTRY_POINT_NOT_STARTED;

	/**
	 * Thrown by {@link #ensureEntryPointBuilt} when computing a construct's own entry point would
	 * require that same computation to already be finished -- i.e. a quantified construct whose
	 * body can match zero characters (e.g. {@code (a?)+}), the one case this engine can't assign a
	 * meaningful "what comes next" set to. Caught and re-thrown as a {@link PatternSyntaxException}
	 * by {@code Ll1Pattern.compile()}, which has the full pattern string this needs for a proper
	 * message. See design.md's "Entry-point computation vs. matcher compilation" section.
	 */
	static final class EntryPointCycleException extends RuntimeException {
		final int startIndex;

		EntryPointCycleException(int startIndex) {
			this.startIndex = startIndex;
		}
	}

	PatternConstruct(int startIndex) {
		this.startIndex = startIndex;
	}

	PatternConstruct(int startIndex, int endIndex) {
		this.startIndex = startIndex;
		this.endIndex = endIndex;
	}

	/**
	 * This construct's own entry point -- see design.md's "Entry-point computation vs. matcher
	 * compilation" section. Lazily triggers {@link #buildEntryMap} on first call, independent of
	 * whether this construct has been (or is being) {@link #compile}d.
	 */
	final CodePointMap<Boolean> getEntryPointMap() {
		ensureEntryPointBuilt();
		return entryMap;
	}

	/** As {@link #getEntryPointMap()}, for the catch-all half of the entry point. */
	final @Nullable PatternConstruct getEntryElse() {
		ensureEntryPointBuilt();
		return entryElse;
	}

	private void ensureEntryPointBuilt() {
		if (entryPointState == ENTRY_POINT_CONSTRUCTED) {
			return;
		}
		if (entryPointState == ENTRY_POINT_CONSTRUCTING) {
			throw new EntryPointCycleException(startIndex);
		}
		entryPointState = ENTRY_POINT_CONSTRUCTING;
		buildEntryMap(next);
		entryPointState = ENTRY_POINT_CONSTRUCTED;
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
		ensureEntryPointBuilt();
		if (matcher == null) {
			// buildMatcher() constructs `new SomeMatcherConstruct(this, ...)`, whose constructor's
			// first act is `this.matcher = it` (see MatcherConstruct's class doc) -- so `matcher` is
			// set as a side effect of the call below, not by assigning its return value.
			buildMatcher();
		}
		return matcher;
	}

	/**
	 * Populates this construct's own {@link #entryMap}/{@link #entryElse}. Called at most once per
	 * construct, lazily, via {@link #ensureEntryPointBuilt} -- never call this directly. Must not
	 * trigger any other construct's {@link #compile}/{@link #buildMatcher} -- only entry-point
	 * getters (and, for a body part whose own entry point might need to see what follows it, a
	 * direct {@link #next} field assignment) -- see design.md's "Entry-point computation vs.
	 * matcher compilation" section for why.
	 */
	abstract void buildEntryMap(PatternConstruct next);

	abstract void buildMatcher();

	/**
	 * Converts {@code branch}'s own entry-point ranges (a {@code CodePointMap<Boolean>} -- see
	 * {@link #entryMap}'s doc for why the value there carries no information) into a {@code
	 * CodePointMap<PatternConstruct>} whose every entry maps to {@code branch} itself -- the actual
	 * identity {@link #entryMap} never bothered to store.
	 */
	static MutableCodePointMap<PatternConstruct> toValueMap(CodePointMap<Boolean> ranges, PatternConstruct branch) {
		MutableCodePointMap<PatternConstruct> result = new ArrayCodePointMap<>();
		result.ensureCapacity(ranges.entrySet().size());
		// ranges.entrySet()'s iteration order is ascending (CodePointMap's own ordering contract),
		// so this can use appendSorted's O(1)-amortized bulk path instead of put()'s general one.
		for (Entry<CodePointMap.Range, Boolean> e : ranges.entrySet()) {
			result.appendSorted(e.getKey().min, e.getKey().max, branch);
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
		// Hoisted out of the loop below: entrySet() is a fresh (if now lazy) view each call, so
		// calling it once per branchEntry here used to rebuild it branch.size() times over.
		Set<Entry<CodePointMap.Range, PatternConstruct>> mergedEntries = merged.entrySet();
		for (Entry<CodePointMap.Range, PatternConstruct> branchEntry : branch.entrySet()) {
			for (Entry<CodePointMap.Range, PatternConstruct> mergedEntry : mergedEntries) {
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

	static MutableCodePointMap<PatternConstruct> mergeEntryMapRejectingAmbiguity(
			String pattern, MutableCodePointMap<PatternConstruct> merged, PatternConstruct branch, String branchDescription) {
		MutableCodePointMap<PatternConstruct> branchMap = toValueMap(branch.getEntryPointMap(), branch);
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
		merged.putAll(branchMap);
		return merged;
	}

	/** Result of {@link #compileAndMergeCandidates}. */
	static final class MergedEntries {
		final MutableCodePointMap<PatternConstruct> ranges;
		// Whichever candidate claimed "matches any other character" (at most one is allowed to).
		final @Nullable PatternConstruct elseCandidate;

		MergedEntries(MutableCodePointMap<PatternConstruct> ranges, @Nullable PatternConstruct elseCandidate) {
			this.ranges = ranges;
			this.elseCandidate = elseCandidate;
		}

		@Nullable PatternConstruct entryElse() {
			return elseCandidate != null ? elseCandidate.getEntryElse() : null;
		}
	}

	/**
	 * Merges {@code candidates}' own entry points (via {@link #getEntryPointMap}/{@link
	 * #getEntryElse}, not {@link #compile} -- see design.md's "Entry-point computation vs. matcher
	 * compilation" section), rejecting the first ambiguity: two candidates whose entry ranges
	 * overlap, or two candidates that both accept "any other character". Used both for plain
	 * alternation ({@code candidates} = a union's branches) and for loop dispatch ({@code
	 * candidates} = a loop's body parts, plus its own {@code next} when the loop can match zero
	 * times), by way of {@link #compileAndMergeCandidates} and {@code
	 * QuantifiableConstruct.buildLoopEntryMap} respectively.
	 */
	static MergedEntries mergeEntryPoints(String pattern, List<PatternConstruct> candidates, String candidateNounPlural) {
		MutableCodePointMap<PatternConstruct> merged = new ArrayCodePointMap<>();
		PatternConstruct elseCandidate = null;
		for (int i = 0; i < candidates.size(); i++) {
			PatternConstruct candidate = candidates.get(i);
			if (candidate.getEntryElse() != null) {
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

	/**
	 * Compiles each of {@code candidates} against {@code compileTarget} (harmless/idempotent if a
	 * candidate is already compiled -- e.g. {@code compileTarget} itself, when it's included as one
	 * of the candidates) -- needed here (unlike {@link #mergeEntryPoints}) because this is used to
	 * build the actual dispatch graph, which needs every candidate's real {@code MatcherConstruct}
	 * -- then merges their entry points exactly as {@link #mergeEntryPoints} does.
	 */
	static MergedEntries compileAndMergeCandidates(
			String pattern, List<PatternConstruct> candidates, PatternConstruct compileTarget, String candidateNounPlural) {
		for (PatternConstruct candidate : candidates) {
			candidate.compile(compileTarget);
		}
		return mergeEntryPoints(pattern, candidates, candidateNounPlural);
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
		 * Computes this construct's own entry point for the quantified ({@code
		 * !isUnquantified()}) case -- called from {@code buildEntryMap}. {@code FIRST(body)} (the
		 * union of {@code body}'s own entry points, each body part's {@code next} pointed at {@code
		 * this} since continuing the loop always eventually routes back here), unioned with {@code
		 * next}'s own entry point when {@code min == 0} (skipping this construct entirely is valid).
		 * Deliberately reads ONLY entry points, never {@code compile()}s anything -- see design.md's
		 * "Entry-point computation vs. matcher compilation" section for why that's what lets a loop
		 * nested inside another loop's body resolve without forcing a cycle.
		 */
		void buildLoopEntryMap(List<PatternConstruct> body, PatternConstruct next) {
			for (PatternConstruct part : body) {
				part.next = this;
			}
			List<PatternConstruct> candidates = new ArrayList<>(body);
			if (min == 0) {
				candidates.add(next);
			}
			MergedEntries result = mergeEntryPoints(pattern, candidates, "loop part");
			for (Entry<CodePointMap.Range, PatternConstruct> e : result.ranges.entrySet()) {
				entryMap.put(e.getKey().min, e.getKey().max, true);
			}
			entryElse = result.entryElse() != null ? this : null;
		}

		/**
		 * Builds the actual loop matcher graph -- a plain {@code DispatchMatcherConstruct}, built via
		 * its loop-flavored constructor, see MatcherConstruct and design.md -- for the quantified
		 * case. Called from {@code buildMatcher()}, after {@code buildLoopEntryMap} (above) has
		 * already computed this construct's own entry point, since the self-registering constructor
		 * needs {@code this.matcher} set before {@code body}'s own {@code compile()} calls, which
		 * dispatch back to {@code this} once they finish matching.
		 */
		void buildLoopMatcher(List<PatternConstruct> body, PatternConstruct next, int captureConstructIndex) {
			new DispatchMatcherConstruct(this, body, next, captureConstructIndex);
		}
	}

	static final class QuantifiedUnion extends QuantifiableConstruct {
		final int parentFlags;

		int captureConstructIndex = 0;
		String captureName = "";
		final List<PatternConstruct> constructs = new ArrayList<>();
		boolean tempFlags = false;

		// The real (non-identity-rewritten) merged entry map/else this union's OWN dispatch is built
		// from, for the capturing-and-unquantified case -- see buildMatcher() below. Needed because
		// the inherited entryMap/entryElse fields are deliberately re-keyed onto `this` (like
		// Sequence's own fix, see its doc), for ancestors' identity checks -- but buildMatcher()'s
		// capturing branch builds its internal (non-self-registering) DispatchMatcherConstruct
		// *before* `this.matcher` gets set (that only happens once the wrapping
		// BeginCaptureMatcherConstruct is constructed afterward), so reading `this.entryMap`'s
		// rekeyed-to-`this` values there would resolve `.matcher` to null. rawEntryMap/rawEntryElse
		// keep the original, immediately-resolvable candidate identities for that one internal use.
		// Genuinely multi-valued (unlike entryMap -- see its doc), so this is a real
		// CodePointMap<PatternConstruct>, not <Boolean>; assigned wholesale from mergeEntryPoints's
		// own result (already exactly the map wanted here) rather than copied entry-by-entry.
		CodePointMap<PatternConstruct> rawEntryMap = new ArrayCodePointMap<>();
		@Nullable PatternConstruct rawEntryElse;

		// The unquantified-and-non-empty case's actual compile target (`next` itself, or a
		// CaptureEndMarker for a capturing group) -- computed once in buildEntryMap() (cheaply, no
		// compile() calls) and reused by buildMatcher() to actually compile the branches against it.
		// Kept as a field rather than recomputed, since buildMatcher() needs the SAME CaptureEndMarker
		// instance buildEntryMap() already used to compute rawEntryMap/rawEntryElse's identities.
		// @Nullable only because it has no meaningful value before buildEntryMap() runs -- by the
		// time buildMatcher() reads it (unguarded), compile()'s ensureEntryPointBuilt() guarantees
		// buildEntryMap() already has, in this (unquantified, non-empty-constructs) branch.
		@Nullable PatternConstruct compileTarget;

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
				buildLoopEntryMap(constructs, next);
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
				// Re-keyed onto `this` rather than aliased -- same reasoning as the main branch below.
				for (Entry<CodePointMap.Range, Boolean> e : next.getEntryPointMap().entrySet()) {
					entryMap.put(e.getKey().min, e.getKey().max, true);
				}
				entryElse = next.getEntryElse() != null ? this : null;
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
			compileTarget = next;
			if (isCapturing()) {
				compileTarget = new CaptureEndMarker(startIndex, captureConstructIndex, next);
				compileTarget.flags = flags;
			}
			for (PatternConstruct part : constructs) {
				part.next = compileTarget;
			}
			MergedEntries result = mergeEntryPoints(pattern, constructs, "union subpattern");
			rawEntryElse = result.entryElse();
			rawEntryMap = result.ranges; // exactly the map wanted here already -- no copy needed.
			// Re-keyed onto `this` rather than kept as whatever nested candidate built each range --
			// see Sequence.buildEntryMap's doc for why (same fix, same reason: a containing loop's
			// "e.getValue() != next" exit-vs-continue identity check must see THIS union, not one of
			// its branches' own leaves, whenever this union is passed as some ancestor's `next`).
			entryElse = rawEntryElse != null ? this : null;
			for (Entry<CodePointMap.Range, PatternConstruct> e : rawEntryMap.entrySet()) {
				entryMap.put(e.getKey().min, e.getKey().max, true);
			}
		}

		@Override
		void buildMatcher() {
			if (!isUnquantified()) {
				buildLoopMatcher(constructs, next, captureConstructIndex);
				return;
			}
			if (constructs.isEmpty()) {
				// Bare flags-only group -- see buildEntryMap()'s matching case. `next` is guaranteed
				// compiled by now (tail-to-front compile order), unlike when buildEntryMap() ran.
				matcher = next.matcher;
				return;
			}
			if (isCapturing()) {
				// compileTarget (a CaptureEndMarker) must itself be compiled before the branches below,
				// since building its own EndCaptureMatcherConstruct needs `next.matcher` -- guaranteed
				// available now (unlike when buildEntryMap() computed compileTarget's entry point).
				compileTarget.compile(next);
			}
			for (PatternConstruct part : constructs) {
				part.compile(compileTarget);
			}
			if (isCapturing()) {
				// Uses rawEntryMap/rawEntryElse, not the (rekeyed-to-`this`) entryMap/entryElse fields --
				// see rawEntryMap's doc: `this.matcher` isn't set yet at this point.
				MatcherConstruct dispatch = new DispatchMatcherConstruct(rawEntryMap, rawEntryElse, flags);
				new BeginCaptureMatcherConstruct(this, captureConstructIndex, dispatch);
			} else {
				// Also uses rawEntryMap/rawEntryElse, not entryMap/entryElse -- see rawEntryMap's doc:
				// this node itself becomes `this.matcher`, so populating from the rekeyed-to-`this`
				// entryMap would resolve every entry back to this very node (an infinite self-dispatch
				// loop) instead of to the actual branch matchers.
				new DispatchMatcherConstruct(this, rawEntryMap, rawEntryElse);
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
			for (Entry<CodePointMap.Range, Boolean> e : realNext.getEntryPointMap().entrySet()) {
				entryMap.put(e.getKey().min, e.getKey().max, true);
			}
			entryElse = realNext.getEntryElse() != null ? this : null;
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
			// A sequence's own entry point is exactly its first element's -- entering the sequence
			// means entering its first element, regardless of what the rest of the sequence looks
			// like. Wire every element's `next` pointer tail-to-front FIRST (a plain field
			// assignment, not a compile() call) so a nullable element can still fold in what follows
			// it when asked for its own entry point below -- but deliberately don't compile() (build
			// matchers for) anything here: that's buildMatcher()'s job, below. This split is what
			// lets a loop nested at the tail of this sequence ask an enclosing loop (this sequence's
			// own `next`, if it's a loop) for ITS entry point mid-construction, without forcing that
			// enclosing loop's own (still in-progress) matcher build to finish first -- see
			// design.md's "Entry-point computation vs. matcher compilation" section.
			PatternConstruct tail = next;
			for (int i = patterns.size() - 1; i >= 0; i--) {
				patterns.get(i).next = tail;
				tail = patterns.get(i);
			}
			// Re-key every range onto `this` instead of aliasing patterns.get(0).entryMap directly --
			// same fix as CaptureEndMarker (2026-09-06, see its own doc): aliasing leaves every entry's
			// VALUE as whatever nested leaf construct originally built the range, not this Sequence,
			// which silently breaks identity checks like a containing loop's "is this range the exit
			// path, i.e. does it lead to `next`" test whenever `next` is a Sequence. See
			// remaining_work.md's dated bug entry (a quantified loop immediately followed by a
			// composite construct, e.g. "(a)(b)*(z)", crashed at match time because of exactly this).
			for (Entry<CodePointMap.Range, Boolean> e : patterns.get(0).getEntryPointMap().entrySet()) {
				entryMap.put(e.getKey().min, e.getKey().max, true);
			}
			entryElse = patterns.get(0).getEntryElse() != null ? this : null;
		}

		@Override
		void buildMatcher() {
			// Compile tail-to-front: the last element's next is this sequence's own next, and each
			// earlier element's next is the element right after it (already compiled by the time we
			// get to it). A Sequence has no matching behavior of its own -- it's exactly whatever its
			// first element compiled to.
			PatternConstruct tail = next;
			for (int i = patterns.size() - 1; i >= 0; i--) {
				PatternConstruct part = patterns.get(i);
				if (part instanceof WordBoundaryConstruct && i > 0) {
					((WordBoundaryConstruct) part).priorCharSet = lastCharSet(patterns.get(i - 1));
				}
				part.compile(tail);
				tail = part;
			}
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
			entryMap.put(value.codePointAt(0), true);
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
			CodePointMap<Boolean> firstChars = firstCharSet(referencedGroup);
			if (firstChars == null) {
				// Possibly-empty (e.g. "(a*)\1") or otherwise not-statically-known referenced group --
				// fall back to the catch-all entry set rather than risk silently wrong zero-width
				// handling. See design.md's "Backreferences" section.
				entryElse = this;
				return;
			}
			for (Entry<CodePointMap.Range, Boolean> e : firstChars.entrySet()) {
				entryMap.put(e.getKey().min, e.getKey().max, true);
			}
		}

		@Override
		void buildMatcher() {
			new BackReferenceMatcherConstruct(this, captureConstructIndex);
		}
	}

	static final class ComplexCharacter
			extends PatternConstruct {
		MutableCodePointMap<Boolean> ranges = new ArrayCodePointMap<>();
		@Nullable PatternConstruct dotElse;

		ComplexCharacter(int startIndex, MutableCodePointMap<Boolean> ranges) {
			super(startIndex);
			this.ranges = ranges;
		}

		ComplexCharacter(int startIndex, int endIndex, MutableCodePointMap<Boolean> ranges) {
			super(startIndex, endIndex);
			this.ranges = ranges;
		}

		ComplexCharacter(int startIndex, int character) {
			super(startIndex);
			ranges.put(character, character + 1, true);
		}

		ComplexCharacter(int startIndex) {
			super(startIndex);
		}

		/**
		 * {@code ranges} itself -- kept as a method (rather than exposing the field directly to every
		 * caller) since this used to also clamp to the code point domain before {@link CodePointMap}
		 * existed: Guava {@code RangeSet#complement()} (negated classes via {@code [^...]}, {@code .},
		 * built-ins like {@code \D}/{@code \S}/{@code \W}) produced a mathematically unbounded
		 * result that could swallow {@code -1}, the sentinel {@code Matcher} uses for "no more input"
		 * (see {@code Matcher#peek}). {@code CodePointMap}'s else-value-based {@link
		 * CodePointMap#complement} is always finite over {@code [0, MAX_CODE_POINT]} by construction
		 * (see its own doc), so no clamping is needed here any more -- {@link
		 * MatcherConstruct#containsFolded} instead guards {@code -1} directly, since a
		 * else-valued {@code ranges} would otherwise report it a "member" via the fill.
		 */
		CodePointMap<Boolean> validRanges() {
			return ranges;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			for (Entry<CodePointMap.Range, Boolean> e : validRanges().entrySet()) {
				entryMap.put(e.getKey().min, e.getKey().max, true);
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
				buildLoopEntryMap(List.of(delegate), next);
				return;
			}
			// Unquantified: entry set is exactly the delegate's own ranges, regardless of what
			// follows -- no need for `delegate` to be compiled (matcher-built) yet to know this;
			// that happens in buildMatcher(), below.
			for (Entry<CodePointMap.Range, Boolean> e : delegate.validRanges().entrySet()) {
				entryMap.put(e.getKey().min, e.getKey().max, true);
			}
		}

		@Override
		void buildMatcher() {
			if (!isUnquantified()) {
				buildLoopMatcher(List.of(delegate), next, -1);
				return;
			}
			// Unquantified (i.e. exactly-once) case: this construct behaves exactly like its
			// delegate ComplexCharacter.
			delegate.compile(next);
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
		@Nullable CodePointMap<Boolean> priorCharSet;

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

		/** True if every code point in {@code a} is also in {@code b}. */
		private static boolean isSubsetOf(CodePointMap<Boolean> a, CodePointMap<Boolean> b) {
			for (Entry<CodePointMap.Range, Boolean> e : a.entrySet()) {
				if (!b.containsKeys(e.getKey().min, e.getKey().max)) {
					return false;
				}
			}
			return true;
		}

		/** True if no code point in {@code a} is also in {@code b}. */
		private static boolean isDisjointFrom(CodePointMap<Boolean> a, CodePointMap<Boolean> b) {
			for (Entry<CodePointMap.Range, Boolean> e : a.entrySet()) {
				if (!b.intersection(e.getKey().min, e.getKey().max).isEmpty()) {
					return false;
				}
			}
			return true;
		}

		private static Wordness classify(@Nullable CodePointMap<Boolean> set, CodePointMap<Boolean> wordSet) {
			if (set == null) {
				return Wordness.UNKNOWN;
			}
			// Computed directly as subset/disjoint checks against wordSet, rather than via
			// wordSet.complement() the way the old RangeSet#enclosesAll version did -- no need to
			// materialize a complement just to test disjointness (see CodePointMap#complement's doc:
			// it would still be correct here, just wasted work for a query this cheap already).
			if (isSubsetOf(set, wordSet)) {
				return Wordness.WORD;
			}
			if (isDisjointFrom(set, wordSet)) {
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
			CodePointMap<Boolean> wordSet = RegexCharacterClass.w.get(flags);
			Wordness prior = classify(priorCharSet, wordSet);
			// next's own entry-point map is already exactly a CodePointMap<Boolean> -- no separate
			// RangeSet needs building here any more.
			CodePointMap<Boolean> peekRanges = next.getEntryElse() == null ? next.getEntryPointMap() : null;
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
	static @Nullable CodePointMap<Boolean> lastCharSet(PatternConstruct pc) {
		if (pc instanceof LiteralString) {
			String value = ((LiteralString) pc).value;
			if (value.isEmpty()) {
				return null;
			}
			int cp = value.codePointBefore(value.length());
			return singletonCodePointMap(cp);
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
			MutableCodePointMap<Boolean> result = new ArrayCodePointMap<>();
			for (PatternConstruct branch : union.constructs) {
				CodePointMap<Boolean> branchSet = lastCharSet(branch);
				if (branchSet == null) {
					return null;
				}
				result.putAll(branchSet);
			}
			return result;
		}
		if (pc instanceof Sequence) {
			List<PatternConstruct> patterns = ((Sequence) pc).patterns;
			return patterns.isEmpty() ? null : lastCharSet(patterns.get(patterns.size() - 1));
		}
		return null;
	}

	private static CodePointMap<Boolean> singletonCodePointMap(int codePoint) {
		MutableCodePointMap<Boolean> result = new ArrayCodePointMap<>();
		result.put(codePoint, codePoint + 1, true);
		return result;
	}

	/**
	 * The set of code points that could be the FIRST one consumed if {@code pc} matches here, if
	 * that's statically known regardless of runtime input -- the mirror image of {@link
	 * #lastCharSet}, used by {@code BackReference}'s compile-time entry-set computation (see
	 * design.md's "Backreferences" section): a backreference's possible first characters are
	 * exactly the referenced group's possible first characters. Returns null ("not statically
	 * known") for anything that could match zero-width, same safe fallback as {@code lastCharSet}.
	 */
	static @Nullable CodePointMap<Boolean> firstCharSet(PatternConstruct pc) {
		if (pc instanceof LiteralString) {
			String value = ((LiteralString) pc).value;
			return value.isEmpty() ? null : singletonCodePointMap(value.codePointAt(0));
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
			MutableCodePointMap<Boolean> result = new ArrayCodePointMap<>();
			for (PatternConstruct branch : union.constructs) {
				CodePointMap<Boolean> branchSet = firstCharSet(branch);
				if (branchSet == null) {
					return null;
				}
				result.putAll(branchSet);
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

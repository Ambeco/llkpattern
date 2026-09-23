package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointSet.MutableCodePointSet;
import com.tbohne.llkpattern.MatcherConstruct.*;
import com.tbohne.llkpattern.NamedCharClass.*;

import java.util.Map.Entry;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

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

	// Set by a chain builder (see #buildFlattenedChain, QuantifiableConstruct#buildLoopMatcher) just
	// before calling compile() on this construct as one candidate among several -- read by
	// MatcherConstruct's owner-based constructor to become that node's own entrySet/failedEntry
	// (see MatcherConstruct's "Flattened dispatch" class doc). Null for the overwhelming majority
	// of constructs, which are never a chain candidate at all. Since MatcherConstruct construction
	// happens exactly once per construct (compile() memoizes on `matcher != null`), these must be
	// set BEFORE the first compile() call, never after.
	@Nullable CodePointSet dispatchEntrySet;
	@Nullable MatcherConstruct dispatchFailedEntry;

	// The construct that comes after this one -- i.e. what compile() was last called with.
	// Recorded so that, once this construct appears as a value in some ancestor's entryMap, that
	// ancestor's MatcherConstruct-building code can rely on `entryMapValue.matcher` already being
	// set (this construct's own compile() already ran, tail-to-front) rather than needing to
	// thread the continuation through again.
	@MonotonicNonNull PatternConstruct next;

	// A shared, never-mutated empty set -- the default for any construct (BoundaryConstruct,
	// WordBoundaryConstruct) whose buildEntryMap() only ever sets entryElse, never entryMap itself.
	// One shared instance rather than `new ArrayCodePointSet()` per construct instance, now that
	// entryMap is a plain (immutable-from-here) CodePointSet reference, not something built up via
	// per-construct mutation -- see entryMap's own doc below.
	private static final CodePointSet EMPTY_ENTRY_MAP = new ArrayCodePointSet();

	// The set of code points this construct claims as its own entry point, once it (and anything
	// it can trivially skip, e.g. an optional quantifier) has matched. Populated by buildEntryMap()
	// (lazily, via ensureEntryPointBuilt() -- see getEntryPointMap()/getEntryElse() below);
	// consumed while compiling a containing QuantifiedUnion/Sequence to detect ambiguous branches,
	// and to build the MatcherConstruct graph. Never read directly outside this construct's own
	// buildEntryMap() -- every other reader goes through the getters.
	//
	// Plain code-point-set membership (never a "code point -> owning construct" map) -- every
	// entryMap-populating call in every buildEntryMap() override below either aliases another
	// construct's own entryMap directly (a construct whose own entry point is exactly some other
	// construct's -- Sequence's first element, CaptureEndMarker's realNext, a bare-flags-only
	// union's next, a ComplexCharacter's own validRanges(), a BackReference's referenced group's
	// firstCharSet -- see each override's own comment) or inserts a code point with no further
	// payload, so there's never a PatternConstruct identity to lose. This is a plain (not Mutable)
	// CodePointSet specifically so aliasing is safe: nothing can mutate an aliased set out from
	// under whichever other construct also holds it. A genuinely multi-valued map DOES exist
	// transiently, inside mergeEntryPoints (where an entry's value is which distinct candidate owns
	// it, needed to run the ambiguity check with a useful "candidate #N" error message) -- but
	// mergeEntryPoints itself projects that down to a plain CodePointSet before ever handing
	// anything back (see its own doc), specifically so no caller can make the mistake a real
	// 2026-09-06 bug once did: exposing a PatternConstruct-valued map as an ancestor's entry map,
	// breaking downstream `==` identity checks like a loop's continue-vs-exit classification.
	CodePointSet entryMap = EMPTY_ENTRY_MAP;
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
	final CodePointSet getEntryPointMap() {
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
	 * Mirror of {@code getEntryElse() != null}, used by {@link #mergeEntryPoints} instead of {@link
	 * #getEntryElse()} directly. This matters because {@link #getEntryElse()} forces the SAME full,
	 * cached {@link #buildEntryMap} that {@link #getEntryPointMap()} does (they share one {@link
	 * #ensureEntryPointBuilt} call) -- calling it on every candidate up front would force every
	 * candidate's entryMap to materialize, even ones {@link #mergeEntryPoints} otherwise wouldn't
	 * need to (see {@link #getEntryPointMap()}'s own call in that method). A candidate that's a
	 * pure leaf (e.g. {@code LiteralString}) or a pure alias ({@code Sequence}, {@code
	 * CaptureEndMarker}) overrides this to answer straight from whatever it aliases instead --
	 * skipping materializing its own {@code entryMap} purely to answer this one question.
	 *
	 * <p>Default just pulls through the ordinary cached, cycle-guarded {@link #getEntryElse()} --
	 * correct for any construct, and REQUIRED (not just correct) for the "real consumers" that
	 * actually own ambiguity-checked ranges of their own ({@code ComplexCharacter}'s own ranges,
	 * and any {@code QuantifiedUnion}/{@code ComplexQuantifiedCharacter} that merges multiple
	 * candidates via {@code buildLoopEntryMap}): overriding those to answer without going through
	 * the cycle guard {@link #ensureEntryPointBuilt} provides would be wrong, since a quantified
	 * construct's own body can point its {@code next} right back at this same construct for a
	 * nullable loop (e.g. {@code (a?)+}) -- see {@code QuantifiableConstruct.buildLoopEntryMap}'s
	 * {@code part.next = this}. Only override this for a construct that either has no recursion at
	 * all (a true leaf) or delegates to exactly one other, structurally-fixed construct (never
	 * blindly through {@code next}, unless whatever `next` might resolve to is itself guaranteed to
	 * still be state-checked -- see {@code QuantifiedUnion}'s bare-flags-group override for the one
	 * case that does this safely).
	 */
	boolean claimsEntryElse() {
		return getEntryElse() != null;
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
	MatcherConstruct compile(PatternConstruct next) {
		if (matcher != null) {
			return matcher;
		}
		this.next = next;
		if (needsEntryPointBeforeMatcher()) {
			ensureEntryPointBuilt();
		}
		if (matcher == null) {
			// buildMatcher() constructs `new SomeMatcherConstruct(this, ...)`, whose constructor's
			// first act is `this.matcher = it` (see MatcherConstruct's class doc) -- so `matcher` is
			// set as a side effect of the call below, not by assigning its return value.
			buildMatcher();
			if (matcher == null) {
				throw new IllegalStateException(getClass().getSimpleName() + ".buildMatcher() did not construct a "
						+ "MatcherConstruct (every MatcherConstruct constructor self-registers on its owner's `matcher`; "
						+ "did you mean to construct one, or to override needsEntryPointBeforeMatcher()?)");
			}
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
	 * Whether {@link #compile} needs to run {@link #ensureEntryPointBuilt} before {@link
	 * #buildMatcher} -- {@code true} by default, since most {@code buildMatcher()} overrides read
	 * fields that only {@code buildEntryMap()} populates (e.g. {@code QuantifiedUnion}'s {@code
	 * compileTarget}/{@code rawEntryElse}). Override to {@code false} ONLY for
	 * a construct whose {@code buildMatcher()} reads nothing {@code buildEntryMap()} sets -- a true
	 * leaf like {@code LiteralString}/{@code ComplexCharacter}, whose matcher is built entirely
	 * from their own constructor-supplied data. This is what lets such a leaf, when reached only as
	 * a merge candidate (via {@link #claimsEntryElse}), skip materializing its own {@link #entryMap}
	 * entirely -- otherwise {@code compile()}'s own unconditional {@code ensureEntryPointBuilt()}
	 * call (needed for every OTHER construct) would force that allocation right back, defeating the
	 * whole point of answering without it. Safe even for a leaf
	 * that participates in the entry-point cycle guard's graph, because a leaf's {@code
	 * buildEntryMap()} never reads {@code next} at all -- leaving it at {@code
	 * ENTRY_POINT_NOT_STARTED} after {@code compile()} can't corrupt anything a later, genuine pull
	 * (e.g. {@code Sequence.buildEntryMap}'s {@code getEntryPointMap()} call) would need; it just
	 * defers the same computation to whenever (if ever) that pull actually happens.
	 */
	boolean needsEntryPointBeforeMatcher() {
		return true;
	}

	/**
	 * Result of {@link #mergeEntryPoints}. {@code ranges} is
	 * a plain {@code CodePointSet}, not {@code PatternConstruct}-valued -- {@link #mergeEntryPoints}
	 * itself projects its own transient, genuinely-multi-valued merge (needed only to run the
	 * ambiguity/conflict check with useful per-candidate error messages) down to this once, so no
	 * caller needs its own separate projection pass, and no caller can accidentally alias a
	 * PatternConstruct-valued map as an ancestor's {@code entryMap} (see that field's own doc for
	 * why that would be a real bug, not just a style concern).
	 */
	static final class MergedEntries {
		// Not MutableCodePointSet -- the candidates.size() == 1 fast path in mergeEntryPoints below
		// aliases that lone candidate's own (immutable-from-here) entryMap directly, with no
		// allocation of its own; only the real (>= 2 candidates) merge path actually builds a fresh
		// MutableCodePointSet to hand back here.
		final CodePointSet ranges;
		// Whichever candidate claimed "matches any other character" (at most one is allowed to).
		final @Nullable PatternConstruct elseCandidate;

		MergedEntries(CodePointSet ranges, @Nullable PatternConstruct elseCandidate) {
			this.ranges = ranges;
			this.elseCandidate = elseCandidate;
		}

		@Nullable PatternConstruct entryElse() {
			return elseCandidate != null ? elseCandidate.getEntryElse() : null;
		}
	}

	/**
	 * {@code candidates} with {@code extra} (or nothing, if {@code null}) logically appended as one
	 * more element, addressed by plain index arithmetic -- no wrapper object, no copy. {@link
	 * #checkDisjoint} sometimes needs a loop's body list plus its own {@code next} as one candidate
	 * list (via its own {@code extra} parameter); this is how it reads "index i of that logical
	 * list" without ever materializing it -- a real copy would have been an {@code arraycopy} this
	 * project's own on-device CPU sampling (Pixel 3a) flagged as real cost. {@link
	 * #mergeEntryPoints}'s own {@code extra} parameter doesn't go through this: it needs to treat
	 * {@code extra} specially anyway (the {@code candidates.isEmpty()} fast path), and never
	 * indexes into the combined list positionally the way this does.
	 */
	private static PatternConstruct candidateAt(
			List<PatternConstruct> candidates, @Nullable PatternConstruct extra, int index) {
		return index < candidates.size() ? candidates.get(index) : extra;
	}

	private static int candidateCount(List<PatternConstruct> candidates, @Nullable PatternConstruct extra) {
		return candidates.size() + (extra != null ? 1 : 0);
	}

	/**
	 * Merges {@code candidates}' own entry points (via {@link #getEntryPointMap}/{@link
	 * #getEntryElse}, not {@link #compile} -- see design.md's "Entry-point computation vs. matcher
	 * compilation" section), rejecting the first ambiguity: two candidates whose entry ranges
	 * overlap, or two candidates that both accept "any other character". Used for plain alternation
	 * ({@code candidates} = a union's branches, by way of {@code QuantifiedUnion.buildEntryMap}) and
	 * for a quantified construct's own entry point ({@code candidates} = a loop's body parts, plus
	 * its own {@code next} when the loop can match zero times, by way of {@code
	 * QuantifiableConstruct.buildLoopEntryMap}). See {@link #checkDisjoint} for the sibling case
	 * that needs the same ambiguity check but not the merged ranges themselves.
	 */
	/**
	 * Unions {@code candidates}' own entry points and picks out whichever one (at most one is
	 * allowed to) claims the any-other-character catch-all -- no ambiguity/overlap check here any
	 * more: that's now {@link #checkDisjoint}'s job, run separately against entry points alone
	 * (see {@link #buildFlattenedChain}/{@code QuantifiableConstruct#buildLoopMatcher}, its own
	 * call sites), not here against {@code PatternConstruct}s while just computing this
	 * construct's own entry point. This is what lets this method union plain {@code CodePointSet}s
	 * directly instead of tagging every candidate's ranges into a {@code CodePointMap<PatternConstruct>} purely to
	 * find a conflicting pair -- see {@link #checkDisjoint}'s own doc for why that map is gone.
	 */
	static MergedEntries mergeEntryPoints(String pattern, List<PatternConstruct> candidates, String candidateNounPlural) {
		return mergeEntryPoints(pattern, candidates, null, candidateNounPlural);
	}

	/**
	 * Same as {@link #mergeEntryPoints(String, List, String)}, plus one more candidate
	 * ({@code extra}, or {@code null} for none) merged in without the caller having to copy
	 * {@code candidates} into a new list just to append it -- {@code
	 * QuantifiableConstruct.buildLoopEntryMap}'s own reason for existing: {@code next} joins the
	 * merge only when {@code min == 0}, and was previously always copied into a fresh {@code
	 * ArrayList<>(body)} first, real work (an {@code arraycopy}) showing up in this project's own
	 * on-device CPU sampling (Pixel 3a) even though `next` isn't part of the merge at all in the
	 * far more common {@code min >= 1} case.
	 */
	static MergedEntries mergeEntryPoints(
			String pattern, List<PatternConstruct> candidates, @Nullable PatternConstruct extra,
			String candidateNounPlural) {
		if (extra == null && candidates.size() == 1) {
			// A lone candidate can't conflict with itself -- skip straight to aliasing its own
			// already-computed entry point, no union/allocation needed at all, same trick
			// buildLoopMatcher's bodyOnlyResult already uses for a single-element loop body. This is
			// the common case for a quantified single character/class (e.g. `a+`, `\d*`).
			PatternConstruct only = candidates.get(0);
			return new MergedEntries(only.getEntryPointMap(), only.claimsEntryElse() ? only : null);
		}
		if (extra != null && candidates.isEmpty()) {
			// Mirror image of the fast path above -- `extra` alone is exactly as uncontested as a
			// lone `candidates` element would be. Not reachable via buildLoopEntryMap (`body` is
			// always non-empty -- a loop always has a real body), but a real case in general, so
			// still handled rather than assumed away.
			return new MergedEntries(extra.getEntryPointMap(), extra.claimsEntryElse() ? extra : null);
		}
		MutableCodePointSet ranges = new ArrayCodePointSet();
		PatternConstruct elseCandidate = null;
		for (PatternConstruct candidate : candidates) {
			elseCandidate = mergeOneEntryPoint(pattern, candidate, elseCandidate, candidateNounPlural, ranges);
		}
		if (extra != null) {
			elseCandidate = mergeOneEntryPoint(pattern, extra, elseCandidate, candidateNounPlural, ranges);
		}
		return new MergedEntries(ranges, elseCandidate);
	}

	/** One candidate's own contribution to an in-progress merge (shared by {@link
	 *  #mergeEntryPoints}'s main-list loop and its {@code extra} candidate) -- unions its entry
	 *  point into {@code ranges} and returns the (possibly updated) else-candidate, throwing if
	 *  this candidate and an earlier one both claim the any-other-character catch-all. */
	private static @Nullable PatternConstruct mergeOneEntryPoint(
			String pattern, PatternConstruct candidate, @Nullable PatternConstruct elseCandidate,
			String candidateNounPlural, MutableCodePointSet ranges) {
		if (candidate.claimsEntryElse()) {
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
		ranges.addAll(candidate.getEntryPointMap());
		return elseCandidate;
	}

	/**
	 * Throws the first ambiguity found among {@code candidates}, checked in priority (list) order:
	 * two candidates whose entry ranges overlap. Entry sets already carry any CASE_INSENSITIVE
	 * folding (a class's at parse time, a literal's or backreference's via {@code
	 * MatcherConstruct#foldedEntrySet}; a named class is deliberately never folded, as in the JDK), so
	 * e.g. {@code (?i:[a-z]+)X} is rejected as ambiguous rather than resolved by chain priority; the
	 * sets are returned (index-aligned with {@code candidates} then {@code extra}) for reuse as
	 * dispatch gates. For each candidate (in order), checks it pairwise
	 * against every earlier candidate via the boolean-only, allocation-free {@link
	 * CodePointSet#intersects} -- no accumulated "claimed so far" union, and no {@code
	 * CodePointMap<PatternConstruct>} tagging every candidate's ranges with its own identity either,
	 * unlike the old {@code mergeEntryPointsRaw}/{@code CodePointMapBuilder} approach this replaces.
	 * The actual overlapping range is only ever computed (via real {@link CodePointSet#intersection})
	 * on the rare path where a conflict is confirmed, purely to name it in the exception -- the
	 * common (no-conflict) path never materializes an overlap set at all. Blames the later
	 * (lower-priority) candidate of a conflicting pair, same as before, since it's the one
	 * redundantly claiming characters an earlier, higher-priority candidate already owns; checking
	 * each candidate against earlier ones in order (rather than each against later ones) is what
	 * keeps that "first" the same first ambiguity the old accumulating version would have reported,
	 * when more than one pair conflicts.
	 *
	 * <p>Deliberately never checks a candidate against anything outside {@code candidates} itself --
	 * in particular, {@link #buildFlattenedChain}'s own {@code elseTarget} (this chain's catch-all
	 * fallback, already resolved to a single {@code MatcherConstruct} by the time this runs, by
	 * construction of {@link #mergeEntryPoints}'s own {@code elseCandidate} tracking above) is
	 * passed separately and is never one of {@code candidates} -- it's expected to overlap every
	 * other candidate (that's the whole point of a fallback bucket), so checking it here would
	 * reject every pattern that has one.
	 */
	private static CodePointSet[] checkDisjoint(
			String pattern, int flags, List<PatternConstruct> candidates, @Nullable PatternConstruct extra,
			String candidateNounPlural) {
		return checkDisjoint(pattern, flags, candidates, extra, null, candidateNounPlural);
	}

	/**
	 * As the four-{@code List}/{@code PatternConstruct} overload above, but lets the caller override
	 * {@code extra}'s own entry set with {@code extraEntrySet} (used only for the overlap comparison
	 * below -- {@code extra} itself is still what an error message blames) -- see {@link
	 * #skipZeroWidthEntrySet}'s own doc for why {@code QuantifiableConstruct.buildLoopMatcher} needs
	 * this and {@link #buildFlattenedChain} doesn't.
	 */
	private static CodePointSet[] checkDisjoint(
			String pattern, int flags, List<PatternConstruct> candidates, @Nullable PatternConstruct extra,
			@Nullable CodePointSet extraEntrySet, String candidateNounPlural) {
		int count = candidateCount(candidates, extra);
		CodePointSet[] sets = new CodePointSet[count];
		for (int j = 0; j < count; j++) {
			PatternConstruct candidate = candidateAt(candidates, extra, j);
			sets[j] = (candidate == extra && extraEntrySet != null) ? extraEntrySet : candidate.getEntryPointMap();
		}
		for (int j = 1; j < count; j++) {
			for (int i = 0; i < j; i++) {
				if (sets[j].intersects(sets[i])) {
					throwOverlapError(pattern, candidateAt(candidates, extra, j), j + 1, candidateNounPlural, sets[j], sets[i]);
				}
			}
		}
		return sets;
	}

	/**
	 * Only reached once {@link #checkDisjoint} has already confirmed {@code own} and {@code prior}
	 * overlap -- recomputes the actual overlapping range (an allocation {@link #checkDisjoint}'s own
	 * pairwise {@link CodePointSet#intersects} scan otherwise avoids entirely) purely to name it in
	 * the thrown exception.
	 */
	private static void throwOverlapError(String pattern, PatternConstruct candidate, int candidateNumber,
			String candidateNounPlural, CodePointSet own, CodePointSet prior) {
		own.forEachRange((min, max) -> {
			CodePointSet overlap = prior.intersection(min, max);
			if (!overlap.isEmpty()) {
				overlap.forEachRange((overlapMin, overlapMax) -> {
					throw PatternSyntaxException.throwWithReferences(
							pattern,
							candidate.startIndex,
							candidateNounPlural, " #" + candidateNumber,
							" starting at index ", candidate.startIndex,
							" accepts character(s) ",
							new PatternSyntaxException.CodePoint(overlapMin),
							"-",
							new PatternSyntaxException.CodePoint(overlapMax - 1),
							", but a prior part of the same construct already claims those, which is not allowed");
				});
			}
		});
	}

	/**
	 * Builds a flattened dispatch chain over {@code candidates} (never empty), tried in list order
	 * -- see {@code MatcherConstruct}'s own "Flattened dispatch" class doc for the shape this
	 * builds: each candidate's own compiled {@code MatcherConstruct} becomes a fork itself, via
	 * {@code dispatchEntrySet}/{@code dispatchFailedEntry} (set here, immediately before that
	 * candidate is compiled -- compiling memoizes on {@link #matcher}, so these MUST be set before
	 * a candidate's first {@link #compile}), rather than a separate {@code
	 * ForkingMatcherConstruct} node wrapping it. Compiled tail-to-front, so each candidate's
	 * {@code dispatchFailedEntry} can point at the next one's already-resolved matcher.
	 *
	 * <p>Ambiguity between candidates is checked exactly as before (via {@link #checkDisjoint}, on
	 * entry points alone, no compiling) -- this experiment changes how the matcher graph is built,
	 * not whether an ambiguous pattern is still rejected (that machinery is a separate, later
	 * experiment -- see remaining_work.md's "Entry-set-conflict-detection-without-allocation"
	 * section).
	 *
	 * <p>{@code elseTarget} is this chain's final fallback, or {@code null} for none, in which case
	 * the last candidate is left entirely ungated (no {@code dispatchEntrySet}/{@code
	 * dispatchFailedEntry} at all) -- its own compiled matcher already re-verifies membership
	 * (folded or not) as its own first action, same reasoning the old fork-chain design relied on.
	 *
	 * <p>When {@code owner} is non-null, the chain's head becomes {@code owner}'s own matcher (via
	 * {@link MatcherConstruct#aliasOrPassThrough}, so {@code owner}'s own dispatch fields --  set
	 * if {@code owner} is itself a candidate in some OUTER chain -- aren't silently dropped).
	 */
	static MatcherConstruct buildFlattenedChain(
			@Nullable PatternConstruct owner,
			int flags,
			String pattern,
			List<PatternConstruct> candidates,
			String candidateNounPlural,
			PatternConstruct compileTarget,
			@Nullable MatcherConstruct elseTarget) {
		CodePointSet[] gates = checkDisjoint(pattern, flags, candidates, null, candidateNounPlural);
		int count = candidates.size();
		MatcherConstruct tail = elseTarget;
		for (int i = count - 1; i >= 0; i--) {
			PatternConstruct candidate = candidates.get(i);
			boolean lastUngated = (i == count - 1 && elseTarget == null);
			candidate.dispatchEntrySet = lastUngated
					? null
					: gates[i];
			candidate.dispatchFailedEntry = lastUngated ? null : tail;
			tail = candidate.compile(compileTarget);
		}
		if (owner != null) {
			MatcherConstruct.aliasOrPassThrough(owner, tail);
		}
		return tail;
	}

	static abstract class QuantifiableConstruct extends PatternConstruct {
		final String pattern;
		int min = 1;
		int max = 1;
		int quantifiableIndex = -1;
		// Set by PatternParser#parseQuantifiable when a trailing '?' follows the quantifier itself
		// (e.g. "a+?"). Possessive '+' stays a no-op: this engine's no-backtrack greedy loop already
		// makes the greedy/possessive choice unobservable (nothing to backtrack into), so possessive
		// syntax is accepted purely for compatibility, not compiled differently.
		boolean reluctant = false;
		// Set by PatternParser#parseQuantifiable when a trailing '+' follows the quantifier itself
		// (e.g. "a++"). Unlike reluctant, this doesn't change buildLoopMatcher's own matcher graph --
		// see `reluctant`'s doc above -- but it DOES exempt the loop from the zero-width-assertion
		// ambiguity check buildLoopMatcher runs for plain greedy syntax (see that method's own doc):
		// java.util.regex's possessive quantifier never backtracks either, so this engine's
		// always-non-backtracking compilation already agrees with it, with nothing to reject.
		boolean possessive = false;

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
				entryElse = skipped.entryElse() != null ? this : null;
				return;
			}
			PatternConstruct target = loopBodyTarget(captureConstructIndex);
			for (PatternConstruct part : body) {
				part.next = target;
			}
			// `body` handed straight to mergeEntryPoints, with `next` merged in via its own `extra`
			// parameter instead of first being copied into a new ArrayList<>(body) just to append it
			// -- see that overload's own doc.
			MergedEntries result = mergeEntryPoints(pattern, body, min == 0 ? next : null, "loop part");
			entryMap = result.ranges; // already Boolean-valued -- see mergeEntryPoints' own doc.
			entryElse = result.entryElse() != null ? this : null;
		}

		@MonotonicNonNull LoopBackMarker loopBackMarker;
		@MonotonicNonNull PatternConstruct loopBodyTargetCache;

		/**
		 * The stable stand-in for "loop back to this construct's own entry point", used as every body
		 * part's {@code next} during entry-point computation ({@link #buildLoopEntryMap}) -- for a
		 * capturing loop, wrapped in a {@link CaptureEndMarker} first, since finishing one iteration
		 * must end the capture before looping back. Memoized as a field, rather than built fresh in
		 * each of {@link #buildLoopEntryMap}/{@link #buildLoopMatcher} separately, so that a NESTED
		 * capturing body part's own {@code CaptureEndMarker} -- constructed once, during entry-point
		 * computation, with this object as its {@code realNext} -- resolves against the exact same
		 * marker instance that {@link #buildLoopMatcher} later fills in with a real {@code .matcher}.
		 * Pointing body parts at {@code this} (the loop construct itself) directly, as this used to,
		 * meant a nested capturing group's {@code CaptureEndMarker} permanently captured {@code this}
		 * as {@code realNext} -- but {@code this.matcher} isn't set until the whole loop has finished
		 * compiling, well after that marker's own {@code buildMatcher()} reads it at match-build time,
		 * throwing a {@code NullPointerException} (a quantified group whose sole body is a capturing
		 * group, e.g. {@code ((x))*}) -- see remaining_work.md's now-fixed entry on this.
		 */
		private PatternConstruct loopBodyTarget(int captureConstructIndex) {
			if (loopBodyTargetCache == null) {
				loopBackMarker = new LoopBackMarker(startIndex, this);
				loopBackMarker.flags = flags;
				if (captureConstructIndex == -1) {
					loopBodyTargetCache = loopBackMarker;
				} else {
					PatternConstruct captureEnd = new CaptureEndMarker(startIndex, captureConstructIndex, loopBackMarker);
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
		 *   <li>{@link MatcherConstruct.LoopMatcherConstruct} (greedy) or {@link
		 *       MatcherConstruct.ReluctantLoopMatcherConstruct} (reluctant, only when {@link
		 *       MatcherConstruct#exitIsPureEnd} proves stopping early is safe -- see that class's own
		 *       doc), self-registered onto a {@link LoopBackMarker} BEFORE the body compiles against
		 *       it, breaking the construction-time cycle every loop body creates (the same
		 *       self-registration-first trick {@code MatcherConstruct}'s class doc describes). The
		 *       greedy node is reached only as a completed body iteration's own continuation, and its
		 *       whole job is enforcing {@code max}: dispatch back to the body's own head (another
		 *       attempt) if under it, or straight to {@code exitNode} (forcing a stop) if not. The
		 *       reluctant node is ALSO this construct's own externally-visible entry point (see its own
		 *       doc for why, and for the {@code min}/{@code max} "+1" shift that makes reusing one node
		 *       for both roles safe) -- neither tests code-point membership at all.
		 *   <li>{@link MatcherConstruct.LoopMatcherExit}, this loop's "stop iterating" node --
		 *       enforces {@code min} (the reluctant-safe case's own {@code min} pre-shifted the same
		 *       way, to stay consistent with the counter's shifted meaning) and, on success, dispatches
		 *       to {@code next}'s own matcher.
		 * </ol>
		 *
		 * <p>When {@code captureConstructIndex != -1} (the construct is <i>also</i> a capturing group,
		 * e.g. {@code (a)*}), the capture must re-fire every iteration -- last iteration wins, per real
		 * regex semantics -- and must fire identically whether this is the very first attempt or a
		 * re-check; since both now share the exact same body-chain head node, that's just a single
		 * shared {@link MatcherConstruct.BeginCaptureMatcherConstruct} wrapping it. Each body part is
		 * compiled against a {@link CaptureEndMarker} standing in for the {@link LoopBackMarker}
		 * above, so finishing one iteration records the captured substring before looping back rather
		 * than looping back directly.
		 */
		void buildLoopMatcher(List<PatternConstruct> body, PatternConstruct next, int captureConstructIndex) {
			if (max == 0) {
				// The body is never compiled: `X{0}` is a no-op, and its capture group (if any) stays unset.
				MatcherConstruct.aliasOrPassThrough(this, next.matcher);
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
			CodePointSet[] gates =
					checkDisjoint(pattern, flags, body, next,
							skipZeroWidthEntrySet(next, checkAssertionAmbiguity, bodyLastCharSet),
							"loop part");

			// Reuses the SAME LoopBackMarker (and, when capturing, the same wrapping CaptureEndMarker)
			// buildLoopEntryMap already handed to each body part as `next` -- see loopBodyTarget()'s own
			// doc for why identity, not just equal content, matters here: a nested capturing body part's
			// own CaptureEndMarker (built during entry-point computation) is permanently pointed at
			// whichever object loopBodyTarget() returned then, so this must be the exact same instance,
			// not a fresh one, or that nested marker's `realNext.matcher` would never get filled in.
			PatternConstruct bodyCompileTarget = loopBodyTarget(captureConstructIndex);
			LoopBackMarker marker = loopBackMarker;

			// Decided once, here, before either the exit node or the marker-owned node is built --
			// both need to already know which case they're in. See ReluctantLoopMatcherConstruct's own
			// doc for the "+1" shift this drives: reached both as the loop's fresh entry (zero
			// iterations done) and as the post-iteration continuation, so its own quantifiableCounts
			// slot counts VISITS, not completed iterations, and LoopMatcherExit's `min` check (reached
			// directly via the body's own failedEntry, bypassing the marker-owned node entirely) has to
			// agree on that same shifted meaning to stay consistent.
			@Nullable List<MatcherConstruct.ZeroWidthAssertionGuard> exitAssertionChain =
					reluctant ? MatcherConstruct.exitAssertionChain(next.matcher) : null;
			boolean reluctantSafe = exitAssertionChain != null;
			int shiftedMin = plusOneCapped(min);
			int shiftedMax = plusOneCapped(max);
			LoopMatcherExit exitNode = new LoopMatcherExit(
					flags, quantifiableIndex, reluctantSafe ? shiftedMin : min, min == 0, next.matcher);

			// A second, distinct marker from `marker` above -- `marker.matcher` is already claimed by
			// the marker-owned node itself; this one's `.matcher` is where that node's "continue"
			// successor (the body chain's own head, not resolvable until after the body compiles)
			// ends up, resolved via ordinary direct assignment further down, exactly like every other
			// forward reference in this file -- no bespoke mutable field needed on either
			// LoopMatcherConstruct or ReluctantLoopMatcherConstruct (see their own class docs).
			LoopContinueMarker continueMarker = new LoopContinueMarker(startIndex);
			continueMarker.flags = flags;
			MatcherConstruct loopNode = reluctantSafe
					? new MatcherConstruct.ReluctantLoopMatcherConstruct(
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
			// LoopBodyPartGateMarker} carrying the gate instead, with the capture wrapped INSIDE it
			// (compiled ungated, since gating already happened by the time it runs).
			MatcherConstruct bodyTail = exitNode;
			for (int i = body.size() - 1; i >= 0; i--) {
				PatternConstruct part = body.get(i);
				CodePointSet partEntrySet = gates[i];
				if (capturing) {
					MatcherConstruct rawPartMatcher = part.compile(bodyCompileTarget);
					LoopBodyPartGateMarker gateMarker = new LoopBodyPartGateMarker(startIndex);
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
			MatcherConstruct entryPoint = reluctantSafe ? loopNode : bodyHead;
			MatcherConstruct.aliasOrPassThrough(this, entryPoint);
		}

		/**
		 * {@code n + 1}, capped (not wrapped) at {@link Integer#MAX_VALUE} -- the "+1" shift {@link
		 * MatcherConstruct.ReluctantLoopMatcherConstruct} needs for both {@code min} and {@code max}
		 * (see its own doc). A literal {@code n + 1} would silently overflow to {@link
		 * Integer#MIN_VALUE} for an unbounded {@code max} (e.g. {@code a+?}/{@code a*?}, where {@code
		 * max == Integer.MAX_VALUE}), which would make every {@code count < shiftedMax} comparison
		 * false immediately and break every unbounded reluctant loop.
		 */
		private static int plusOneCapped(int n) {
			return n == Integer.MAX_VALUE ? Integer.MAX_VALUE : n + 1;
		}
	}

	/**
	 * A zero-width vehicle for a single capturing loop body part's own entry gating -- see
	 * {@code QuantifiableConstruct.buildLoopMatcher}'s own doc for why the gate has to live here,
	 * one level above the {@code BeginCaptureMatcherConstruct} it owns, rather than on the body
	 * part itself (which is compiled ungated and wrapped INSIDE the capture instead).
	 */
	static final class LoopBodyPartGateMarker extends PatternConstruct {
		LoopBodyPartGateMarker(int startIndex) {
			super(startIndex);
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			throw new AssertionError("LoopBodyPartGateMarker's entry point is never queried");
		}

		@Override
		void buildMatcher() {
			throw new AssertionError("LoopBodyPartGateMarker's own matcher is built directly, not via buildMatcher()");
		}
	}

	/**
	 * A zero-width marker standing in for a {@code MatcherConstruct.LoopMatcherConstruct} as a
	 * loop body's own compile target, so that node can be self-registered onto this marker BEFORE
	 * the body compiles against it (see {@code QuantifiableConstruct.buildLoopMatcher}'s doc for why
	 * that ordering matters). Its own entry point IS queried, though -- a nullable construct nested
	 * inside the loop body (e.g. the {@code (a)?} in {@code (a)?+}) needs to look past itself at
	 * "what comes after me" while computing its OWN entry point (see {@code Sequence.buildEntryMap}'s
	 * {@code getEntryPointMap()} call for the general pattern), and what "comes after" a loop body is
	 * -- semantically -- the loop itself: looping back around re-enters exactly the same entry point
	 * {@code owner} already advertises externally. Delegating to {@code owner}'s own (by this point
	 * already-computed and cached, since {@code compile()} only reaches {@code buildLoopMatcher} after
	 * {@code owner}'s {@link #ensureEntryPointBuilt}) entry point is exactly right, and cheap.
	 */
	static final class LoopBackMarker extends PatternConstruct {
		final QuantifiableConstruct owner;

		LoopBackMarker(int startIndex, QuantifiableConstruct owner) {
			super(startIndex);
			this.owner = owner;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			entryMap = owner.getEntryPointMap();
			entryElse = owner.getEntryElse() != null ? this : null;
		}

		@Override
		void buildMatcher() {
			throw new AssertionError("LoopBackMarker's own matcher is built directly, not via buildMatcher()");
		}
	}

	/**
	 * A second zero-width marker used by {@code QuantifiableConstruct.buildLoopMatcher}, distinct
	 * from {@link LoopBackMarker} (whose own {@code .matcher} is already claimed by the {@code
	 * LoopMatcherConstruct} instance itself): this one's {@code .matcher} is where that same
	 * instance's "continue" successor -- the body-part-selection chain, not resolvable until after
	 * the loop body has compiled -- ends up, via an ordinary direct assignment once it's known. This
	 * reuses {@code PatternConstruct.matcher}'s own already-mutable-once-and-only-once nature as the
	 * one place a forward reference is allowed to resolve later, rather than adding any equivalent
	 * mutable field to {@code MatcherConstruct} itself (see {@code LoopMatcherConstruct}'s own doc).
	 * Never has its {@code buildEntryMap}/{@code buildMatcher} invoked -- nothing ever calls {@code
	 * compile()} on it -- so both just assert if ever reached.
	 */
	static final class LoopContinueMarker extends PatternConstruct {
		LoopContinueMarker(int startIndex) {
			super(startIndex);
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			throw new AssertionError("LoopContinueMarker's entry point is never queried");
		}

		@Override
		void buildMatcher() {
			throw new AssertionError("LoopContinueMarker's own matcher is assigned directly, not via buildMatcher()");
		}
	}

	static final class QuantifiedUnion extends QuantifiableConstruct {
		final int parentFlags;

		int captureConstructIndex = 0;
		String captureName = "";
		final List<PatternConstruct> constructs = new ArrayList<>();
		boolean tempFlags = false;

		// The real (non-identity-rewritten) catch-all candidate this union's OWN fork chain falls
		// back to in buildMatcher() -- see that method below. Needed because the inherited
		// entryElse field is deliberately re-keyed onto `this` (like Sequence's own fix, see its
		// doc), for ancestors' identity checks -- but buildMatcher() reads the real candidate
		// identity, not `this`, to resolve the actual MatcherConstruct target the fallback should
		// dispatch to. (buildMatcher() otherwise builds its fork chain by walking `constructs`
		// directly -- see mergeEntryPoints' own doc for why nothing here needs a
		// PatternConstruct-valued entry map of its own any more.)
		@Nullable PatternConstruct rawEntryElse;

		// The unquantified-and-non-empty case's actual compile target (`next` itself, or a
		// CaptureEndMarker for a capturing group) -- computed once in buildEntryMap() (cheaply, no
		// compile() calls) and reused by buildMatcher() to actually compile the branches against it.
		// Kept as a field rather than recomputed, since buildMatcher() needs the SAME CaptureEndMarker
		// instance buildEntryMap() already used to compute rawEntryElse's identity.
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
		boolean claimsEntryElse() {
			if (isUnquantified() && constructs.isEmpty()) {
				// Bare flags-only group ("(?i)", no body) -- same aliasing as buildEntryMap: passes
				// straight through to `next` (this union contributes nothing of its own). Safe even
				// though `next` could resolve back to an ancestor loop still under construction (see
				// buildLoopEntryMap's `part.next = this`) -- whatever `next` turns out to be, if it's
				// itself a QuantifiableConstruct it keeps the state-checked default below, so the
				// cycle is still caught there, just one level further down.
				return next.claimsEntryElse();
			}
			return super.claimsEntryElse();
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
				// empty body via throwEmptySequence before a QuantifiedUnion with zero constructs can
				// ever exist. Previously this fell through to compileAndMergeCandidates() with an
				// empty candidate list, producing an empty entryMap/entryElse -- i.e. a
				// fork chain that matches nothing at all, silently breaking the
				// surrounding sequence ("(?s)abx" stopped matching "abx"). A bare flags group is
				// zero-width and always succeeds -- its only job was toggling `flags` for
				// PatternParser, already done by the caller -- so just pass through to `next` exactly
				// as an empty Sequence element would, instead of compiling as its own dispatch node.
				// Aliased directly -- entryMap's values are always Boolean `true` regardless of which
				// construct built it (see entryMap's own doc), so there's no PatternConstruct identity
				// to lose by sharing next's own map instead of copying its entries.
				entryMap = next.getEntryPointMap();
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
			// Re-keyed onto `this` rather than kept as whatever nested candidate built each range --
			// see Sequence.buildEntryMap's doc for why (same fix, same reason: a containing loop's
			// "e.getValue() != next" exit-vs-continue identity check must see THIS union, not one of
			// its branches' own leaves, whenever this union is passed as some ancestor's `next`).
			entryElse = rawEntryElse != null ? this : null;
			// Safe to alias directly (unlike entryElse just above): result.ranges is already
			// Boolean-valued -- see mergeEntryPoints' own doc -- so there's no PatternConstruct
			// identity to lose by sharing it as-is instead of re-keying/copying.
			entryMap = result.ranges;
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
				MatcherConstruct.aliasOrPassThrough(this, next.matcher);
				return;
			}
			if (isCapturing()) {
				// compileTarget (a CaptureEndMarker) must itself be compiled before the branches below,
				// since building its own EndCaptureMatcherConstruct needs `next.matcher` -- guaranteed
				// available now (unlike when buildEntryMap() computed compileTarget's entry point).
				compileTarget.compile(next);
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
			// ALSO claims real (non-empty) explicit ranges of its own -- rare in practice (its own explicit
			// ranges, if any, were already required to be disjoint from every sibling's by buildEntryMap's
			// own mergeEntryPoints call, so nothing here goes unvalidated) and not exercised by this
			// project's own test suite; flagged in remaining_work.md if it ever needs revisiting.
			MatcherConstruct elseTarget = rawEntryElse != null ? rawEntryElse.compile(compileTarget) : null;
			List<PatternConstruct> chainCandidates = rawEntryElse == null
					? constructs
					: constructs.stream().filter(c -> c != rawEntryElse).collect(java.util.stream.Collectors.toList());
			if (isCapturing()) {
				MatcherConstruct dispatch = buildFlattenedChain(null, flags, pattern, chainCandidates, "union subpattern", compileTarget, elseTarget);
				new BeginCaptureMatcherConstruct(this, captureConstructIndex, dispatch);
			} else {
				buildFlattenedChain(this, flags, pattern, chainCandidates, "union subpattern", compileTarget, elseTarget);
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
		boolean claimsEntryElse() {
			// realNext is a fixed field (unlike `next`, never reassigned to point back at some
			// ancestor mid-construction), so delegating straight through can't participate in the
			// one cycle this engine actually has (a nullable loop body) -- safe to bypass this
			// marker's own cycle guard entirely, same reasoning as its buildEntryMap override.
			return realNext.claimsEntryElse();
		}

		@Override
		boolean needsEntryPointBeforeMatcher() {
			// buildMatcher() below reads only realNext.matcher -- nothing buildEntryMap() sets.
			return false;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			// realNext is already compiled by the time any of this marker's callers need it -- it's
			// the capturing group's own `next`, which (like any `next`) was compiled before the group
			// itself, tail-to-front.
			//
			// Bug fix (2026-09-06): this used to just alias `entryMap = realNext.entryMap` directly
			// -- but that leaked every entry's VALUE as realNext itself (whatever realNext.buildEntryMap
			// put there -- entryMap was PatternConstruct-valued at the time), not this marker. That
			// silently broke identity checks like fork chain's loop-flavored
			// constructor's `e.getValue() == next` (used to tell "the loop is exiting toward `next`"
			// from "the loop is continuing") whenever THIS marker was passed in as that `next` -- i.e.
			// any non-quantified capturing group whose content contains its own internal loop, e.g.
			// "([a-z]+)!": the exit character got misclassified as "continue the loop, dispatch
			// straight to realNext.matcher", bypassing this marker's own EndCaptureMatcherConstruct
			// entirely, so the capture's `result` was set on entry but never finalized (group(n)
			// returned null even though the whole pattern matched). Found via GroupSyntaxTest.
			//
			// entryMap has since been migrated to Boolean-only values (see its own doc) specifically
			// because that value "carries zero information" -- so aliasing is safe again now, and
			// re-keying (copying) is back to being pure wasted work: every consumer of THIS marker's
			// entryMap only ever asks "which code points are in it", never anything realNext-specific,
			// so sharing realNext's own (also always-Boolean-`true`) map changes nothing observable.
			// Any identity check this bug was about reads a construct's own PatternConstruct-valued
			// candidate list (e.g. `constructs`/`rawEntryElse`) or mergeEntryPoints' own transient
			// merge (used only to run its ambiguity check), never this plain, Boolean-only entryMap.
			entryMap = realNext.getEntryPointMap();
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

		/**
		 * Wires every element's {@code next} pointer tail-to-front (a plain field assignment, not a
		 * {@code compile()} call) so a nullable element can still fold in what follows it when asked
		 * for its own entry point -- shared by {@link #buildEntryMap} and {@link #claimsEntryElse},
		 * since either one might run first (or, harmlessly, both -- this is idempotent). See
		 * design.md's "Entry-point computation vs. matcher compilation" section for why the split
		 * from compiling matters.
		 */
		private void wireElementNextPointers() {
			PatternConstruct tail = next;
			for (int i = patterns.size() - 1; i >= 0; i--) {
				patterns.get(i).next = tail;
				tail = patterns.get(i);
			}
		}

		@Override
		boolean claimsEntryElse() {
			// Same aliasing as buildEntryMap below: a sequence's own entry point is exactly its
			// first element's. patterns.get(0) is a fixed field (never reassigned the way `next`
			// is), so delegating straight through can't itself introduce a cycle -- but its OWN
			// entry-point computation still depends on the tail-to-front wiring below having run.
			wireElementNextPointers();
			return patterns.get(0).claimsEntryElse();
		}

		@Override
		boolean needsEntryPointBeforeMatcher() {
			// buildMatcher() below does its own tail-to-front `next` wiring independently (via each
			// part.compile(tail) call), and never reads entryMap/entryElse -- so, unlike buildEntryMap
			// above (whose wiring/entryMap-caching exists purely to answer an ANCESTOR's pull), this
			// Sequence's own matcher build needs nothing buildEntryMap() would have computed. This is
			// what lets a leaf branch reached only through a Sequence (e.g. a plain "a" union branch,
			// always parsed as a one-element Sequence) skip its own entryMap allocation too --
			// otherwise this Sequence's own compile() would force the pull right back regardless of
			// what the leaf itself does.
			return false;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			// A sequence's own entry point is exactly its first element's -- entering the sequence
			// means entering its first element, regardless of what the rest of the sequence looks
			// like. Wire every element's `next` pointer tail-to-front FIRST -- but deliberately don't
			// compile() (build matchers for) anything here: that's buildMatcher()'s job, below. This
			// split is what lets a loop nested at the tail of this sequence ask an enclosing loop
			// (this sequence's own `next`, if it's a loop) for ITS entry point mid-construction,
			// without forcing that enclosing loop's own (still in-progress) matcher build to finish
			// first -- see design.md's "Entry-point computation vs. matcher compilation" section.
			wireElementNextPointers();
			// Aliased directly, not re-keyed -- unlike `entryElse` (a genuinely PatternConstruct-valued
			// field, where re-keying onto `this` is load-bearing -- see QuantifiedUnion's own doc for
			// the 2026-09-06 bug that motivated it), entryMap's values are always Boolean
			// `true` regardless of which construct built it (see entryMap's own doc), so this
			// Sequence's own entry point and its first element's are the exact same map, both in
			// content AND in every consumer's eyes -- there's no identity to lose by sharing the
			// object instead of copying its entries.
			entryMap = patterns.get(0).getEntryPointMap();
			entryElse = patterns.get(0).getEntryElse() != null ? this : null;
		}

		@Override
		void buildMatcher() {
			// A Sequence has no matching behavior of its own -- it's exactly whatever its first
			// element compiled to, so any dispatch gating of our own (if this sequence is itself a
			// chain candidate) belongs on that first element's own node instead; safe to propagate
			// directly (no aliasOrPassThrough wrapper needed) since `patterns.get(0)` is exclusively
			// owned by this Sequence and hasn't been compiled by anyone else yet.
			patterns.get(0).dispatchEntrySet = dispatchEntrySet;
			patterns.get(0).dispatchFailedEntry = dispatchFailedEntry;
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
			matcher = patterns.get(0).matcher;
		}
	}

	static final class LiteralString extends PatternConstruct {
		// A CharSequence, not a String: for a literal run PatternParser could decode verbatim from
		// the pattern text (no escapes, no COMMENTS-mode gaps), it's a zero-copy
		// java.nio.CharBuffer view of `pattern` rather than a materialized copy -- see
		// PatternParser.parseUnion's own doc for why (java.lang.String.subSequence/substring both
		// copy; CharBuffer.wrap doesn't).
		final CharSequence value;

		LiteralString(int startIndex, int endIndex, CharSequence value) {
			super(startIndex, endIndex);
			this.value = value;
		}

		@Override
		boolean claimsEntryElse() {
			return false; // never sets entryElse -- see buildEntryMap.
		}

		@Override
		boolean needsEntryPointBeforeMatcher() {
			// buildMatcher() below reads nothing buildEntryMap() sets -- see the base class doc.
			return false;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			// A true leaf -- nothing to alias from -- so this is still a genuinely new (if tiny,
			// single-entry) set, built via a local mutable variable since entryMap itself is a plain
			// (non-Mutable) CodePointSet reference now -- see its own doc.
			MutableCodePointSet set = new ArrayCodePointSet();
			set.add(Character.codePointAt(value, 0));
			// A literal is the one leaf whose set isn't already folded (a class's is, at parse time;
			// a named class is never folded), so it is folded here rather than in checkDisjoint.
			entryMap = MatcherConstruct.foldedEntrySet(set, flags);
		}

		@Override
		void buildMatcher() {
			// value.toString() here, not value directly: LiteralMatcherConstruct wants a real String
			// (String#regionMatches is a JIT intrinsic -- real vectorized comparison -- and
			// String#charAt/length are direct field/array reads; a CharBuffer's own versions of
			// those are neither, measurably so per this project's own Android CPU sampling once
			// tried -- see LiteralMatcherConstruct.value's own doc). This runs once per compile
			// (same as buildMatcher() itself), not once per match attempt, so it's the same
			// allocation this construct's value would have cost pre-CharBuffer if `value` is a
			// CharBuffer view here (the "pure" case -- see parseUnion's own doc); if `value` is
			// already a String (the "impure" case, escapes/COMMENTS-gaps), toString() is a free
			// no-op (String#toString() returns `this`).
			new LiteralMatcherConstruct(this, value.toString());
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
			CodePointSet firstChars = firstCharSet(referencedGroup);
			if (firstChars == null) {
				// Possibly-empty (e.g. "(a*)\1") or otherwise not-statically-known referenced group --
				// fall back to the catch-all entry set rather than risk silently wrong zero-width
				// handling. See design.md's "Backreferences" section.
				entryElse = this;
				return;
			}
			// Aliased directly -- firstCharSet() already returns a plain CodePointSet (often itself an
			// alias, e.g. straight through to a ComplexCharacter's own validRanges()), so there's no
			// identity to lose by sharing it instead of copying its entries.
			// The backreference itself compares case-insensitively (codePointsMatch), whatever the
			// referenced group's own flags were.
			entryMap = MatcherConstruct.foldedEntrySet(firstChars, flags);
		}

		@Override
		void buildMatcher() {
			new BackReferenceMatcherConstruct(this, captureConstructIndex);
		}
	}

	static final class ComplexCharacter
			extends PatternConstruct {
		// Effectively immutable once a ComplexCharacter exists: every constructor below sets this
		// exactly once, from a set PatternParser finished building beforehand (see
		// PatternParser#parseComplexCharacter's own local `ranges` accumulator) -- so it's typed as
		// the plain (non-Mutable) CodePointSet here, and can be assigned directly from a
		// NamedCharClass/RegexCharacterClass static constant with no defensive copy, since nothing
		// past construction ever mutates it.
		final CodePointSet ranges;
		@Nullable PatternConstruct dotElse;

		ComplexCharacter(int startIndex, CodePointSet ranges) {
			super(startIndex);
			this.ranges = ranges;
		}

		ComplexCharacter(int startIndex, int endIndex, CodePointSet ranges) {
			super(startIndex, endIndex);
			this.ranges = ranges;
		}

		ComplexCharacter(int startIndex, int character) {
			super(startIndex);
			MutableCodePointSet single = new ArrayCodePointSet();
			single.add(character, character + 1);
			this.ranges = single;
		}

		/**
		 * {@code ranges} itself -- kept as a method (rather than exposing the field directly to every
		 * caller) since this used to also clamp to the code point domain before {@link CodePointMap}
		 * existed: Guava {@code RangeSet#complement()} (negated classes via {@code [^...]}, {@code .},
		 * built-ins like {@code \D}/{@code \S}/{@code \W}) produced a mathematically unbounded
		 * result that could swallow {@code -1}, the sentinel {@code Matcher} uses for "no more input"
		 * (see {@code Matcher#peek}). {@link CodePointSet}'s {@link CodePointSet#complement} is
		 * always finite over {@code [0, MAX_CODE_POINT]} by construction (see its own doc), so no
		 * clamping is needed here any more -- {@link MatcherConstruct.SingleCharMatcherConstruct} instead guards
		 * {@code -1} directly, since an inverted {@code ranges} would otherwise report it a "member"
		 * via the fill.
		 */
		CodePointSet validRanges() {
			return ranges;
		}

		@Override
		boolean claimsEntryElse() {
			return dotElse != null; // mirrors buildEntryMap's `entryElse = dotElse` exactly.
		}

		@Override
		boolean needsEntryPointBeforeMatcher() {
			// buildMatcher() below (new SingleCharMatcherConstruct(this)) reads `ranges` directly off
			// this instance, not entryMap -- see the base class doc.
			return false;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			// Aliased directly: a character class's own entry point IS exactly its own valid ranges,
			// not a separate copy of them -- entryMap and ranges/validRanges() were always meant to
			// hold identical content, so there's nothing to gain from keeping them as two objects.
			entryMap = validRanges();
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
		boolean claimsEntryElse() {
			if (!isUnquantified()) {
				// Real dispatch/ambiguity-checked case -- must go through the ordinary cycle-guarded
				// path (this construct's own `next` might loop back here, e.g. a nullable body like
				// `[ab]{0,2}` -- see buildLoopEntryMap).
				return super.claimsEntryElse();
			}
			// Unquantified: the unquantified case's buildEntryMap never sets entryElse.
			return false;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			if (!isUnquantified()) {
				buildLoopEntryMap(List.of(delegate), next, -1);
				return;
			}
			// Unquantified: entry set is exactly the delegate's own ranges, regardless of what
			// follows -- no need for `delegate` to be compiled (matcher-built) yet to know this;
			// that happens in buildMatcher(), below. Aliased directly, same reasoning as
			// ComplexCharacter.buildEntryMap.
			entryMap = delegate.validRanges();
		}

		@Override
		void buildMatcher() {
			if (!isUnquantified()) {
				buildLoopMatcher(List.of(delegate), next, -1);
				return;
			}
			// Unquantified (i.e. exactly-once) case: this construct behaves exactly like its
			// delegate ComplexCharacter -- propagate our own dispatch fields (if we're ourselves a
			// chain candidate) onto `delegate` BEFORE compiling it, so its own compiled node ends up
			// with the right gating; safe because `delegate` is exclusively owned by this construct
			// (created together, never independently compiled from anywhere else).
			delegate.dispatchEntrySet = dispatchEntrySet;
			delegate.dispatchFailedEntry = dispatchFailedEntry;
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

		/**
		 * Loop-ambiguity helper only -- see {@code PatternConstruct#skipZeroWidthEntrySet}'s
		 * {@code checkAssertions} doc, and only ever consulted there under {@code MULTILINE} (a
		 * non-MULTILINE ^/$ only ever holds at the true input edges, never at an interior loop-exit
		 * position, so the caller never needs this otherwise). {@code $} holds whenever the PEEK
		 * character itself is a line terminator, regardless of what the loop body's last-consumed
		 * character was, so its admitted set is exactly the terminator-starting code points,
		 * unconditionally. {@code ^} holds whenever the PRIOR character was a line terminator,
		 * regardless of peek, so its admitted set is "any code point" whenever the body could
		 * plausibly have just consumed one, and empty (no interior exit possible via ^) otherwise;
		 * returns {@code null} ("not statically known") when {@code bodyLastCharSet} itself is
		 * {@code null}, same safe fallback {@code WordBoundaryConstruct}'s own version uses.
		 */
		static @Nullable CodePointSet admittedInteriorExitPeekSet(
				boolean isLineBegin, @Nullable CodePointSet bodyLastCharSet, int flags) {
			CodePointSet terminatorStarts = lineTerminatorStartCodePoints(flags);
			if (!isLineBegin) {
				return terminatorStarts;
			}
			if (bodyLastCharSet == null) {
				return null;
			}
			return bodyLastCharSet.intersects(terminatorStarts) ? universalCodePointSet() : null;
		}

		/**
		 * The code points that can BEGIN a line terminator (matching {@code MatcherConstruct}'s own
		 * runtime {@code lineTerminatorLengthAt}/{@code lineTerminatorLengthBefore} scans, honoring
		 * {@code UNIX_LINES}) -- sufficient for a single-code-point admitted-peek-set check, since
		 * every terminator this engine recognizes ({@code \n}, {@code \r}, {@code "\r\n"} as one
		 * unit, {@code \u0085}, {@code  }, {@code  }) is uniquely identified by its own
		 * first code point.
		 */
		private static CodePointSet lineTerminatorStartCodePoints(int flags) {
			MutableCodePointSet result = new ArrayCodePointSet();
			result.add('\n', '\n' + 1);
			result.add('\r', '\r' + 1);
			if ((flags & Ll1Pattern.UNIX_LINES) == 0) {
				result.add(0x0085, 0x0086);
				result.add(0x2028, 0x202A);
			}
			return result;
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
		@Nullable CodePointSet priorCharSet;

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
		private static boolean isSubsetOf(CodePointSet a, CodePointSet b) {
			// first(), not entrySet(), so a violation short-circuits instead of scanning the rest of
			// `a` regardless -- see CodePointSet#first's own doc.
			return !a.first((min, max) -> !b.containsAll(min, max));
		}

		/** True if no code point in {@code a} is also in {@code b}. */
		private static boolean isDisjointFrom(CodePointSet a, CodePointSet b) {
			return !a.first((min, max) -> !b.intersection(min, max).isEmpty());
		}

		private static Wordness classify(@Nullable CodePointSet set, CodePointSet wordSet) {
			if (set == null) {
				return Wordness.UNKNOWN;
			}
			// Computed directly as subset/disjoint checks against wordSet, rather than via
			// wordSet.complement() the way the old RangeSet#enclosesAll version did -- no need to
			// materialize a complement just to test disjointness; it would still be correct here,
			// just wasted work for a query this cheap already.
			if (isSubsetOf(set, wordSet)) {
				return Wordness.WORD;
			}
			if (isDisjointFrom(set, wordSet)) {
				return Wordness.NON_WORD;
			}
			return Wordness.UNKNOWN;
		}

		/**
		 * Loop-ambiguity helper only -- see {@code PatternConstruct#skipZeroWidthEntrySet}'s
		 * {@code checkAssertions} doc. The set of peek code points for which a \b/\B sitting right
		 * after a loop body could hold, given that the body's own last-consumed character is
		 * somewhere in {@code bodyLastCharSet} -- i.e. the code points an interior exit through this
		 * assertion could be ambiguous with the loop simply continuing on. Returns {@code null}
		 * ("not statically known", same safe fallback as {@code lastCharSet}/{@code classify}) only
		 * when {@code bodyLastCharSet} itself is {@code null}; a non-null but WORD-ness-mixed
		 * {@code bodyLastCharSet} still resolves, to {@link PatternConstruct#universalCodePointSet}
		 * (since some prior character in it always matches whatever word-ness the peek character
		 * has, \b/\B can then hold for ANY peek).
		 */
		static @Nullable CodePointSet admittedInteriorExitPeekSet(
				boolean isWordBoundary, @Nullable CodePointSet bodyLastCharSet, int flags) {
			if (bodyLastCharSet == null) {
				return null;
			}
			CodePointSet wordSet = RegexCharacterClass.w.get(flags);
			Wordness prior = classify(bodyLastCharSet, wordSet);
			if (prior == Wordness.UNKNOWN) {
				return universalCodePointSet();
			}
			// Same "wantsWordPeek" formula buildMatcher() uses for its own statically-known-prior case.
			boolean priorIsWord = prior == Wordness.WORD;
			boolean wantsWordPeek = isWordBoundary != priorIsWord;
			return wantsWordPeek ? wordSet : wordSet.complement();
		}

		@Override
		void buildMatcher() {
			// See design.md's "Boundary matching" section and the class doc for
			// WordBoundaryMatcherConstruct for the full optimization rationale. In brief: both sides
			// of the boundary (the character just consumed, and the one about to be) are classified
			// as always-word/always-non-word/unknown at compile time; whichever side is statically
			// known doesn't need to be checked at match time at all.
			CodePointSet wordSet = RegexCharacterClass.w.get(flags);
			Wordness prior = classify(priorCharSet, wordSet);
			// next's own entry-point map is already exactly a plain CodePointSet -- no separate
			// RangeSet needs building here any more.
			CodePointSet peekRanges = next.getEntryElse() == null ? next.getEntryPointMap() : null;
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
				// `next` is shared/likely already compiled, so any dispatch gating of our own (if this
				// construct is itself a chain candidate) can't be retrofitted onto it directly -- see
				// MatcherConstruct#aliasOrPassThrough's own doc.
				MatcherConstruct.aliasOrPassThrough(this, next.matcher);
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
			new WordBoundaryMatcherConstruct(this, wordSet, priorMatchType, peekMatchType, isWordBoundary);
		}
	}

	/**
	 * {@code (?<=X)}/{@code (?<!X)}, restricted to a body {@code X} that always matches exactly one
	 * code point -- a direct generalization of {@code \b}/{@code \B}'s own single-code-point {@code
	 * peekPrevious()} check (see design.md's "Boundary matching" section); wider lookbehind, and any
	 * lookahead, are permanently out of scope (can't be evaluated in O(1) per position). {@code
	 * lookSet} and {@code captureConstructIndex} are both fully resolved at parse time by {@link
	 * #resolveSingleCodePointBody} -- unlike {@code WordBoundaryConstruct}, there's no neighbor
	 * context to wait for, so this construct needs no {@code buildEntryMap}-time classification step.
	 */
	static final class LookbehindConstruct extends PatternConstruct {
		final String pattern;
		final boolean isPositive; // true: (?<=X), false: (?<!X)
		final CodePointSet lookSet;
		final int captureConstructIndex; // -1 if the body wasn't wrapped in a capturing group

		LookbehindConstruct(
				String pattern, int startIndex, int endIndex, boolean isPositive,
				CodePointSet lookSet, int captureConstructIndex) {
			super(startIndex, endIndex);
			this.pattern = pattern;
			this.isPositive = isPositive;
			this.lookSet = lookSet;
			this.captureConstructIndex = captureConstructIndex;
		}

		@Override
		void buildEntryMap(PatternConstruct next) {
			entryElse = this;
		}

		@Override
		void buildMatcher() {
			// Always a real check -- unlike \b/\B, there's no "peek" side to statically classify
			// away: the previous character is never known at compile time, so this never collapses
			// to a no-op or a compile-time error the way WordBoundaryConstruct sometimes does.
			new MatcherConstruct.LookbehindMatcherConstruct(this, isPositive, lookSet, captureConstructIndex);
		}

		/** The result of {@link #resolveSingleCodePointBody}: the body's statically-known
		 *  code point set, plus which capturing group (if any) wraps the whole body. */
		static final class SingleCodePointBody {
			final CodePointSet codePoints;
			final int captureConstructIndex; // -1 if none

			SingleCodePointBody(CodePointSet codePoints, int captureConstructIndex) {
				this.codePoints = codePoints;
				this.captureConstructIndex = captureConstructIndex;
			}
		}

		/**
		 * Statically resolves a lookbehind body to "always matches exactly one code point, optionally
		 * wrapped in a single capturing group around the whole body" -- or {@code null} if it doesn't
		 * (e.g. more than one code point wide, optional/repeated, or more than one capturing group).
		 * Same recursive shape as {@link #lastCharSet}/{@link #firstCharSet} but stricter (needs total
		 * width exactly 1, not just "last/first character known") and threads a capture index too.
		 */
		static @Nullable SingleCodePointBody resolveSingleCodePointBody(PatternConstruct pc) {
			if (pc instanceof LiteralString) {
				CharSequence value = ((LiteralString) pc).value;
				if (Character.codePointCount(value, 0, value.length()) != 1) {
					return null;
				}
				MutableCodePointSet set = new ArrayCodePointSet();
				set.add(Character.codePointAt(value, 0));
				// Folded, unlike lastCharSet's raw singleton: a literal's real match-time membership
				// (what this assertion must actually check) is the folded set under CASE_INSENSITIVE/
				// UNICODE_CASE, exactly like LiteralString.buildEntryMap's own entryMap.
				return new SingleCodePointBody(MatcherConstruct.foldedEntrySet(set, pc.flags), -1);
			}
			if (pc instanceof ComplexCharacter) {
				return new SingleCodePointBody(((ComplexCharacter) pc).validRanges(), -1);
			}
			if (pc instanceof ComplexQuantifiedCharacter) {
				ComplexQuantifiedCharacter cqc = (ComplexQuantifiedCharacter) pc;
				return cqc.min == 1 && cqc.max == 1
						? new SingleCodePointBody(cqc.delegate.validRanges(), -1)
						: null;
			}
			if (pc instanceof QuantifiedUnion) {
				QuantifiedUnion union = (QuantifiedUnion) pc;
				if (union.min != 1 || union.max != 1 || union.constructs.isEmpty()) {
					return null;
				}
				boolean isCapturing = union.captureConstructIndex >= 0;
				if (union.constructs.size() == 1) {
					SingleCodePointBody inner = resolveSingleCodePointBody(union.constructs.get(0));
					if (inner == null) {
						return null;
					}
					if (!isCapturing) {
						return inner;
					}
					// A capturing group can't itself wrap another capturing group here -- there's only
					// one code point behind this position for at most one group to claim.
					return inner.captureConstructIndex == -1
							? new SingleCodePointBody(inner.codePoints, union.captureConstructIndex)
							: null;
				}
				// A real alternation: every branch must resolve with no capturing group of its own --
				// only the whole alternation (via an enclosing capturing group on this union) may
				// capture, e.g. (?<=(a|b)) is supported, (?<=(a)|(b)) is not.
				MutableCodePointSet result = new ArrayCodePointSet();
				for (PatternConstruct branch : union.constructs) {
					SingleCodePointBody inner = resolveSingleCodePointBody(branch);
					if (inner == null || inner.captureConstructIndex != -1) {
						return null;
					}
					result.addAll(inner.codePoints);
				}
				return new SingleCodePointBody(result, union.captureConstructIndex);
			}
			if (pc instanceof Sequence) {
				List<PatternConstruct> patterns = ((Sequence) pc).patterns;
				return patterns.size() == 1 ? resolveSingleCodePointBody(patterns.get(0)) : null;
			}
			return null;
		}

		/**
		 * Loop-ambiguity helper only -- see {@code PatternConstruct#skipZeroWidthEntrySet}'s {@code
		 * checkAssertions} doc, and {@code WordBoundaryConstruct#admittedInteriorExitPeekSet}'s own
		 * doc for why the coarse catch-all entry point ({@code entryElse = this}) isn't safe for a
		 * loop's own continue-vs-exit ambiguity check. Simpler than that method's version: a
		 * lookbehind's truth depends ONLY on the prior character, never on peek at all, so once {@code
		 * bodyLastCharSet} shows this assertion COULD hold right after a body iteration, exiting
		 * through it is ambiguous with continuing for literally every peek code point; otherwise it
		 * contributes nothing.
		 */
		static @Nullable CodePointSet admittedInteriorExitPeekSet(
				boolean isPositive, CodePointSet lookSet, @Nullable CodePointSet bodyLastCharSet) {
			if (bodyLastCharSet == null) {
				return null;
			}
			// first(), not entrySet(), so a violation short-circuits -- same technique as
			// WordBoundaryConstruct's own isSubsetOf/isDisjointFrom helpers.
			boolean subsetOfLookSet = !bodyLastCharSet.first((min, max) -> !lookSet.containsAll(min, max));
			boolean couldHold = isPositive ? bodyLastCharSet.intersects(lookSet) : !subsetOfLookSet;
			return couldHold ? universalCodePointSet() : new ArrayCodePointSet();
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
	static @Nullable CodePointSet lastCharSet(PatternConstruct pc) {
		if (pc instanceof LiteralString) {
			CharSequence value = ((LiteralString) pc).value;
			if (value.length() == 0) {
				return null;
			}
			int cp = Character.codePointBefore(value, value.length());
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
			MutableCodePointSet result = new ArrayCodePointSet();
			for (PatternConstruct branch : union.constructs) {
				CodePointSet branchSet = lastCharSet(branch);
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

	private static CodePointSet singletonCodePointMap(int codePoint) {
		MutableCodePointSet result = new ArrayCodePointSet();
		result.add(codePoint, codePoint + 1);
		return result;
	}

	/** Every code point -- used by the {@code admittedInteriorExitPeekSet} methods below for the
	 *  "any peek could be ambiguous" case (e.g. a loop body with both word and non-word last
	 *  characters, against \b/\B). */
	static CodePointSet universalCodePointSet() {
		MutableCodePointSet result = new ArrayCodePointSet();
		result.add(0, CodePointSet.MAX_CODE_POINT + 1);
		return result;
	}

	private static CodePointSet union(CodePointSet a, CodePointSet b) {
		MutableCodePointSet result = new ArrayCodePointSet();
		result.addAll(a);
		result.addAll(b);
		return result;
	}

	/**
	 * The union of {@link #lastCharSet} over every candidate in a loop's own {@code body} list, or
	 * {@code null} if any candidate's own last-character set isn't statically known -- used only by
	 * {@code QuantifiableConstruct.buildLoopMatcher}'s greedy-loop zero-width-assertion ambiguity
	 * check (see {@link #skipZeroWidthEntrySet}'s {@code checkAssertions} doc). Deliberately
	 * all-or-nothing (one unknown candidate gives up entirely, rather than unioning what the KNOWN
	 * candidates contribute) -- same conservative-fallback philosophy as {@code lastCharSet} itself.
	 */
	private static @Nullable CodePointSet unionLastCharSet(List<PatternConstruct> body) {
		MutableCodePointSet result = new ArrayCodePointSet();
		for (PatternConstruct part : body) {
			CodePointSet partLast = lastCharSet(part);
			if (partLast == null) {
				return null;
			}
			result.addAll(partLast);
		}
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
	/**
	 * {@code pc}'s own entry point (see {@link #getEntryPointMap}), but seeing straight through any
	 * zero-width assertion ({@code BoundaryConstruct}/{@code LineBoundaryConstruct}/{@code
	 * WordBoundaryConstruct}) to whatever actually determines which code points can follow --
	 * used ONLY by a loop's own ambiguity check ({@code QuantifiableConstruct.buildLoopMatcher}'s
	 * {@link #checkDisjoint} call against its own {@code next}), never by ordinary union/dispatch
	 * construction. A loop's body, once it decides to continue, has already (irreversibly, since
	 * this engine never backtracks) consumed a code point -- so the real question for loop ambiguity
	 * is "could exiting the loop, possibly through one or more zero-width assertions, eventually
	 * require the SAME code point some body part would also accept," not "what does the very next
	 * AST node, in isolation, claim." An ordinary union's own dispatch never commits anything before
	 * a zero-width assertion's own runtime check can veto it, so treating such an assertion as a
	 * low-priority catch-all (its ordinary {@code entryElse = this}, no explicit ranges) is fine
	 * there -- see design.md's "Boundary matching" section -- but a loop can't afford that same
	 * latitude, since it has nowhere to backtrack to once it's consumed a character (see
	 * remaining_work.md's now-fixed "loop followed by a zero-width assertion" entry, e.g. {@code
	 * a*^a}).
	 *
	 * <p>Recurses into a {@code Sequence}'s first element and an unquantified {@code
	 * QuantifiedUnion}'s own branches (unioning them), the same shape {@link #firstCharSet}/{@link
	 * #lastCharSet} use, so a boundary buried inside a nested group ({@code (^a)}) or alternation
	 * ({@code (^|x)}) is still seen through. Anything else (a quantified construct, {@code
	 * CaptureEndMarker}, a leaf) is returned via its own, already-correct {@link
	 * #getEntryPointMap()} -- safe against the one real cycle this engine has (a loop nested in this
	 * loop's own tail), since recursion here only ever continues through unquantified, non-looping
	 * AST shapes.
	 *
	 * <p>{@code checkAssertions} (when {@code true}, with {@code bodyLastCharSet} the union of
	 * {@code lastCharSet()} over the loop's own body candidates, or {@code null} if that's not
	 * statically known) additionally unions in whatever code points a {@code \b}/{@code \B}/
	 * {@code MULTILINE ^}/{@code MULTILINE $} passed through could themselves admit at an INTERIOR
	 * exit -- i.e. right after one more body iteration, not just once the whole tail is otherwise
	 * forced. Plain seeing-through (the {@code false} case above) is exactly right for the "what
	 * does the tail eventually require" question, but these four assertion types are
	 * position-dependent (their truth value depends on which character the loop body just
	 * consumed), so treating them as a low-priority catch-all -- correct for the "what does the
	 * tail eventually require" question the {@code false} case answers -- misses that exiting
	 * through them can ALSO be valid at exactly the same code points the body would keep consuming
	 * on (e.g. {@code a+\B}: after consuming an 'a', \B holds precisely when the next 'a' is also
	 * there, since a word character never differs in word-ness from another word character -- see
	 * {@code WordBoundaryConstruct#admittedInteriorExitPeekSet}/{@code
	 * LineBoundaryConstruct#admittedInteriorExitPeekSet}). Only used for a plain greedy loop's own
	 * check (never reluctant, whose early exit is instead proven safe/unsafe at MATCH time by
	 * {@code MatcherConstruct#exitAssertionChain}, and never possessive, which -- like
	 * {@code java.util.regex}'s own possessive quantifier -- never backtracks either, so this
	 * engine's already-non-backtracking compilation can't newly disagree with it) -- see
	 * {@code QuantifiableConstruct#buildLoopMatcher}.
	 */
	private static CodePointSet skipZeroWidthEntrySet(
			PatternConstruct pc, boolean checkAssertions, @Nullable CodePointSet bodyLastCharSet) {
		if (pc instanceof WordBoundaryConstruct) {
			CodePointSet rest = skipZeroWidthEntrySet(pc.next, checkAssertions, bodyLastCharSet);
			if (!checkAssertions) {
				return rest;
			}
			CodePointSet admitted = WordBoundaryConstruct.admittedInteriorExitPeekSet(
					((WordBoundaryConstruct) pc).isWordBoundary, bodyLastCharSet, pc.flags);
			return admitted == null ? rest : union(rest, admitted);
		}
		if (pc instanceof LineBoundaryConstruct) {
			CodePointSet rest = skipZeroWidthEntrySet(pc.next, checkAssertions, bodyLastCharSet);
			if (!checkAssertions || (pc.flags & Ll1Pattern.MULTILINE) == 0) {
				return rest;
			}
			CodePointSet admitted = LineBoundaryConstruct.admittedInteriorExitPeekSet(
					((LineBoundaryConstruct) pc).isLineBegin, bodyLastCharSet, pc.flags);
			return admitted == null ? rest : union(rest, admitted);
		}
		if (pc instanceof BoundaryConstruct) {
			return skipZeroWidthEntrySet(pc.next, checkAssertions, bodyLastCharSet);
		}
		if (pc instanceof LookbehindConstruct) {
			LookbehindConstruct lb = (LookbehindConstruct) pc;
			CodePointSet rest = skipZeroWidthEntrySet(pc.next, checkAssertions, bodyLastCharSet);
			if (!checkAssertions) {
				return rest;
			}
			CodePointSet admitted =
					LookbehindConstruct.admittedInteriorExitPeekSet(lb.isPositive, lb.lookSet, bodyLastCharSet);
			return admitted == null ? rest : union(rest, admitted);
		}
		if (pc instanceof Sequence) {
			List<PatternConstruct> patterns = ((Sequence) pc).patterns;
			return patterns.isEmpty()
					? pc.getEntryPointMap()
					: skipZeroWidthEntrySet(patterns.get(0), checkAssertions, bodyLastCharSet);
		}
		if (pc instanceof QuantifiedUnion && ((QuantifiedUnion) pc).isUnquantified()) {
			List<PatternConstruct> constructs = ((QuantifiedUnion) pc).constructs;
			if (!constructs.isEmpty()) {
				MutableCodePointSet result = new ArrayCodePointSet();
				for (PatternConstruct branch : constructs) {
					result.addAll(skipZeroWidthEntrySet(branch, checkAssertions, bodyLastCharSet));
				}
				return result;
			}
		}
		return pc.getEntryPointMap();
	}

	static @Nullable CodePointSet firstCharSet(PatternConstruct pc) {
		if (pc instanceof LiteralString) {
			CharSequence value = ((LiteralString) pc).value;
			return value.length() == 0 ? null : singletonCodePointMap(Character.codePointAt(value, 0));
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
			MutableCodePointSet result = new ArrayCodePointSet();
			for (PatternConstruct branch : union.constructs) {
				CodePointSet branchSet = firstCharSet(branch);
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

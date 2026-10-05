package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.ArrayCodePointSet;
import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.CodePointSetBuilder;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;
import com.tbohne.llkpattern.PatternSyntaxException;

import com.tbohne.llkpattern.impl.unicode.CodePointSet.MutableCodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;


public abstract class PatternConstruct {
	public final int startIndex;
	public int endIndex = -1;

	// The parser's flags in effect where this construct was written (after any enclosing inline
	// "(?i:...)"). Set by PatternParser after each `new`, and copied to the compiled
	// MatcherConstruct so CASE_INSENSITIVE folding stays scoped to where it was written.
	public int flags = 0;

	@MonotonicNonNull MatcherConstruct matcher;

	// Set by a chain builder just before compile() when this is one candidate among several (see
	// MatcherConstruct's "Flattened dispatch" class doc). compile() memoizes on `matcher`, so these
	// must be set BEFORE the first compile().
	@Nullable CodePointSet dispatchEntrySet;
	@Nullable MatcherConstruct dispatchFailedEntry;

	// What compile() was last called with, so an ancestor can rely on `entryMapValue.matcher`
	// already being set (compilation runs tail-to-front).
	@MonotonicNonNull PatternConstruct next;

	// Shared default for constructs whose buildEntryMap() only ever sets entryElse.
	static final CodePointSet EMPTY_ENTRY_MAP = new ArrayCodePointSet();

	// Code points this construct claims as its entry point once it (and anything it can trivially
	// skip) has matched. Built lazily by buildEntryMap() via ensureEntryPointBuilt(); read only
	// through getEntryPointMap()/getEntryElse().
	//
	// A plain (not Mutable) code-point set, never a "code point -> owning construct" map, so that
	// overrides can safely alias another construct's set. mergeEntryPoints uses a
	// construct-valued map transiently but projects it to a plain set before returning: exposing
	// that map as an ancestor's entry map once broke downstream `==` identity checks (a loop's
	// continue-vs-exit classification).
	CodePointSet entryMap = EMPTY_ENTRY_MAP;
	@MonotonicNonNull PatternConstruct entryElse;

	private static final int ENTRY_POINT_NOT_STARTED = 0;
	private static final int ENTRY_POINT_CONSTRUCTING = 1;
	private static final int ENTRY_POINT_CONSTRUCTED = 2;
	private int entryPointState = ENTRY_POINT_NOT_STARTED;

	PatternConstruct(int startIndex) {
		this.startIndex = startIndex;
	}

	PatternConstruct(int startIndex, int endIndex) {
		this.startIndex = startIndex;
		this.endIndex = endIndex;
	}

	// Only @MonotonicNonNull because siblings are wired tail-to-front; this turns "not wired yet" into a loud error.
	PatternConstruct next() {
		PatternConstruct n = next;
		if (n == null) {
			throw nextNotWired();
		}
		return n;
	}

	// Out of line so next() stays small enough for ART to inline.
	private IllegalStateException nextNotWired() {
		return new IllegalStateException(getClass().getSimpleName() + " at pattern index " + startIndex
				+ " read `next` before it was wired (did you mean to call compile(next) first?)");
	}

	// As next(): matchers self-register mid-construction to break cycles.
	final MatcherConstruct matcher() {
		MatcherConstruct m = matcher;
		if (m == null) {
			throw new IllegalStateException(getClass().getSimpleName() + " at pattern index " + startIndex
					+ " has no compiled matcher yet (did you mean to call compile(next) on it first?)");
		}
		return m;
	}

	// Safe because nothing reads `matcher` until the registering MatcherConstruct constructor returns.
	@SuppressWarnings("initialization.field.write.initialized")
	final void registerMatcher(@UnknownInitialization MatcherConstruct m) {
		matcher = m;
	}

	// See design.md "Entry-point computation vs. matcher compilation".
	final CodePointSet getEntryPointMap() {
		ensureEntryPointBuilt();
		return entryMap;
	}

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
		buildEntryMap(next());
		entryPointState = ENTRY_POINT_CONSTRUCTED;
	}

	/**
	 * Whether this construct has a catch-all entry, answered without forcing its own {@link
	 * #entryMap} to materialize (used by {@link #mergeEntryPoints}).
	 *
	 * <p>The default goes through the cycle-guarded {@link #getEntryElse()}, and is REQUIRED for
	 * constructs that own ambiguity-checked ranges or merge candidates: a quantified construct's
	 * body can point its {@code next} back at itself for a nullable loop (e.g. {@code (a?)+}), so
	 * bypassing the guard would recurse forever. Override only for a true leaf, or a construct
	 * delegating to exactly one structurally-fixed construct (see {@code
	 * QuantifiedUnionPatternConstruct}'s bare-flags-group override).
	 */
	boolean claimsEntryElse() {
		return getEntryElse() != null;
	}

	/**
	 * Only meaningful once {@link #claimsEntryElse} is true: whether the catch-all is "the pattern
	 * may END here" ({@link EndPatternConstruct}, seen through nullable/zero-width/marker
	 * constructs) rather than a construct that itself accepts any character.
	 *
	 * <p>End-of-find is mode-dependent: under {@code lookingAt()}/{@code find()} the match is
	 * complete once reached, but under {@code matches()} only end of input completes it. So an
	 * end-of-find branch keeps its natural priority position in a union, behind a mode-aware gate
	 * ({@link EndOfFindGateMatcherConstruct}), instead of being a lowest-priority tail fallback.
	 * Every override mirrors an entry-point construct that propagates {@code entryElse}.
	 */
	boolean elseIsEndOfFind() {
		return false;
	}

	/**
	 * Only meaningful once {@link #claimsEntryElse} is true: whether the catch-all is a residual
	 * one -- {@code .}, which claims "whatever its siblings don't", so {@code a|.} means {@code
	 * a|[^a]} and {@code .+b} means {@code [^b]+b}.
	 *
	 * <p>Two residual claimants can't share a choice, but one may share it with an end-of-find exit
	 * ({@code .*} at the end of a pattern): the body takes what it can and the exit takes the rest.
	 * Every override mirrors an {@link #elseIsEndOfFind} one.
	 */
	boolean elseIsResidual() {
		return false;
	}

	/**
	 * Compiles this construct into a MatcherConstruct graph, returning the node that starts
	 * matching it. See design.md's "The compile() algorithm and cycle handling".
	 *
	 * <p>Memoized on {@link #matcher}: already set means this construct was compiled, or a
	 * MatcherConstruct constructor higher on the stack self-registered here to break a cycle.
	 */
	public MatcherConstruct compile(PatternConstruct next) {
		if (matcher != null) {
			return matcher;
		}
		this.next = next;
		if (needsEntryPointBeforeMatcher()) {
			ensureEntryPointBuilt();
		}
		if (matcher == null) {
			// `matcher` is set by the MatcherConstruct constructor buildMatcher() calls, not by its return value.
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
	 * Populates {@link #entryMap}/{@link #entryElse}; called at most once, via {@link
	 * #ensureEntryPointBuilt} -- never call directly. Must not trigger any other construct's
	 * {@link #compile}/{@link #buildMatcher}: only entry-point getters, and for a body part that
	 * needs to see what follows it, a direct {@link #next} assignment.
	 */
	abstract void buildEntryMap(PatternConstruct next);

	abstract void buildMatcher();

	/**
	 * Whether {@link #compile} must run {@link #ensureEntryPointBuilt} before {@link
	 * #buildMatcher}. True by default, since most {@code buildMatcher()} overrides read fields only
	 * {@code buildEntryMap()} populates.
	 *
	 * <p>Override to false ONLY for a true leaf (e.g. {@code LiteralPatternConstruct}) whose
	 * {@code buildMatcher()} reads nothing {@code buildEntryMap()} sets and whose {@code
	 * buildEntryMap()} never reads {@code next}: reached only as a merge candidate, it then skips
	 * materializing its {@link #entryMap}, and a later genuine pull just defers the same work.
	 */
	boolean needsEntryPointBeforeMatcher() {
		return true;
	}

	// Reads "index i of candidates followed by extra" without copying (the arraycopy showed up in
	// Pixel 3a CPU sampling).
	private static PatternConstruct candidateAt(
			ConstructList candidates, @Nullable PatternConstruct extra, int index) {
		return index < candidates.size() ? candidates.get(index) : castNonNull(extra);
	}

	private static int candidateCount(ConstructList candidates, @Nullable PatternConstruct extra) {
		return candidates.size() + (extra != null ? 1 : 0);
	}

	/**
	 * Unions {@code candidates}' entry points (via {@link #getEntryPointMap}/{@link
	 * #getEntryElse}, not {@link #compile}) and picks the one candidate allowed to claim the
	 * any-other-character catch-all. Used for a union's branches and a quantified construct's body
	 * parts. Overlap checking is {@link #checkDisjoint}'s job, not this method's.
	 */
	static MergedEntries mergeEntryPoints(String pattern, ConstructList candidates, String candidateNounPlural) {
		return mergeEntryPoints(pattern, candidates, null, candidateNounPlural);
	}

	/**
	 * As above, plus one more candidate {@code extra} (or null) merged in without copying {@code
	 * candidates} into a new list: a loop's {@code next} joins only when {@code min == 0}.
	 */
	static MergedEntries mergeEntryPoints(
			String pattern, ConstructList candidates, @Nullable PatternConstruct extra,
			String candidateNounPlural) {
		if (extra == null && candidates.size() == 1) {
			// Lone candidate (the common quantified single char/class): alias its entry point, no allocation.
			PatternConstruct only = candidates.get(0);
			return new MergedEntries(only.getEntryPointMap(), only.claimsEntryElse() ? only : null);
		}
		if (extra != null && candidates.isEmpty()) {
			return new MergedEntries(extra.getEntryPointMap(), extra.claimsEntryElse() ? extra : null);
		}
		// Pre-sized from the candidates' summed entry counts (an overestimate) to avoid regrowth.
		// Plain ArrayCodePointSet, not CodePointSetBuilder: the builder's sort/compact pass cost
		// more than it saved for the usual 2-3 candidates (notes.md 2026-09-25).
		int capacityHint = rangeCountHint(candidateAt(candidates, extra, 0).getEntryPointMap());
		int candidateCount = candidateCount(candidates, extra);
		for (int i = 1; i < candidateCount; i++) {
			capacityHint += rangeCountHint(candidateAt(candidates, extra, i).getEntryPointMap());
		}
		MutableCodePointSet ranges = new ArrayCodePointSet(capacityHint);
		PatternConstruct elseCandidate = null;
		for (int i = 0, n = candidates.size; i < n; i++) {
			elseCandidate = mergeOneEntryPoint(pattern, candidates.items[i], elseCandidate, candidateNounPlural, ranges, false);
		}
		if (extra != null) {
			elseCandidate = mergeOneEntryPoint(pattern, extra, elseCandidate, candidateNounPlural, ranges, true);
		}
		return new MergedEntries(ranges, elseCandidate);
	}

	// An overestimate of set's entry count, for pre-sizing.
	private static int rangeCountHint(CodePointSet set) {
		if (set instanceof ArrayCodePointSet) {
			return ((ArrayCodePointSet) set).size;
		}
		int[] count = {0};
		set.forEachRange((min, max) -> count[0]++);
		return count[0];
	}

	// Unions one candidate's entry point into ranges; throws if it and an earlier one both claim the catch-all.
	private static @Nullable PatternConstruct mergeOneEntryPoint(
			String pattern, PatternConstruct candidate, @Nullable PatternConstruct elseCandidate,
			String candidateNounPlural, MutableCodePointSet ranges, boolean isLoopExit) {
		if (candidate.claimsEntryElse()) {
			// Only a loop's own exit, never a union branch (whose end-of-find would be lost to the
			// residual tail): a residual body (`.` in `.*`) and an end-of-find exit coexist.
			if (isLoopExit && elseCandidate != null && elseCandidate.elseIsResidual() && candidate.elseIsEndOfFind()) {
				ranges.insertAll(candidate.getEntryPointMap());
				return elseCandidate;
			}
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
		ranges.insertAll(candidate.getEntryPointMap());
		return elseCandidate;
	}

	/**
	 * Throws the first ambiguity among {@code candidates}, checked in priority order: two
	 * candidates whose entry ranges overlap. Returns the entry sets (index-aligned with {@code
	 * candidates} then {@code extra}) for reuse as dispatch gates.
	 *
	 * <p>Entry sets already carry any CASE_INSENSITIVE folding (a class's at parse time, a
	 * literal's or backreference's via {@code MatcherConstruct#foldedEntrySet}; a named class is
	 * deliberately never folded, as in the JDK), so {@code (?i:[a-z]+)X} is rejected as ambiguous.
	 *
	 * <p>Each candidate is checked against every earlier one with the allocation-free {@link
	 * CodePointSet#intersects}; the overlap is only computed on the conflict path, to name it.
	 * The later (lower-priority) candidate is blamed.
	 *
	 * <p>Never checks against anything outside {@code candidates}: {@link #buildFlattenedChain}'s
	 * {@code elseTarget} fallback is expected to overlap every other candidate.
	 */
	static CodePointSet[] checkDisjoint(
			String pattern, int flags, ConstructList candidates, @Nullable PatternConstruct extra,
			String candidateNounPlural) {
		return checkDisjoint(pattern, flags, candidates, extra, null, candidateNounPlural);
	}

	/**
	 * As above, but {@code extraEntrySet} overrides {@code extra}'s entry set for the overlap
	 * comparison only (an error still blames {@code extra}) -- see {@link #skipZeroWidthEntrySet}.
	 */
	static CodePointSet[] checkDisjoint(
			String pattern, int flags, ConstructList candidates, @Nullable PatternConstruct extra,
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
	 * Builds a flattened dispatch chain over {@code candidates} (never empty), tried in list order;
	 * see {@code MatcherConstruct}'s "Flattened dispatch" class doc. Each candidate becomes a fork
	 * itself via {@code dispatchEntrySet}/{@code dispatchFailedEntry}, set immediately before it is
	 * compiled (compiling memoizes on {@link #matcher}). Compiled tail-to-front so each candidate's
	 * {@code dispatchFailedEntry} points at the next one's matcher.
	 *
	 * <p>Ambiguity is checked on entry points alone via {@link #checkDisjoint}. The entry sets it
	 * compares are the same ones handed to {@code dispatchEntrySet}, so there is no
	 * conflict-check-only allocation to eliminate (notes.md 2026-09-18).
	 *
	 * <p>{@code elseCandidate} is the construct {@code elseTarget} was compiled from (or null);
	 * only its explicit entry ranges are checked. {@code endOfFindCandidate} (a member of {@code
	 * candidates}, or null) is the one candidate whose catch-all is end-of-find; it keeps its list
	 * position behind an {@link EndOfFindGateMatcherConstruct}, so {@code elseCandidate} is null then.
	 *
	 * <p>{@code elseTarget} is the final fallback, or null for none, in which case the last
	 * candidate is left ungated: its own matcher re-verifies membership as its first action.
	 *
	 * <p>When {@code owner} is non-null the chain's head becomes {@code owner}'s matcher (via
	 * {@link MatcherConstruct#aliasOrPassThrough}, so an outer chain's dispatch fields on {@code
	 * owner} aren't dropped).
	 */
	static MatcherConstruct buildFlattenedChain(
			@Nullable PatternConstruct owner,
			int flags,
			String pattern,
			ConstructList candidates,
			String candidateNounPlural,
			PatternConstruct compileTarget,
			@Nullable MatcherConstruct elseTarget,
			@Nullable PatternConstruct elseCandidate,
			@Nullable PatternConstruct endOfFindCandidate) {
		// elseCandidate gets no gate of its own, so `gates` past `count` is unused.
		CodePointSet[] gates = checkDisjoint(pattern, flags, candidates, elseCandidate, candidateNounPlural);
		int count = candidates.size;
		MatcherConstruct tail = elseTarget;
		for (int i = count - 1; i >= 0; i--) {
			PatternConstruct candidate = candidates.items[i];
			boolean lastUngated = (i == count - 1 && elseTarget == null);
			if (candidate == endOfFindCandidate && !lastUngated) {
				// Keeps its list position (JDK alternation order): ungated, behind a gate that also admits end-of-find.
				candidate.dispatchEntrySet = null;
				candidate.dispatchFailedEntry = null;
				tail = new EndOfFindGateMatcherConstruct(
						candidate.flags, gates[i], candidate.compile(compileTarget), tail);
				continue;
			}
			candidate.dispatchEntrySet = lastUngated
					? null
					: gates[i];
			candidate.dispatchFailedEntry = lastUngated ? null : tail;
			tail = candidate.compile(compileTarget);
		}
		if (tail == null) {
			throw new IllegalStateException("buildFlattenedChain() was given no candidates and no elseTarget "
					+ "(did the caller mean to skip building a chain for an empty union?)");
		}
		if (owner != null) {
			MatcherConstruct.aliasOrPassThrough(owner, tail);
		}
		return tail;
	}

	/**
	 * Entry point of a zero-width assertion ({@code \b}, {@code ^}, a lookbehind, ...): whatever
	 * can start what FOLLOWS it. The assertion only ever narrows when its branch can succeed at
	 * match time, so {@code next}'s entry set is a sound (if not tight) gate, and a branch opening
	 * with one is ambiguity-checked by the code points it can really start with ({@code \b[ab]c}
	 * starts with {@code a} or {@code b}). Catch-all is inherited only when {@code next} claims it.
	 */
	static void buildZeroWidthEntryMap(PatternConstruct owner, PatternConstruct next) {
		owner.entryMap = next.getEntryPointMap();
		if (next.getEntryElse() != null) {
			owner.entryElse = owner;
		}
	}

	/**
	 * Code points that could be the LAST one consumed if this construct matches here, when statically
	 * known regardless of input; null if not known. Used by WordBoundaryPatternConstruct's
	 * \b/\B compile-time optimization (design.md "Boundary matching").
	 *
	 * <p>Null for anything that could match zero-width, including a type with no override: always a
	 * safe fallback, just a missed optimization. Overridden by Literal, ComplexCharacter,
	 * ComplexQuantifiedCharacter, QuantifiedUnion and Sequence constructs. A virtual method rather
	 * than an `instanceof` chain (see skipZeroWidthEntrySet).
	 */
	@Nullable CodePointSet lastCharSet() {
		return null;
	}

	static CodePointSet singletonCodePointMap(int codePoint) {
		MutableCodePointSet result = new ArrayCodePointSet();
		result.insert(codePoint, codePoint + 1);
		return result;
	}

	// Built once: callers only read it, and building the full range per call showed up at ~9% of
	// sampled allocation for \X-heavy corpora (notes.md 2026-09-26).
	private static final CodePointSet UNIVERSAL_CODE_POINT_SET = buildUniversalCodePointSet();

	private static CodePointSet buildUniversalCodePointSet() {
		CodePointSetBuilder result = CodePointSetBuilder.create();
		result.append(0, CodePointSet.MAX_CODE_POINT + 1);
		return result.build();
	}

	static CodePointSet universalCodePointSet() {
		return UNIVERSAL_CODE_POINT_SET;
	}

	static CodePointSet union(CodePointSet a, CodePointSet b) {
		MutableCodePointSet result = new ArrayCodePointSet();
		result.insertAll(a);
		result.insertAll(b);
		return result;
	}

	/**
	 * The union of {@link #lastCharSet} over a loop's body candidates, or null if any is unknown.
	 * All-or-nothing, like {@code lastCharSet} itself. Used only by the greedy-loop zero-width
	 * ambiguity check (see {@link #skipZeroWidthEntrySet}).
	 */
	static @Nullable CodePointSet unionLastCharSet(ConstructList body) {
		if (body.size() == 1) {
			// No copy: callers only read the result.
			return body.get(0).lastCharSet();
		}
		// lastCharSet() isn't cached, so compute each part once, up front (a null still means give up).
		CodePointSet[] partLastSets = new CodePointSet[body.size()];
		int capacityHint = 0;
		for (int i = 0, n = body.size; i < n; i++) {
			CodePointSet partLast = body.items[i].lastCharSet();
			if (partLast == null) {
				return null;
			}
			partLastSets[i] = partLast;
			capacityHint += rangeCountHint(partLast);
		}
		// Last-char sets needn't be disjoint, so this hint overshoots more than mergeEntryPoints'.
		MutableCodePointSet result = new ArrayCodePointSet(capacityHint);
		for (CodePointSet partLast : partLastSets) {
			result.insertAll(partLast);
		}
		return result;
	}

	/**
	 * This construct's entry point, but seeing through zero-width assertions to whatever determines
	 * which code points can follow. Used ONLY by a loop's own ambiguity check against its {@code
	 * next} ({@code QuantifiablePatternConstruct.buildLoopMatcher}), never by union dispatch.
	 *
	 * <p>A loop that continues has irreversibly consumed a code point (no backtracking), so the
	 * question is "could exiting through zero or more assertions require the SAME code point a
	 * body part accepts". A union's dispatch commits nothing before an assertion's runtime check
	 * can veto it, so plain see-through is fine there (design.md "Boundary matching"), but a loop
	 * can't afford it (e.g. {@code a*^a}).
	 *
	 * <p>Recurses into a Sequence's first element and an unquantified union's branches, so a
	 * boundary inside {@code (^a)} or {@code (^|x)} is still seen through. Anything else is its
	 * own {@link #getEntryPointMap()}, safe against the one real cycle (a loop nested in this
	 * loop's tail) since recursion only continues through unquantified, non-looping shapes.
	 *
	 * <p>{@code checkAssertions} (with {@code bodyLastCharSet} the {@link #unionLastCharSet} of the
	 * loop body, or null if unknown) additionally unions in what a {@code \b}/{@code \B}/{@code
	 * MULTILINE ^}/{@code MULTILINE $} could admit at an INTERIOR exit, right after one more body
	 * iteration. Those assertions depend on the character just consumed, so exiting through them
	 * can be valid at exactly the code points the body would keep consuming (e.g. {@code a+\B}
	 * holds precisely when the next 'a' is also there); see each type's {@code
	 * admittedInteriorExitPeekSet}. Used only for a plain greedy loop: a reluctant loop's early
	 * exit is checked at match time by {@code MatcherConstruct#exitAssertionChain}, and a
	 * possessive one never backtracks, as in {@code java.util.regex}.
	 *
	 * <p>The default claims the entry point normally; zero-width and wrapper types override it. A
	 * virtual method, not the `instanceof` chain it replaced (2026-09-27), whose per-type tests
	 * every ordinary construct failed before reaching the fallback (notes.md 2026-09-26).
	 */
	CodePointSet skipZeroWidthEntrySet(boolean checkAssertions, @Nullable CodePointSet bodyLastCharSet) {
		return getEntryPointMap();
	}

	/**
	 * The mirror of {@link #lastCharSet}: code points that could be the FIRST one consumed, when
	 * statically known; null if not (same safe fallback). Used by {@code
	 * BackReferencePatternConstruct}'s entry set, which is the referenced group's first-char set
	 * (design.md "Backreferences"). Overridden by the same types as {@code lastCharSet}.
	 */
	@Nullable CodePointSet firstCharSet() {
		return null;
	}

	/**
	 * Resolves this construct to "always matches exactly one code point, optionally wrapped in one
	 * capturing group around the whole body", or null if it doesn't (wider, optional/repeated, or
	 * several capturing groups). Used only by {@code LookbehindPatternConstruct}; overridden by the
	 * same types as {@code lastCharSet}/{@code firstCharSet}.
	 */
	public LookbehindPatternConstruct.@Nullable SingleCodePointBody resolveSingleCodePointBody() {
		return null;
	}

}

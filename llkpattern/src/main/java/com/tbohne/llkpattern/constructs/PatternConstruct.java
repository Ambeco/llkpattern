package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.CodePointSet.MutableCodePointSet;
import com.tbohne.llkpattern.NamedCharClass.*;

import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import java.util.ArrayList;
import java.util.List;

public abstract class PatternConstruct {
	public final int startIndex;
	public int endIndex = -1;

	// The parser's `flags` (CASE_INSENSITIVE/UNICODE_CASE/etc.) in effect at the moment this
	// construct was parsed -- i.e. after any enclosing inline "(?i:...)" toggle has been applied,
	// and before it's restored on group exit. Mutable (not a constructor param) purely to keep
	// every existing PatternConstruct subclass constructor unchanged; PatternParser sets it right
	// after each `new` call. Propagated to this construct's compiled MatcherConstruct (see
	// MatcherConstruct's own `flags` field) so CASE_INSENSITIVE folding at match time is scoped to
	// wherever the pattern was written case-insensitively, not the whole pattern's global flags --
	// see "Inline flag toggles don't actually locally scope anything" in remaining_work.md.
	public int flags = 0;

	@MonotonicNonNull MatcherConstruct matcher;

	// Set by a chain builder (see #buildFlattenedChain, QuantifiablePatternConstruct#buildLoopMatcher) just
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

	// A shared, never-mutated empty set -- the default for any construct (BoundaryPatternConstruct,
	// WordBoundaryPatternConstruct) whose buildEntryMap() only ever sets entryElse, never entryMap itself.
	// One shared instance rather than `new ArrayCodePointSet()` per construct instance, now that
	// entryMap is a plain (immutable-from-here) CodePointSet reference, not something built up via
	// per-construct mutation -- see entryMap's own doc below.
	static final CodePointSet EMPTY_ENTRY_MAP = new ArrayCodePointSet();

	// The set of code points this construct claims as its own entry point, once it (and anything
	// it can trivially skip, e.g. an optional quantifier) has matched. Populated by buildEntryMap()
	// (lazily, via ensureEntryPointBuilt() -- see getEntryPointMap()/getEntryElse() below);
	// consumed while compiling a containing QuantifiedUnionPatternConstruct/SequencePatternConstruct to detect ambiguous branches,
	// and to build the MatcherConstruct graph. Never read directly outside this construct's own
	// buildEntryMap() -- every other reader goes through the getters.
	//
	// Plain code-point-set membership (never a "code point -> owning construct" map) -- every
	// entryMap-populating call in every buildEntryMap() override below either aliases another
	// construct's own entryMap directly (a construct whose own entry point is exactly some other
	// construct's -- SequencePatternConstruct's first element, CaptureEndPatternConstruct's realNext, a bare-flags-only
	// union's next, a ComplexCharacterPatternConstruct's own validRanges(), a BackReferencePatternConstruct's referenced group's
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

	static final int ENTRY_POINT_NOT_STARTED = 0;
	static final int ENTRY_POINT_CONSTRUCTING = 1;
	static final int ENTRY_POINT_CONSTRUCTED = 2;
	private int entryPointState = ENTRY_POINT_NOT_STARTED;

	/**
	 * Thrown by {@link #ensureEntryPointBuilt} when computing a construct's own entry point would
	 * require that same computation to already be finished -- i.e. a quantified construct whose
	 * body can match zero characters (e.g. {@code (a?)+}), the one case this engine can't assign a
	 * meaningful "what comes next" set to. Caught and re-thrown as a {@link PatternSyntaxException}
	 * by {@code Ll1Pattern.compile()}, which has the full pattern string this needs for a proper
	 * message. See design.md's "Entry-point computation vs. matcher compilation" section.
	 */

	PatternConstruct(int startIndex) {
		this.startIndex = startIndex;
	}

	PatternConstruct(int startIndex, int endIndex) {
		this.startIndex = startIndex;
		this.endIndex = endIndex;
	}

	/**
	 * {@link #next}, for every read after {@link #compile} (or a parent's own buildEntryMap) has set
	 * it. The field is only {@code @MonotonicNonNull} because it cannot be a constructor argument
	 * (siblings are wired tail-to-front, and a loop's body points back at its own marker), so this
	 * is the one place that turns "not wired yet" into a loud error instead of an NPE.
	 */
	PatternConstruct next() {
		PatternConstruct n = next;
		if (n == null) {
			throw new IllegalStateException(getClass().getSimpleName() + " at pattern index " + startIndex
					+ " read `next` before it was wired (did you mean to call compile(next) first?)");
		}
		return n;
	}

	/**
	 * {@link #matcher}, for every read after this construct's {@link #compile} has run (or a
	 * MatcherConstruct constructor self-registered on it). As {@link #next()}, the field stays
	 * {@code @MonotonicNonNull} because matchers register themselves mid-construction to break cycles.
	 */
	final MatcherConstruct matcher() {
		MatcherConstruct m = matcher;
		if (m == null) {
			throw new IllegalStateException(getClass().getSimpleName() + " at pattern index " + startIndex
					+ " has no compiled matcher yet (did you mean to call compile(next) on it first?)");
		}
		return m;
	}

	/**
	 * Called only from MatcherConstruct's constructors, which register {@code this} while still under
	 * construction so that recursive references between nodes can be final fields (see
	 * MatcherConstruct's class doc). The checker rightly can't prove an under-construction value safe to
	 * store into an initialized object's field; it is safe here because nothing reads {@link #matcher}
	 * until that constructor returns.
	 */
	@SuppressWarnings("initialization.field.write.initialized")
	final void registerMatcher(@UnknownInitialization MatcherConstruct m) {
		matcher = m;
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
		buildEntryMap(next());
		entryPointState = ENTRY_POINT_CONSTRUCTED;
	}

	/**
	 * Mirror of {@code getEntryElse() != null}, used by {@link #mergeEntryPoints} instead of {@link
	 * #getEntryElse()} directly. This matters because {@link #getEntryElse()} forces the SAME full,
	 * cached {@link #buildEntryMap} that {@link #getEntryPointMap()} does (they share one {@link
	 * #ensureEntryPointBuilt} call) -- calling it on every candidate up front would force every
	 * candidate's entryMap to materialize, even ones {@link #mergeEntryPoints} otherwise wouldn't
	 * need to (see {@link #getEntryPointMap()}'s own call in that method). A candidate that's a
	 * pure leaf (e.g. {@code LiteralPatternConstruct}) or a pure alias ({@code SequencePatternConstruct}, {@code
	 * CaptureEndPatternConstruct}) overrides this to answer straight from whatever it aliases instead --
	 * skipping materializing its own {@code entryMap} purely to answer this one question.
	 *
	 * <p>Default just pulls through the ordinary cached, cycle-guarded {@link #getEntryElse()} --
	 * correct for any construct, and REQUIRED (not just correct) for the "real consumers" that
	 * actually own ambiguity-checked ranges of their own ({@code ComplexCharacterPatternConstruct}'s own ranges,
	 * and any {@code QuantifiedUnionPatternConstruct}/{@code ComplexQuantifiedCharacterPatternConstruct} that merges multiple
	 * candidates via {@code buildLoopEntryMap}): overriding those to answer without going through
	 * the cycle guard {@link #ensureEntryPointBuilt} provides would be wrong, since a quantified
	 * construct's own body can point its {@code next} right back at this same construct for a
	 * nullable loop (e.g. {@code (a?)+}) -- see {@code QuantifiablePatternConstruct.buildLoopEntryMap}'s
	 * {@code part.next = this}. Only override this for a construct that either has no recursion at
	 * all (a true leaf) or delegates to exactly one other, structurally-fixed construct (never
	 * blindly through {@code next}, unless whatever `next` might resolve to is itself guaranteed to
	 * still be state-checked -- see {@code QuantifiedUnionPatternConstruct}'s bare-flags-group override for the one
	 * case that does this safely).
	 */
	boolean claimsEntryElse() {
		return getEntryElse() != null;
	}

	/**
	 * Only meaningful once {@link #claimsEntryElse} is true: whether that catch-all is "the pattern
	 * may END here" ({@link EndPatternConstruct}, seen through whatever nullable/zero-width/marker
	 * constructs sit in front of it) rather than a construct that itself accepts any character (an
	 * unresolvable backreference). End-of-find is mode-dependent, exactly like {@link
	 * EndMatcherConstruct}: under {@code lookingAt()}/{@code find()} the match is
	 * complete as soon as it is reached, whatever code point follows, but under {@code matches()} only
	 * end of input completes it. That is why an end-of-find branch keeps its natural priority
	 * position in a union, behind a mode-aware gate (see {@link EndOfFindGateMatcherConstruct}),
	 * instead of being a lowest-priority tail fallback. Every override mirrors an entry-point
	 * construct that propagates {@code entryElse} from somewhere else; only called at matcher-build
	 * time, for a union that actually has a catch-all candidate.
	 */
	boolean elseIsEndOfFind() {
		return false;
	}

	/**
	 * Only meaningful once {@link #claimsEntryElse} is true: whether that catch-all is a residual
	 * one -- {@code .}, which claims "whatever its siblings don't" out of its own {@link
	 * #firstCharSet}, so {@code a|.} means {@code a|[^a]} and {@code .+b} means {@code [^b]+b} --
	 * rather than end-of-find or an unresolvable backreference's genuine "any character". Two
	 * residual claimants can't share a choice (which one gets the leftovers?), but one may share it
	 * with an end-of-find exit ({@code .*} at the end of a pattern): the loop body takes whatever it
	 * can and the exit takes what is left. Every override mirrors an {@link #elseIsEndOfFind} one.
	 */
	boolean elseIsResidual() {
		return false;
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
	public MatcherConstruct compile(PatternConstruct next) {
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
	 * fields that only {@code buildEntryMap()} populates (e.g. {@code QuantifiedUnionPatternConstruct}'s {@code
	 * compileTarget}/{@code rawEntryElse}). Override to {@code false} ONLY for
	 * a construct whose {@code buildMatcher()} reads nothing {@code buildEntryMap()} sets -- a true
	 * leaf like {@code LiteralPatternConstruct}/{@code ComplexCharacterPatternConstruct}, whose matcher is built entirely
	 * from their own constructor-supplied data. This is what lets such a leaf, when reached only as
	 * a merge candidate (via {@link #claimsEntryElse}), skip materializing its own {@link #entryMap}
	 * entirely -- otherwise {@code compile()}'s own unconditional {@code ensureEntryPointBuilt()}
	 * call (needed for every OTHER construct) would force that allocation right back, defeating the
	 * whole point of answering without it. Safe even for a leaf
	 * that participates in the entry-point cycle guard's graph, because a leaf's {@code
	 * buildEntryMap()} never reads {@code next} at all -- leaving it at {@code
	 * ENTRY_POINT_NOT_STARTED} after {@code compile()} can't corrupt anything a later, genuine pull
	 * (e.g. {@code SequencePatternConstruct.buildEntryMap}'s {@code getEntryPointMap()} call) would need; it just
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
	static PatternConstruct candidateAt(
			List<PatternConstruct> candidates, @Nullable PatternConstruct extra, int index) {
		return index < candidates.size() ? candidates.get(index) : castNonNull(extra);
	}

	static int candidateCount(List<PatternConstruct> candidates, @Nullable PatternConstruct extra) {
		return candidates.size() + (extra != null ? 1 : 0);
	}

	/**
	 * Merges {@code candidates}' own entry points (via {@link #getEntryPointMap}/{@link
	 * #getEntryElse}, not {@link #compile} -- see design.md's "Entry-point computation vs. matcher
	 * compilation" section), rejecting the first ambiguity: two candidates whose entry ranges
	 * overlap, or two candidates that both accept "any other character". Used for plain alternation
	 * ({@code candidates} = a union's branches, by way of {@code QuantifiedUnionPatternConstruct.buildEntryMap}) and
	 * for a quantified construct's own entry point ({@code candidates} = a loop's body parts, plus
	 * its own {@code next} when the loop can match zero times, by way of {@code
	 * QuantifiablePatternConstruct.buildLoopEntryMap}). See {@link #checkDisjoint} for the sibling case
	 * that needs the same ambiguity check but not the merged ranges themselves.
	 */
	/**
	 * Unions {@code candidates}' own entry points and picks out whichever one (at most one is
	 * allowed to) claims the any-other-character catch-all -- no ambiguity/overlap check here any
	 * more: that's now {@link #checkDisjoint}'s job, run separately against entry points alone
	 * (see {@link #buildFlattenedChain}/{@code QuantifiablePatternConstruct#buildLoopMatcher}, its own
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
	 * QuantifiablePatternConstruct.buildLoopEntryMap}'s own reason for existing: {@code next} joins the
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
		// Pre-sized (not the default single-slot capacity) from the candidates' own entry counts
		// summed -- an overestimate of the final merged size (merging can only coalesce entries
		// together, never split them further), so this is a real Arrays.copyOf regrow avoided, not
		// a guess. Plain ArrayCodePointSet, not a CodePointSetBuilder: a builder's extra sort/compact
		// pass measurably cost MORE than this construct's usual 2-3-candidate merge saved by skipping
		// ArrayCodePointSet#insert's binary-search-insert-with-shift (see notes.md's 2026-09-25 entry) --
		// pre-sizing alone, without that pass, is the part that's worth keeping.
		int capacityHint = rangeCountHint(candidateAt(candidates, extra, 0).getEntryPointMap());
		int candidateCount = candidateCount(candidates, extra);
		for (int i = 1; i < candidateCount; i++) {
			capacityHint += rangeCountHint(candidateAt(candidates, extra, i).getEntryPointMap());
		}
		MutableCodePointSet ranges = new ArrayCodePointSet(capacityHint);
		PatternConstruct elseCandidate = null;
		for (int i = 0; i < candidates.size(); i++) {
			elseCandidate = mergeOneEntryPoint(pattern, candidates.get(i), elseCandidate, candidateNounPlural, ranges, false);
		}
		if (extra != null) {
			elseCandidate = mergeOneEntryPoint(pattern, extra, elseCandidate, candidateNounPlural, ranges, true);
		}
		return new MergedEntries(ranges, elseCandidate);
	}

	/**
	 * An overestimate of {@code set}'s own entry count, for pre-sizing an {@link ArrayCodePointSet}
	 * about to absorb it (see {@link #mergeEntryPoints}/{@link #unionLastCharSet}) -- exact for the
	 * common case ({@code set} already an {@link ArrayCodePointSet}, whose {@code size} field is
	 * read directly, no iteration), and a real (if rarer) count via {@link CodePointSet#forEachRange}
	 * for any other {@link CodePointSet} implementation (e.g. a lazy {@code UnionCodePointSet}).
	 */
	static int rangeCountHint(CodePointSet set) {
		if (set instanceof ArrayCodePointSet) {
			return ((ArrayCodePointSet) set).size;
		}
		int[] count = {0};
		set.forEachRange((min, max) -> count[0]++);
		return count[0];
	}

	/** One candidate's own contribution to an in-progress merge (shared by {@link
	 *  #mergeEntryPoints}'s main-list loop and its {@code extra} candidate) -- unions its entry
	 *  point into {@code ranges} and returns the (possibly updated) else-candidate, throwing if
	 *  this candidate and an earlier one both claim the any-other-character catch-all. */
	static @Nullable PatternConstruct mergeOneEntryPoint(
			String pattern, PatternConstruct candidate, @Nullable PatternConstruct elseCandidate,
			String candidateNounPlural, MutableCodePointSet ranges, boolean isLoopExit) {
		if (candidate.claimsEntryElse()) {
			// Only for a loop's own exit (`extra`, never a union branch, whose end-of-find would be lost to
			// the residual tail): a residual body (`.` in `.*`) and its end-of-find exit coexist: the body claims
			// what it can and the exit gets the rest. The residual claimant stays the tracked one.
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
	static CodePointSet[] checkDisjoint(
			String pattern, int flags, List<PatternConstruct> candidates, @Nullable PatternConstruct extra,
			String candidateNounPlural) {
		return checkDisjoint(pattern, flags, candidates, extra, null, candidateNounPlural);
	}

	/**
	 * As the four-{@code List}/{@code PatternConstruct} overload above, but lets the caller override
	 * {@code extra}'s own entry set with {@code extraEntrySet} (used only for the overlap comparison
	 * below -- {@code extra} itself is still what an error message blames) -- see {@link
	 * #skipZeroWidthEntrySet}'s own doc for why {@code QuantifiablePatternConstruct.buildLoopMatcher} needs
	 * this and {@link #buildFlattenedChain} doesn't.
	 */
	static CodePointSet[] checkDisjoint(
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
	static void throwOverlapError(String pattern, PatternConstruct candidate, int candidateNumber,
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
	 * not whether an ambiguous pattern is still rejected. A separate experiment considered replacing
	 * {@code checkDisjoint} with on-demand conflict reporting to avoid entry-set allocation, but was
	 * closed without being implemented once this flattened-dispatch design landed: {@code
	 * checkDisjoint} itself is already allocation-free (pairwise {@link CodePointSet#intersects}, no
	 * accumulated union), and the entry sets it compares are the very ones handed to {@code
	 * dispatchEntrySet} below -- load-bearing for match-time dispatch, not just conflict detection --
	 * so there was no longer any conflict-check-only allocation left to eliminate. See notes.md's
	 * 2026-09-18 "entry-set-conflict-detection-without-allocation" entry.
	 *
	 * <p>{@code elseCandidate} is the construct {@code elseTarget} was compiled from (or {@code
	 * null}); only its explicit entry ranges are used, purely for the {@link #checkDisjoint} call.
	 *
	 * <p>{@code endOfFindCandidate} (a member of {@code candidates}, or {@code null}) is the one
	 * candidate whose catch-all is end-of-find; it stays in list order but behind an {@link
	 * EndOfFindGateMatcherConstruct}. Its explicit ranges are checked with the rest
	 * (it is in {@code candidates}), so {@code elseCandidate} is {@code null} in that case.
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
			@Nullable MatcherConstruct elseTarget,
			@Nullable PatternConstruct elseCandidate,
			@Nullable PatternConstruct endOfFindCandidate) {
		// elseCandidate (the catch-all branch, compiled separately as elseTarget and so absent from
		// `candidates`) still has to be ambiguity-checked against the others by its own explicit ranges,
		// if it has any -- it gets no gate of its own below, so `gates` past `count` is unused.
		CodePointSet[] gates = checkDisjoint(pattern, flags, candidates, elseCandidate, candidateNounPlural);
		int count = candidates.size();
		MatcherConstruct tail = elseTarget;
		for (int i = count - 1; i >= 0; i--) {
			PatternConstruct candidate = candidates.get(i);
			boolean lastUngated = (i == count - 1 && elseTarget == null);
			if (candidate == endOfFindCandidate && !lastUngated) {
				// Keeps its own list position (JDK alternation order): compiled ungated, behind a gate that
				// also admits end-of-find -- see elseIsEndOfFind.
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
	 * A zero-width vehicle for a single capturing loop body part's own entry gating -- see
	 * {@code QuantifiablePatternConstruct.buildLoopMatcher}'s own doc for why the gate has to live here,
	 * one level above the {@code BeginCaptureMatcherConstruct} it owns, rather than on the body
	 * part itself (which is compiled ungated and wrapped INSIDE the capture instead).
	 */

	/**
	 * A zero-width marker standing in for a {@code LoopMatcherConstruct} as a
	 * loop body's own compile target, so that node can be self-registered onto this marker BEFORE
	 * the body compiles against it (see {@code QuantifiablePatternConstruct.buildLoopMatcher}'s doc for why
	 * that ordering matters). Its own entry point IS queried, though -- a nullable construct nested
	 * inside the loop body (e.g. the {@code (a)?} in {@code (a)?+}) needs to look past itself at
	 * "what comes after me" while computing its OWN entry point (see {@code SequencePatternConstruct.buildEntryMap}'s
	 * {@code getEntryPointMap()} call for the general pattern), and what "comes after" a loop body is
	 * -- semantically -- the loop itself: looping back around re-enters exactly the same entry point
	 * {@code owner} already advertises externally. Delegating to {@code owner}'s own (by this point
	 * already-computed and cached, since {@code compile()} only reaches {@code buildLoopMatcher} after
	 * {@code owner}'s {@link #ensureEntryPointBuilt}) entry point is exactly right, and cheap.
	 */

	/**
	 * A second zero-width marker used by {@code QuantifiablePatternConstruct.buildLoopMatcher}, distinct
	 * from {@link LoopBackPatternConstruct} (whose own {@code .matcher} is already claimed by the {@code
	 * LoopMatcherConstruct} instance itself): this one's {@code .matcher} is where that same
	 * instance's "continue" successor -- the body-part-selection chain, not resolvable until after
	 * the loop body has compiled -- ends up, via an ordinary direct assignment once it's known. This
	 * reuses {@code PatternConstruct.matcher}'s own already-mutable-once-and-only-once nature as the
	 * one place a forward reference is allowed to resolve later, rather than adding any equivalent
	 * mutable field to {@code MatcherConstruct} itself (see {@code LoopMatcherConstruct}'s own doc).
	 * Never has its {@code buildEntryMap}/{@code buildMatcher} invoked -- nothing ever calls {@code
	 * compile()} on it -- so both just assert if ever reached.
	 */

	/**
	 * A zero-width marker inserted as a capturing group's branches' "next", so that an
	 * EndCaptureMatcherConstruct fires (recording the captured substring) right as the group's
	 * content finishes matching, before control actually reaches whatever follows the group. Has
	 * the same entry set as {@code realNext} -- inserting it must not change what characters are
	 * considered ambiguous for the group's branches.
	 */

	/**
	 * {@code \1}/{@code \k<name>}. {@code referencedGroup} is resolved at parse time (see
	 * PatternParser's {@code tryParseBackReference}) to the actual, already-fully-parsed
	 * {@code QuantifiedUnionPatternConstruct} the reference points at -- forward references and references to
	 * undefined groups are rejected there, before a BackReferencePatternConstruct is ever constructed. See
	 * design.md's "Backreferences" section for the full design.
	 */

	/**
	 * {@code \X} (extended grapheme cluster, UAX #29) -- consumes one full cluster starting at the
	 * current position, via {@link GraphemeCluster#nextBoundary}, a direct forward-only port of
	 * JDK 27's {@code jdk.internal.util.regex.Grapheme#nextBoundary}: it never looks more than
	 * forward from the current position, so it fits this engine's single-pass model without any
	 * new architectural capability (unlike {@code \b{g}}, deliberately left unimplemented for now
	 * -- see remaining_work.md -- since determining a grapheme BOUNDARY, rather than consuming a
	 * whole cluster, needs unbounded backward context: e.g. telling apart two adjacent regional
	 * indicators that continue one flag emoji from two adjacent ones that start a new one requires
	 * counting every regional indicator back to the last real boundary, not just looking at the
	 * immediately adjacent code points).
	 *
	 * <p>Like {@code .}, entry is universal -- but unlike {@code .} (which claims only "whatever a
	 * sibling branch doesn't"), {@code \X} claims EVERY code point explicitly, so it can never
	 * safely coexist with any other branch/loop-exit candidate (a plain union {@code a|\X} or a
	 * loop {@code \X*a} is rejected as ambiguous, same as {@code .+b} -- see README's "Intentional
	 * differences" list). This is the conservative, always-correct choice: a grapheme cluster's own
	 * width isn't statically known, so there's no way to carve out "whatever \X wouldn't otherwise
	 * claim" the way {@code .}'s residual else claim does.
	 */

	/**
	 * Entry point of a zero-width assertion ({@code \b}, {@code ^}, a lookbehind, ...): whatever can
	 * start what FOLLOWS it, since the assertion consumes nothing itself, so a union branch or loop
	 * part opening with one is ambiguity-checked against its siblings by the code points it can
	 * really start with (e.g. {@code \b[ab]c} starts with {@code a} or {@code b}), exactly like any
	 * other branch. The assertion only ever NARROWS when that branch can succeed at match time, so
	 * {@code next}'s own entry set is a sound (if not tight) gate. Catch-all ({@code entryElse}) is
	 * inherited from {@code next} only when {@code next} itself claims it (e.g. the end of the
	 * pattern), re-keyed onto {@code owner} like every other pass-through construct.
	 */
	static void buildZeroWidthEntryMap(PatternConstruct owner, PatternConstruct next) {
		owner.entryMap = next.getEntryPointMap();
		if (next.getEntryElse() != null) {
			owner.entryElse = owner;
		}
	}

	/**
	 * Shared base for the zero-width assertion construct types whose {@code skipZeroWidthEntrySet}
	 * override has the exact same shape -- fold in whatever {@link #admittedInteriorExitPeekSet}
	 * says a loop's interior exit through this assertion should treat as ambiguous, on top of
	 * unconditionally seeing through to {@code next}'s own entry set otherwise -- {@code
	 * WordBoundaryPatternConstruct}/{@code LineBoundaryPatternConstruct}/{@code LookbehindPatternConstruct}/{@code
	 * GraphemeBoundaryPatternConstruct}. ({@code BoundaryPatternConstruct} sees straight through with no
	 * "admitted" concept at all, so it stays a direct {@code PatternConstruct} subclass instead.)
	 *
	 * <p>EXPERIMENTAL (2026-09-27, project owner's idea -- see remaining_work.md/notes.md): merges
	 * what used to be four separate {@code skipZeroWidthEntrySet} overrides into one shared,
	 * {@code final} implementation here, so all four subclasses dispatch to the exact same compiled
	 * method rather than each having their own -- an attempt to reduce that call site's
	 * megamorphism (it still has several distinct override bodies system-wide -- this shared one,
	 * plus {@code BoundaryPatternConstruct}/{@code SequencePatternConstruct}/{@code QuantifiedUnionPatternConstruct}'s own, plus the base
	 * default -- just fewer of them). Not known in advance whether ART's inline caching actually
	 * benefits from this; measured, not assumed -- see notes.md for the result.
	 */

	/**
	 * {@code \b{g}} (grapheme boundary) -- unlike {@code \b}/{@code \B}, only the positive form
	 * exists (JDK 27 doesn't recognize {@code \B{g}} as special syntax either; see design.md).
	 * Always a real, match-time check -- like {@link LookbehindPatternConstruct}, there's no compile-time
	 * elision to a no-op or a compile error, since neither neighbor's grapheme-boundary-ness is
	 * ever fully statically known the way \b/\B's word-ness sometimes is.
	 */

	/**
	 * {@code ^} (line begin) / {@code $} (line end). Split out from {@link BoundaryPatternConstruct}
	 * (2026-09-07) for the same reason {@code \b}/{@code \B} were: a real, non-stub
	 * implementation with its own logic (MULTILINE-aware line-terminator scanning), distinct
	 * enough from {@code BoundaryPatternConstruct}'s remaining, still-unimplemented types that sharing
	 * one {@code BoundaryEnum}-keyed dispatch added indirection for no benefit. See design.md's
	 * "Boundary matching" section for the matching design.
	 */

	/**
	 * {@code \b} (word boundary) / {@code \B} (non-word-boundary). Split out from {@link
	 * BoundaryPatternConstruct} (2026-09-07) since these two are the only boundary types with an actual
	 * implementation, plus a compile-time optimization {@link BoundaryPatternConstruct}'s other types
	 * don't need -- see design.md's "Boundary matching" section for the full design.
	 */

	/**
	 * {@code (?<=X)}/{@code (?<!X)}, restricted to a body {@code X} that always matches exactly one
	 * code point -- a direct generalization of {@code \b}/{@code \B}'s own single-code-point {@code
	 * peekPrevious()} check (see design.md's "Boundary matching" section); wider lookbehind, and any
	 * lookahead, are permanently out of scope (can't be evaluated in O(1) per position). {@code
	 * lookSet} and {@code captureConstructIndex} are both fully resolved at parse time by {@link
	 * #resolveSingleCodePointBody} -- unlike {@code WordBoundaryPatternConstruct}, there's no neighbor
	 * context to wait for, so this construct needs no {@code buildEntryMap}-time classification step.
	 */

	/**
	 * The set of code points that could be the LAST one consumed if this construct matches here, if
	 * that's statically known regardless of runtime input -- used by WordBoundaryPatternConstruct's \b/\B
	 * compile-time optimization (see design.md's "Boundary matching" section) to classify the
	 * character immediately preceding a boundary as always/never a "word" character, the same way
	 * an ordinary entryMap already classifies the character immediately following one. Returns null
	 * ("not statically known") for anything that could match zero-width -- including a construct
	 * type with no override here -- rather than chasing what an earlier sibling might contribute in
	 * that case; that's always a safe fallback, just a missed optimization. Overridden by
	 * LiteralPatternConstruct/ComplexCharacterPatternConstruct/ComplexQuantifiedCharacterPatternConstruct/QuantifiedUnionPatternConstruct/SequencePatternConstruct -- see
	 * skipZeroWidthEntrySet's own doc for why a virtual method, not an `instanceof` chain.
	 */
	@Nullable CodePointSet lastCharSet() {
		return null;
	}

	static CodePointSet singletonCodePointMap(int codePoint) {
		MutableCodePointSet result = new ArrayCodePointSet();
		result.insert(codePoint, codePoint + 1);
		return result;
	}

	// Built once, not per call -- every call site only reads the result (union/insertAll/intersects,
	// never mutates it back), so there's no need to pay universalCodePointSet's own
	// set(0, MAX_CODE_POINT + 1) allocation-and-array-growth cost on every one of its callers'
	// calls (measured as a real compile-time cost for \X: see documents/notes.md's 2026-09-26
	// entry -- GraphemeClusterPatternConstruct.buildEntryMap calls this once per \X compiled, and adding
	// that huge a range from empty triggered enough ArrayCodePointSet growth to show up at ~9% of
	// sampled allocation weight in a corpus with real \X usage).
	static final CodePointSet UNIVERSAL_CODE_POINT_SET = buildUniversalCodePointSet();

	static CodePointSet buildUniversalCodePointSet() {
		CodePointSetBuilder result = CodePointSetBuilder.create();
		result.append(0, CodePointSet.MAX_CODE_POINT + 1);
		return result.build();
	}

	/** Every code point -- used by the {@code admittedInteriorExitPeekSet} methods below for the
	 *  "any peek could be ambiguous" case (e.g. a loop body with both word and non-word last
	 *  characters, against \b/\B). */
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
	 * The union of {@link #lastCharSet} over every candidate in a loop's own {@code body} list, or
	 * {@code null} if any candidate's own last-character set isn't statically known -- used only by
	 * {@code QuantifiablePatternConstruct.buildLoopMatcher}'s greedy-loop zero-width-assertion ambiguity
	 * check (see {@link #skipZeroWidthEntrySet}'s {@code checkAssertions} doc). Deliberately
	 * all-or-nothing (one unknown candidate gives up entirely, rather than unioning what the KNOWN
	 * candidates contribute) -- same conservative-fallback philosophy as {@code lastCharSet} itself.
	 */
	static @Nullable CodePointSet unionLastCharSet(List<PatternConstruct> body) {
		if (body.size() == 1) {
			// No copy needed: lastCharSet() always returns a fresh set (or an already-immutable one --
			// see its own call sites), and every caller of unionLastCharSet's result only ever reads it
			// (skipZeroWidthEntrySet passes it straight into an admittedInteriorExitPeekSet call, never
			// mutates it) -- the overwhelmingly common single-alternative loop body (`a+`, `\w*`) would
			// otherwise pay a whole insertAll-driven copy of a set it's about to discard anyway.
			return body.get(0).lastCharSet();
		}
		// lastCharSet() (unlike getEntryPointMap()) isn't cached -- each call does real recursive
		// work and returns a fresh set -- so every part's set is computed exactly once here, up
		// front, both to preserve that (a null anywhere still means "give up entirely") and so the
		// capacity hint below doesn't force a second call per part.
		CodePointSet[] partLastSets = new CodePointSet[body.size()];
		int capacityHint = 0;
		for (int i = 0; i < body.size(); i++) {
			CodePointSet partLast = body.get(i).lastCharSet();
			if (partLast == null) {
				return null;
			}
			partLastSets[i] = partLast;
			capacityHint += rangeCountHint(partLast);
		}
		// Pre-sized -- see mergeEntryPoints' own comment on why (plain ArrayCodePointSet, not a
		// CodePointSetBuilder). Note this hint can overshoot more here than in mergeEntryPoints:
		// unlike entry-point candidates, last-char sets aren't required to be disjoint, so the
		// summed count isn't as tight an overestimate -- still safe (never too small), just not as
		// exact.
		MutableCodePointSet result = new ArrayCodePointSet(capacityHint);
		for (CodePointSet partLast : partLastSets) {
			result.insertAll(partLast);
		}
		return result;
	}

	/**
	 * {@code pc}'s own entry point (see {@link #getEntryPointMap}), but seeing straight through any
	 * zero-width assertion ({@code BoundaryPatternConstruct}/{@code LineBoundaryPatternConstruct}/{@code
	 * WordBoundaryPatternConstruct}) to whatever actually determines which code points can follow --
	 * used ONLY by a loop's own ambiguity check ({@code QuantifiablePatternConstruct.buildLoopMatcher}'s
	 * {@link #checkDisjoint} call against its own {@code next}), never by ordinary union/dispatch
	 * construction. A loop's body, once it decides to continue, has already (irreversibly, since
	 * this engine never backtracks) consumed a code point -- so the real question for loop ambiguity
	 * is "could exiting the loop, possibly through one or more zero-width assertions, eventually
	 * require the SAME code point some body part would also accept," not "what does the very next
	 * AST node, in isolation, claim." An ordinary union's own dispatch never commits anything before
	 * a zero-width assertion's own runtime check can veto it, so gating on the assertion's ordinary
	 * see-through entry point ({@link #buildZeroWidthEntryMap}) is fine
	 * there -- see design.md's "Boundary matching" section -- but a loop can't afford that same
	 * latitude, since it has nowhere to backtrack to once it's consumed a character (see
	 * remaining_work.md's now-fixed "loop followed by a zero-width assertion" entry, e.g. {@code
	 * a*^a}).
	 *
	 * <p>Recurses into a {@code SequencePatternConstruct}'s first element and an unquantified {@code
	 * QuantifiedUnionPatternConstruct}'s own branches (unioning them), the same shape {@link #firstCharSet}/{@link
	 * #lastCharSet} use, so a boundary buried inside a nested group ({@code (^a)}) or alternation
	 * ({@code (^|x)}) is still seen through. Anything else (a quantified construct, {@code
	 * CaptureEndPatternConstruct}, a leaf) is returned via its own, already-correct {@link
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
	 * {@code WordBoundaryPatternConstruct#admittedInteriorExitPeekSet}/{@code
	 * LineBoundaryPatternConstruct#admittedInteriorExitPeekSet}). Only used for a plain greedy loop's own
	 * check (never reluctant, whose early exit is instead proven safe/unsafe at MATCH time by
	 * {@code MatcherConstruct#exitAssertionChain}, and never possessive, which -- like
	 * {@code java.util.regex}'s own possessive quantifier -- never backtracks either, so this
	 * engine's already-non-backtracking compilation can't newly disagree with it) -- see
	 * {@code QuantifiablePatternConstruct#buildLoopMatcher}.
	 */
	// Default: this construct claims its own entry point normally -- overridden by the handful of
	// zero-width-assertion/wrapper types below (WordBoundaryPatternConstruct, LineBoundaryPatternConstruct,
	// BoundaryPatternConstruct, LookbehindPatternConstruct, GraphemeBoundaryPatternConstruct, SequencePatternConstruct, QuantifiedUnionPatternConstruct)
	// that instead need to be "seen through" for a loop-exit ambiguity check. A plain virtual method
	// here, rather than the `instanceof` chain this replaced (2026-09-27): every OTHER construct
	// (LiteralPatternConstruct, ComplexCharacterPatternConstruct, etc. -- the common case) used to have to fail all 7
	// `instanceof` tests before reaching this same fallback, and each new zero-width construct type
	// added one more unconditional test to that chain (measured as a real, if small, ART-specific
	// compile-time cost for \X/\b{g} -- see documents/notes.md's 2026-09-26 entries). A virtual
	// dispatch costs the same O(1) regardless of how many construct types exist, and matches how
	// buildEntryMap/buildMatcher already dispatch per-type on this same class.
	CodePointSet skipZeroWidthEntrySet(boolean checkAssertions, @Nullable CodePointSet bodyLastCharSet) {
		return getEntryPointMap();
	}

	/**
	 * The set of code points that could be the FIRST one consumed if this construct matches here, if
	 * that's statically known regardless of runtime input -- the mirror image of {@link
	 * #lastCharSet}, used by {@code BackReferencePatternConstruct}'s compile-time entry-set computation (see
	 * design.md's "Backreferences" section): a backreference's possible first characters are
	 * exactly the referenced group's possible first characters. Returns null ("not statically
	 * known") for anything that could match zero-width, same safe fallback as {@code lastCharSet}.
	 * Overridden by the same construct types {@code lastCharSet} is.
	 */
	@Nullable CodePointSet firstCharSet() {
		return null;
	}

	/**
	 * Statically resolves this construct to "always matches exactly one code point, optionally
	 * wrapped in a single capturing group around the whole body" -- or {@code null} if it doesn't
	 * (e.g. more than one code point wide, optional/repeated, or more than one capturing group).
	 * Same recursive shape as {@link #lastCharSet}/{@link #firstCharSet} but stricter (needs total
	 * width exactly 1, not just "last/first character known") and threads a capture index too.
	 * Used only by {@code LookbehindPatternConstruct} to resolve a 1-code-point lookbehind body; overridden
	 * by the same construct types {@code lastCharSet}/{@code firstCharSet} are.
	 */
	public LookbehindPatternConstruct.@Nullable SingleCodePointBody resolveSingleCodePointBody() {
		return null;
	}

}

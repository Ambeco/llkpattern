# Remaining Work

Run `./gradlew :llkpattern:test` (with `JAVA_HOME` pointed at a JDK 17/21 — see [notes.md](notes.md)) to check the current state of the suite; see notes.md for dated pass/fail history rather than this file.

## Matcher dispatch

- [ ] **Known cost**: `foldedEntrySet`'s fold expansion is O(set size) per chain candidate under
      CASE_INSENSITIVE -- `(?iu)\p{L}+9` (a ~130k-codepoint class, quantified, under
      CASE_INSENSITIVE+UNICODE_CASE) measured ~60ms just to compile. Rare pattern shape (huge class
      + case-insensitivity combined), not exercised by the scraped corpus, but worth a targeted
      look (e.g. skip fold-expanding a class above some size and fall back to a runtime-folded
      check for just that candidate) if a real pattern like this ever shows up in profiling.
- [ ] **Known gap, not currently reachable**: `buildFlattenedChain`'s handling of a chain
      candidate that is BOTH the "any other character" catch-all (`rawEntryElse`) AND separately
      claims real, non-empty explicit ranges of its own would mis-order dispatch (its own explicit
      range's priority relative to siblings would be lost -- see the exclusion comment in
      `QuantifiedUnion.buildMatcher`). Not reachable today: the only field that could produce this
      shape, `ComplexCharacter.dotElse`, is never actually assigned anywhere in the codebase
      (confirmed via `grep -n "dotElse\s*="` -- always null in practice). If `dotElse` is ever
      wired up (e.g. for a DOTALL-variant `.`), this needs fixing first: give the else-candidate a
      `PassThroughMatcherConstruct` gated on its own explicit entry set at its natural chain
      position, in addition to (not instead of) the ungated node used as the tail fallback.

## Also remember for later (currently-unimplemented/deferred features)

- [ ] Once implemented, add the same depth of test coverage for: positive/negative lookahead
      (`(?=...)`/`(?!...)`) and lookbehind (`(?<=...)`/`(?<!...)`) -- note both are currently
      rejected outright at parse time per design.md.
- [ ] Remaining `java.util.regex.Pattern` compile flags: `UNIX_LINES` is only partially honored (it affects
      `^`/`$`/`\Z` but not which characters `.`/`\s`/etc. treat as line terminators), and `LITERAL` (treat
      the whole pattern as literal text) and `CANON_EQ` (canonical-equivalence matching) are not started: no tests
      and no stub `flags` branch.

## Scraped-corpus differential test harness

- [ ] More sources, each as its own `scrape_<source>.py` + golden file + `ScrapedCorpusTestBase`
      subclass (the pipeline already supports this cleanly):
  - [ ] **AOSP/libcore**: `https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/test/java/util/regex/`
        (note this is `libcore`, not `platform_frameworks_base`).
  - [ ] **RE2J**: `https://github.com/google/re2j/tree/master/javatests/com/google/re2j` --
        interesting as a comparison point since RE2J, like llk, is a deliberately linear-time
        (non-backtracking) engine, just via a different mechanism (Thompson NFA simulation vs
        LL(1) compile-time dispatch).
  - [ ] **dregex**: `https://github.com/marianobarrios/dregex/tree/master/src/test/java/dregex`.
  - [ ] Oracle GraalVM's regex engine tests were a candidate too -- not yet located/confirmed.
  - [ ] **dk.brics.automaton**: `https://github.com/cs-au-dk/dk.brics.automaton/tree/master/test/java/dk/brics/automaton`.
  - [ ] **DataDog/java-reggie**: `https://github.com/DataDog/java-reggie/tree/main/reggie-integration-tests/src/test/java/com/datadoghq/reggie/integration`.
- [ ] **Investigate java-reggie's `FuzzTest`** (`https://github.com/DataDog/java-reggie` --
      look under its integration-tests module) to see how it picks fuzzed inputs and decides
      pass/fail. This project considered a fuzz test for `Ll1Pattern` before and shelved it for
      exactly that reason: it wasn't clear which inputs to generate for an arbitrary pattern, or
      what the "correct" outcome even is without an oracle to compare against. java-reggie
      apparently found an answer worth copying -- read it before building anything, don't just
      copy the "fuzz" label.
- [ ] **Let `CorpusGenerator` refresh a golden file in place.** Today it only writes a whole file from an
      intermediate TSV, which discards the hand-triaged `status` tags, so refreshing a few rows means writing a
      throwaway class (see CLAUDE.md). Add a mode that reads an existing golden file, takes a selector for which
      rows to regenerate (e.g. a status prefix, a pattern regex, or row numbers), re-runs llk for them, and
      rewrites only those rows, leaving every other row untouched. A flag chooses whether to also re-run
      `java.util.regex` for the selected rows (when its recorded columns are suspect) or keep the recorded regex
      columns and refresh only the llk ones. `status` is recomputed only for regenerated rows.

## Scraped-corpus microbenchmark

- [ ] `jmhAllocSampling`'s fixed 3000-iteration count (`llkpattern/build.gradle`) was sized for
      `llkCompile` (yielded ~3000 `jdk.ObjectAllocationSample` events, a good sample size); the same
      count only yielded 135 events for `llkMatch` (it allocates far less per pass) -- still enough
      to show a clear dominant leaf (`Ll1Pattern.matcher` at 97.9%), but a `regexMatch`/`llkMatch`-
      specific higher iteration count would give finer resolution if that ever matters.

## On-device (Android) corpus benchmark

- [ ] If a device's `java.util.regex` disagrees with the golden files' recorded `regexMatchResult`
      (scraped on desktop), decide whether that's rare enough to ignore (the benchmark only times
      *speed*, not correctness, on-device) or common enough to need Android-specific golden columns
      or forked golden files -- not yet checked against a real device.
- [ ] Re-run the timing and sampling benchmarks on the other phones once convenient.

## Core implementation

- [ ] **Consider a parse-time check rejecting a quantified construct whose entire body is nullable** (e.g. `(a?)+`).
      Today only the entry-point-computation guard (design.md's "Entry-point computation vs. matcher compilation")
      catches it, as a compile-time `PatternSyntaxException`; `PatternParser` has no `nullable(construct)` recursion
      (a third sibling to `firstCharSet()`/`lastCharSet()`). A parse-time version would only improve the
      diagnostic (an earlier, more specific message), not correctness, since the guard already catches every case
      (`NestedQuantifierCombinatorialTest`).
- [ ] `MatcherConstruct.BoundaryMatcherConstruct.match(...)`: `InputBegin`/`InputEndExceptTerminator`/`InputEnd` (`\A`/`\Z`/`\z`) are implemented (position-and-surrounding-characters checks only, honoring `MULTILINE`/`UNIX_LINES` -- see design.md's "Boundary matching" section). `^`/`$` and `\b`/`\B` are separate, already-implemented pairs (`LineBoundaryConstruct`/`LineBoundaryMatcherConstruct`, `WordBoundaryConstruct`/`WordBoundaryMatcherConstruct`). `\G` isn't a position-based boundary at all and has no `MatcherConstruct` of its own -- see design.md's "Boundary matching" section.
- [ ] Remaining `Matcher` API gaps: `hitEnd`/`requireEnd` (need engine support) and `useAnchoringBounds`/`hasAnchoringBounds`, `useTransparentBounds`/`hasTransparentBounds` (see the `region()` item below) -- all still `UnsupportedOperationException` stubs. Also `find(int)` doesn't `reset()` first the way `java.util.regex.Matcher#find(int)` does, and a failed `find()` doesn't stay failed (the next `find()` restarts from `regionStart`).
- [ ] `region()`'s interaction with `hasAnchoringBounds`/`useAnchoringBounds`/`useTransparentBounds` (whether `^`/`$`/boundaries see past the region) isn't implemented at all yet -- `^`/`$`/`\A`/`\Z`/`\z`/`\b`/`\B` all currently hard-code the "opaque bounds" behavior (never look past `regionStart`/`regionEnd`), which is `useAnchoringBounds(true)`/`useTransparentBounds(false)`'s combination (the default) but not configurable to the other three.
- [ ] `PatternConstruct.compile()` is typed `@Nullable MatcherConstruct` but, now that every construct type actually builds a matcher, likely always returns non-null in practice — worth dropping the `@Nullable` (and fixing `Ll1Pattern.compile()`'s unchecked-nullable assignment).
- [ ] `PatternSyntaxException.Reference` is constructed in a couple of places (e.g. the old, since-rewritten ambiguity-detection attempt) but was never actually handled in `PatternSyntaxException.throwWithReferences` — it silently falls through to `Object.toString()` (`Reference@<hashcode>`). Either implement it (render the referenced snippet, as `CodePoint`/`CodePointReference` do) or remove it if `CodePoint`-based messages turn out to be sufficient. Current loop/union ambiguity messages avoid it, using plain indices/`CodePoint` instead.
- [ ] **Bracket-embedded named classes still merge, unlike a standalone escape.** Now that
      `ComplexCharacter.ranges` is immutable and `PatternParser#parseComplexEscape` is a pure
      function, a standalone escape atom (a bare `\D`/`\p{...}` in running pattern text) assigns
      the resulting `CodePointMap` straight into `ComplexCharacter.ranges` with no copy at all. A
      named class used *inside* a bracket expression (e.g. `[\d\s]`, `[a\D]`) still goes through
      `ranges.putAll(...)` (now `ArrayCodePointMap#putAll(ArrayCodePointMap)`'s optimized overload,
      not a per-entry merge -- see that method's own doc), which is a real sorted-sweep merge, not
      an alias. Worth revisiting if profiling shows it matters: e.g. special-casing "the bracket's
      `ranges` local is still empty" to alias instead of merge, same trick the standalone-escape
      site already uses.
- [ ] **`CodePointMap#forEachRange` isn't used everywhere `entrySet()` still is.** It visits ranges
      as primitive `int`/`value` triples with no `Range`/`Entry`/`Iterator` allocated per range (for
      `ArrayCodePointMap`'s common `elseValue == null` case -- see its own doc). `PatternParser`'s
      `intersect` helper and `ArrayCodePointMap#putAll`'s internal sweep (`sweepMerge`) already use
      it, and `#intersection`/`#equals` were rewritten to skip `entrySet()`/`forEachRange` entirely
      (direct raw-array reads, since both operands are known to be `ArrayCodePointMap` there). Still
      unconverted: `TreeCodePointMap`'s own methods (low priority -- differential-test oracle only,
      not a production path), and every `PatternConstruct`/`MatcherConstruct` loop that walks an
      entry map while building the matcher/dispatch graph (`addCodePointsTo`, `buildEntryMap`, the
      `MultiDispatchingMatcherConstruct` builders, etc. -- `grep -n '\.entrySet()'
      llkpattern/src/main/java` finds them all). Most of those are on the `llkCompile` hot path per
      this session's own profiling history, so likely worth a dedicated pass rather than
      opportunistic conversion -- large enough in surface area to be its own session rather than
      folded into whatever prompted this item.
- [ ] **`ArrayCodePointMap`/`TreeCodePointMap` immutable+builder split**: floated in the original
      design sketch for the array-backed map, but neither implementation actually has this split
      today (both are mutable-only) -- worth doing for both together if immutability is ever
      wanted, rather than giving only the newer class a shape the older one lacks.
- [ ] **Followup experiment** for `ArrayCodePointMap`: shrink the range field to 10 bits and use the
    freed 11th bit as a mask-vs-range flag. When set, the 10 "range" bits are instead a bitmask of
    which of the 10 code points *after* `min` also map to this value (not required to be
    contiguous) -- lookup then has to branch on the flag and, on a mask hit, may need to check up
    to 11 candidate keys in a row (since a mask entry no longer implies contiguous coverage the
    way a range entry does), so it trades lookup speed for density. Good fit for
    alternating-but-not-contiguous data (e.g. `isLowerCase` over `0x100`-`0x137`, which alternates
    upper/lower every code point and would otherwise need one range entry per code point). Also
    worth trying a `long[]` variant with 42 range/mask bits instead of `int[]`'s 11, trading larger
    per-entry size for fewer wasted bits when ranges/gaps are long.

## Shrink `UnicodePredicates` (idea from the project owner, 2026-09-19)

Measured 2026-09-19 (JDK 17, cold): `UnicodePredicates` is 561 `CodePointSet` fields (22 `Character`
predicates, 201 categories/scripts, 338 blocks) holding 13,640 ranges total; a 316KB class file; ~14ms
first-touch (~4ms load, ~3ms verification, ~7ms running 561 tiny init methods -- mostly one-time
class-loading overhead, not compute); ~109KB heap afterward (~55KB of that is the raw range data at 4
bytes/range, the rest per-set object/array overhead). One-time cost, so not urgent -- only worth doing
to keep it "vaguely reasonable" and the jar/dex small.

- [ ] **Step 1: pack all the ranges into one binary blob plus an index.** The generator concatenates
      every set's `int[]` internals (`ArrayCodePointSet`'s own packed `(min<<11)|count` format, ~55KB
      total before compression) into one big buffer, and writes a second buffer mapping each predicate
      to its slice (offset/length). Both live as jar resources (or, to avoid needing resource loading
      at all -- relevant on Android -- as `String` constants in a generated class, split into <64KB
      pieces since a class-file string constant is capped at 65,535 modified-UTF-8 bytes). Each
      predicate becomes a thin set built on demand from its slice. Should collapse the class file,
      verification, and static-init cost, since there'd be no per-set bytecode at all.
      Design questions to settle before building: (a) lookup speed matters more than init speed, and
      it is UNMEASURED whether a slice view would be slower than `ArrayCodePointSet`'s plain `int[]`
      indexing -- the bounds check is about the same (a heap `IntBuffer.get` also checks `limit`), but
      `IntBuffer` adds `offset`/`hb` field loads and an abstract-class call the JIT may not inline
      (ART, on Android, inlines less than HotSpot's C2, so the Pixel 3a is where a gap is likelier),
      and a `ByteBuffer.asIntBuffer()` view over a byte blob adds byte-order handling on top. Options,
      cheapest first: (i) one shared `int[]` blob with each thin set holding `(offset, length)` and
      indexing `blob[offset + i]` -- plain array indexing, no buffer abstraction, but needs a small new
      `CodePointSet` implementation (or making `ArrayCodePointSet`'s search work over array+offset)
      and keeps the whole ~55KB blob alive; (ii) an `IntBuffer` slice per set; (iii) copy the slice
      into a real `ArrayCodePointSet` on first use and cache it (zero match-time cost, small
      per-used-set init cost). Settle it with a throwaway JMH microbenchmark (per the "narrow
      hypothesis" guidance in CLAUDE.md), not the full corpus cycle: `contains` on the current
      `ArrayCodePointSet` vs (i) vs (ii) on a large set such as `isDefined`, on the desktop AND the
      Pixel 3a; (b) resource loading via `getResourceAsStream` needs checking on the Pixel 3a / APK
      packaging, and its failure mode should be a loud, detailed exception per this project's
      error-message conventions.
- [ ] **Step 2 (after step 1): replace the 561 members with an enum** (or an ordinal-indexed table) and
      one method that materializes the set for a given value on the fly. Fits the existing name lookups
      (`NamedCharClass#scriptByName`/`#blockByName`, currently generated string switches) and lets
      nothing be built until asked for. Supersedes the simpler "separate generated classes per family
      (`UnicodeBlocks`/`UnicodeScripts`) so they only load when used" idea, which only defers the cost
      instead of removing it.
- [ ] Both steps change the generator (`UnicodeAnalyzer`) output format, so re-run the regeneration
      (see notes.md's 2026-09-19 entry) and the full suite afterward, and re-measure class size, init
      time and heap with the same numbers as above.

## Toolchain

- [ ] Pin a Checker Framework version compatible with modern JDKs (or a JDK toolchain constraint) and re-enable the nullness checker in `llkpattern/build.gradle` — currently disabled because the default-resolved 3.19.0 crashes against JDK 25's javac internals.
- [ ] Consider bumping the Gradle wrapper (currently 8.7) so it can run on newer JDKs directly, instead of requiring `JAVA_HOME` to point at JDK 17/21. Check compatibility with the Android Gradle Plugin used by `app/` first.

## Testing

- [ ] Add more parser tests covering boundaries and backreferences syntax once those are implemented — see "Also remember for later" above.
- [ ] Cross-check current `Matcher` behavior against `java.util.regex.Pattern`/`Matcher` for the subset of syntax both support beyond what's already asserted from first principles — see the scraped-corpus harness above for the systematic version of this.
- [ ] Decide on a CI setup (or at least a documented local command, given the JDK version constraint above) to run the suite "frequently" per the owner's stated preference.

## Housekeeping / cleanup

- [ ] Clarify the relationship between `llkpattern/` (current), `oldllkpattern/` (prior version, kept for reference) — is `oldllkpattern` still needed, or can it be removed/archived once the new implementation catches up?
- [ ] Fill in section 2 (High-Level Design) and section 3 (Current Progress) of [README.md](../README.md) in more depth as the design solidifies (still not a full design writeup in the README itself, which continues to point at design.md).

## Open Questions

- [ ] **Reluctant/possessive quantifiers' permanent semantics**: the parser currently accepts and no-ops `?`/`+` quantifier modifiers (per its own comment, "reluctant and possessive quantifiers are no-ops in this Pattern"). Confirm this is the intended permanent semantic (i.e., this engine has one matching behavior, and the reluctant/possessive distinction from `java.util.regex` doesn't apply here) and document it prominently for users migrating from `java.util.regex`, rather than leaving it as an implicit consequence of "no backtracking."

## `PatternParser` codepoint-array indexing

- [ ] **Convert `PatternParser`'s `index` from a char index into `pattern` to a codepoint index into a decoded
      `int[]`**, to skip `Character.charCount`/surrogate math in the per-character scan loop. Not a small tweak:
      `index`/`startIndex`/`endIndex` are used 100+ times, including every `PatternSyntaxException`'s char-accurate
      position (must keep matching `java.util.regex`'s char-offset contract), every `pattern.substring(...)`, and the
      three `CharBuffer.wrap(pattern, ...)` zero-copy literal sites. Design: a parallel `int[] charOffsets`
      (codepoint index -> char index), built in one pass with the decode and translated through at every
      span/substring/error site.
- Tried exactly this on 2026-09-14 and reverted (notes.md): correct, but desktop `llkCompile` regressed ~5-8%
  with ~10% more allocation, because the two per-compile `int[]`s cost more than this corpus's short patterns
  ever saved. Only worth revisiting for a corpus of much longer patterns, or a single-array encoding (char offset
  packed into unused high bits of each codepoint slot). Measure before keeping.

## `firstCharSet`/`lastCharSet` follow-ups

- [ ] **`BackReference` aliasing its referenced group's own entry point directly**, instead of going through the separate `firstCharSet`/`lastCharSet` static-walk helpers -- proposed this session, NOT done: `firstCharSet(referencedGroup)` and `referencedGroup.getEntryPointMap()` diverge for a nullable referenced group (the latter folds in `next`'s entries via `buildLoopEntryMap`'s `min == 0` case, and can throw `EntryPointCycleException` on a pattern that compiles fine today), so this needs verifying against `(a?)\1` and `(a|b)?\1` before landing, not just assumed safe.
- [ ] `singletonCodePointMap` (used by `firstCharSet`/`lastCharSet`, and stale-named -- it's a `CodePointSet` now, not a `CodePointMap`) and the `QuantifiedUnion`-branch-union temporary sets inside those two methods are still real, un-eliminated small allocations -- left alone this session per the item above (converting `lastCharSet`'s callers to a push model isn't viable; `firstCharSet`'s one call site might be, see above, but wasn't converted). Worth renaming `singletonCodePointMap` to `singletonCodePointSet` while touching this.

## Remaining desktop-allocation-sampling leaders

Found via `:llkpattern:jmhAllocSampling`. Each has a bigger blast radius than the throwaway per-bracket
`CodePointSetBuilder`, so each needs its own dedicated look. Before touching any of them, read the
`CodePointSetBuilder` entry in notes.md: small-N accumulation sites have repeatedly regressed when converted to a
builder or pre-sized.

- [ ] **`ArrayCodePointSet`'s own eager `keys` array** (~6% of sampled allocation weight) has the
      same "allocated even when the set ends up empty" shape `CodePointSetBuilder` had, but
      `ArrayCodePointSet` is a much more central, widely-used class -- implements the shared
      `CodePointSet`/`MutableCodePointSet` interfaces, used at match time as well as parse time,
      and has many more call sites (`add`/`addRange`/`complement`/`forEachRange`/`size`/`invert`/
      the shared static `EMPTY_ENTRY_MAP` instance in `PatternConstruct`). Making `keys` lazy would
      need every reader path to handle a null/absent array correctly, not just the two methods
      `CodePointSetBuilder` needed -- worth doing, but as its own careful pass with full
      before/after benchmarking, not bundled in with a smaller, throwaway-class fix.
- [ ] **`Sequence`/`QuantifiedUnion`'s default-capacity `patterns`/`constructs` `ArrayList`s**
      (`ArrayList.grow`, ~7% of sampled allocation weight) -- both are plain `new ArrayList<>()`,
      so the first `#add` grows to Java's default capacity of 10 regardless of how many elements
      the sequence/union actually ends up holding (often far fewer for typical corpus patterns).
      Pre-sizing to a smaller initial capacity (e.g. 4, matching `CodePointSetBuilder`'s own
      `INITIAL_CAPACITY`) would shrink the allocated array without eliminating the allocation
      itself -- measure whether that's worth doing before implementing, per this file's existing
      "measure before keeping" guidance a few sections up (a plausible-sounding pre-sizing
      heuristic has already twice measured as a net regression in this project).

- [ ] **`PatternParser.parse`'s own `PatternConstruct` allocation is ~18% of sampled allocation
      weight** -- every `QuantifiedUnion`/`Sequence` node gets allocated eagerly as the parser
      descends, even for AST shapes that could plausibly be deferred or elided (e.g. a `Sequence`
      wrapping a single element, or a `QuantifiedUnion` that turns out to be unquantified with
      exactly one branch and no capture -- both common). Worth investigating whether some of these
      can be built lazily (only materialized if something downstream actually needs the wrapper,
      rather than unconditionally on the way down) or elided entirely for the trivial-wrapper case.
      Not attempted yet -- this is parse-time AST structure, not the compiled matcher graph the
      `flatten-matcher-dispatch` experiment touches, so it's an independent effort; likely large
      enough in surface area (`PatternParser`'s whole recursive-descent structure assumes eager
      construction) to warrant its own dedicated session per this file's usual guidance, not a
      quick opportunistic change.
- [ ] **`PatternParser#parseComplexCharacterRanges`'s `negate` handling** calls a separate
      `ArrayCodePointSet#complement` (a full array copy) on its already-built result when a bracket
      expression starts with `^`. `CodePointSetBuilder` gained an `#invert` method (2026-09-18,
      currently uncalled) specifically so a builder could bake inversion in directly instead --
      would need `negate` threaded through `intersect`/`mergeRun`'s own call chain first. Not
      attempted yet.
- [ ] **`PatternConstruct#mergeEntryPoints` restoring its old pre-sizing** -- it used to
      `ensureCapacity` its result from a known entry count (`mergeEntryPointsRaw`'s
      `CodePointMapBuilder`) before that source was deleted as an unrelated side effect of the
      `checkDisjoint`/ambiguity-check refactor (see notes.md's 2026-09-14 entries), leaving today's
      version unsized. A prior attempt at restoring this (summing each candidate's own entry-set
      size) was rejected specifically because it would have forced an `entryMap` materialization
      `addCodePointsTo` existed to avoid -- but `addCodePointsTo` was ALSO removed in that same
      refactor, so that objection may no longer hold. Worth a fresh look, with the "measure before
      keeping" caution above firmly in mind (`mergeEntryPoints` is likely a small-N call site same
      as the three rejected `CodePointSetBuilder` conversions, so a `CodePointSetBuilder`-based fix
      specifically is NOT the presumed answer here -- a pre-sized `ArrayCodePointSet` is more likely
      the right shape, same as before the `mergeEntryPointsRaw` deletion).

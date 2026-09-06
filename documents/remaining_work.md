# Remaining Work

Updated 2026-09-06. `./gradlew :llkpattern:test` (with `JAVA_HOME` pointed at a JDK 17/21 — see [notes.md](notes.md)) compiles and passes: 28 tests, 0 skipped.

## Done

- [x] `CodePointMap`/`TreeCodePointMap`: interface fixed (consistent `[min, max)` convention, immutable `Range`, dropped broken/duplicate inline implementations), `TreeCodePointMap` rewritten against it and covered by 23 tests. `intersectionRejectingConflicts` added for the union-ambiguity-detection use case.
- [x] Fixed `UnicodePredicates.java`'s "code too large" compile error by changing the `unicodeanalyzer` generator to emit each field's builder chain as its own private static method (keeping the shared `<clinit>` small) and regenerating the file.
- [x] Toolchain: identified that Gradle 8.7 doesn't run reliably on this machine's default JDK 25 — use JDK 17/21 via `JAVA_HOME`. Checker Framework plugin disabled (incompatible with JDK 25) pending a version bump.
- [x] **AST → `MatcherConstruct` compilation, for the non-repeating subset**: `PatternConstruct.compile()`/`buildEntryMap()`/`buildMatcher()` now actually build a working matcher graph for literals, character classes, sequences, and plain (unquantified) alternation — see design.md's "compile() algorithm" section. `MatcherConstruct`'s self-registering-constructor cycle-handling mechanism is implemented (not yet exercised by a real cycle, since loops aren't compiled yet).
- [x] `QuantifiedUnion.buildEntryMap` implements real ambiguity detection (the core LL(1) restriction) for plain alternation, with a `PatternSyntaxException` naming the conflicting branch and characters.
- [x] Found and fixed three real, previously-latent `PatternParser` bugs while building tests against the above (all pre-existing, not introduced by this work): `advanceCodePoint()` double-advancing past every character after the first (it added `offsetByCodePoints`'s return value, which is already an absolute index, as if it were a delta); `parseComplexCharacter()` never consuming the closing `]`; `parseUnion()`'s raw-text accumulation appending the wrong (post-advance) character.
- [x] `PatternParserTest` rewritten with 5 real, passing tests (single literal, multi-char literal, sequence of character classes, alternation, ambiguous-alternation rejection) replacing the one `@Ignore`d test from the previous session.

## Core implementation

- [ ] **Next up**: implement quantifier/loop compilation — `QuantifiedUnion.buildEntryMap`/`ComplexQuantifiedCharacter.buildEntryMap` currently throw `UnsupportedOperationException` whenever `min != 1 || max != 1` (i.e. an actual `?`, `*`, `+`, or `{n,m}`). Needs a `LoopMatcherConstruct`/`EndLoopMatcherConstruct` pair, folding `next`'s entry set into the construct's own entryMap when `min == 0` (entering zero times), and is the one case that actually exercises the self-registering-constructor cycle-handling mechanism (a loop body's "next" is the loop construct itself, not-yet-fully-built). See design.md's Open Questions.
- [ ] Implement `MatcherConstruct.BackReferenceMatcherConstruct.match(...)` (currently throws; the node itself is now correctly wired into the graph, just the runtime behavior is missing).
- [ ] Implement `MatcherConstruct.BoundaryMatcherConstruct.match(...)` (currently throws; same as above — structurally present, behaviorally stubbed). This needs matcher *state* (position, surrounding characters), not just the next code point, so it may need a different mechanism than a plain dispatch map — see design.md.
- [ ] Implement the bulk of `Matcher`'s public API (`find`, `matches`, `lookingAt`, `group(...)`, `start(...)`, `end(...)`, `replaceAll`/`replaceFirst`/`appendReplacement`/`appendTail`, `toMatchResult`, `hitEnd`, `requireEnd`, `useAnchoringBounds`/`hasAnchoringBounds`, `useTransparentBounds`/`hasTransparentBounds`, `quoteReplacement`, `beginCapture`/`endCapture`) — currently `UnsupportedOperationException` stubs. This is also where `find()`'s unanchored scanning needs to be reconciled with the "start at the compiled root node" design (see design.md).
- [ ] `PatternConstruct.compile()` is typed `@Nullable MatcherConstruct` but, once every construct type actually builds a matcher, may always return non-null in practice — worth dropping the `@Nullable` (and fixing `Ll1Pattern.compile()`'s unchecked-nullable assignment) once that's true.
- [ ] `PatternSyntaxException.Reference` is constructed in a couple of places (e.g. `QuantifiedUnion`'s old ambiguity-detection attempt) but was never actually handled in `PatternSyntaxException.throwWithReferences` — it silently falls through to `Object.toString()` (`Reference@<hashcode>`). Either implement it (render the referenced snippet, as `CodePoint`/`CodePointReference` do) or remove it if `CodePoint`-based messages turn out to be sufficient.
- [ ] Migrate `ComplexCharacter`'s direct Guava `RangeSet<Integer>` usage onto `CodePointMap`, or decide it should stay separate (`ComplexCharacter` represents a single character class's ranges, which is a slightly different job than `CodePointMap`'s "ranges to values"; worth a deliberate decision rather than reflexive migration).
- [ ] Eventually replace `TreeCodePointMap`'s Guava `TreeRangeMap` delegation with a more specialized/optimized code-point range structure — explicitly called out by the project owner as a later step, not needed for a first working version. The `CodePointMap` interface exists specifically so this swap doesn't require touching callers.
- [ ] `CodePointMap.ComplementCodePointMap` is only partially implemented (`entrySet`/`intersection`/`intersectionRejectingConflicts` throw `UnsupportedOperationException`) — fill in once there's a concrete caller/use case driving what's actually needed (`.` in a branching context is the likely first caller).
- [ ] The `QuantifiedUnion.buildEntryMap` ambiguity check (`findFirstOverlap`) is an O(branches × ranges) manual scan rather than using `CodePointMap.intersectionRejectingConflicts` directly, because the latter's exception doesn't carry which range/branch conflicted. Fine for realistic pattern sizes; revisit only if it matters in practice.

## Toolchain

- [ ] Pin a Checker Framework version compatible with modern JDKs (or a JDK toolchain constraint) and re-enable the nullness checker in `llkpattern/build.gradle` — currently disabled because the default-resolved 3.19.0 crashes against JDK 25's javac internals.
- [ ] Consider bumping the Gradle wrapper (currently 8.7) so it can run on newer JDKs directly, instead of requiring `JAVA_HOME` to point at JDK 17/21. Check compatibility with the Android Gradle Plugin used by `app/` first.
- [ ] Add a documented/scripted way to run `unicodeanalyzer` and regenerate `UnicodePredicates.java`, rather than the current copy-paste-and-hand-assemble process used to fix the "code too large" bug.

## Testing

- [x] `TreeCodePointMapTest` — 23 tests covering the range convention, core map operations, union/difference/intersection, conflict detection, `compute`/`computeIfAbsent`, `complement`, `equals`, and the copy constructor.
- [x] `PatternParserTest` — 5 tests covering literal/multi-char-literal/sequence/alternation compilation and ambiguous-alternation rejection.
- [ ] Add more parser tests covering the documented grammar (groups, quantifiers, character classes/intersection/negation, escapes, boundaries, `\p{...}` Unicode classes) and its error cases (`PatternSyntaxException`s) — coverage is still thin relative to the grammar's size.
- [ ] Add compiler tests for quantifier/loop compilation once implemented — both the positive case (should compile and match correctly) and negative cases (ambiguous loop-exit conditions should throw a clear `PatternSyntaxException`).
- [ ] Add matcher/end-to-end tests once `Matcher`'s public API is implemented, ideally cross-checked against `java.util.regex.Pattern`/`Matcher` behavior for the subset of syntax both support.
- [ ] Decide on a CI setup (or at least a documented local command, given the JDK version constraint above) to run the suite "frequently" per the owner's stated preference.

## Housekeeping / cleanup

- [ ] Clarify the relationship between `llkpattern/` (current), `oldllkpattern/` (prior version, kept for reference) — is `oldllkpattern` still needed, or can it be removed/archived once the new implementation catches up?
- [ ] Clarify what the `app/` Gradle module (looks like default Android app boilerplate) is for in this project — is it a demo/harness, or leftover scaffolding from `File > New Project` that can be deleted?
- [ ] Fill in section 2 (High-Level Design) and section 3 (Current Progress) of [README.md](../README.md) in more depth as the design solidifies (updated 2026-09-06; still not a full design writeup in the README itself, which continues to point at design.md).

# Working Notes (for Claude)

Notes to self about how to work on this project, and other context that doesn't belong in the README or design docs.

## Build / environment

- **JDK: use 17 or 21, not 25.** This machine's default `java` is JDK 25, but Gradle 8.7 (this project's wrapper version) can't run on it — the Groovy buildscript compiler crashes intermittently with `Unsupported class file major version 69`. Set `JAVA_HOME` to a JDK 17 or 21 install for any Gradle invocation, e.g. (bash) `export JAVA_HOME="C:\Program Files\Java\jdk-17"`. Don't hardcode this into the committed `gradle.properties` (machine-specific path) — a real fix would be a Gradle toolchain declaration or bumping the Gradle wrapper version, tracked in remaining_work.md.
- The Checker Framework nullness-checking plugin is currently disabled in `llkpattern/build.gradle` (commented out, `apply false` in the `plugins{}` block) because it crashes with `NoSuchMethodError` against JDK 25's internal javac APIs even when the rest of the build uses JDK 17/21 correctly — its own default-resolved version (3.19.0) predates JDK 17-era javac internals it pokes at reflectively. Needs a compatible version pinned before re-enabling, not a permanent removal.
- `./gradlew :llkpattern:test` (with JAVA_HOME set as above) is the command to run the test suite. As of 2026-09-06 it passes: 48 tests, 0 skipped; as of 2026-09-07 (end of that day's session): 1398 tests, 0 failing, 561 skipped (the scraped-corpus harness accounts for 561 golden rows x ~2 tests/row -- see remaining_work.md's scraped-corpus section).
- **Android API floor: 26, not just whatever `llkpattern/build.gradle`'s `sourceCompatibility`/`targetCompatibility = VERSION_1_8` implies.** Learned 2026-09-07 while implementing a `MethodHandle.findSpecial`-based optimization: the project owner rejected `MethodHandles.privateLookupIn` (Java 9+) specifically because it's Android API 33+, and API 26 (~Java-8-core-library-level `MethodHandles`) is worth "over half of live Android devices" vs. under half on API 33 — so any `java.lang.invoke`/newer-JDK API usage in `llkpattern` needs to be checked against API 26, not just against whatever the `sourceCompatibility` constant says (that constant predates this being asked explicitly and may not itself reflect a considered Android floor — worth confirming with the project owner rather than assuming, if it ever comes up again). Note `app/build.gradle` currently declares `minSdk 19`, even below 26 — per the existing "app/ looks like unmodified boilerplate" note below, that's likely just an unrevisited `New Project` default, not a deliberate floor.

## Process preferences

- The user (Ambeco) wrote the original base code; I've taken over implementation under their guidance. Don't assume I know the historical reasoning behind existing code — ask if it's unclear rather than guessing.
- Make reasonable, incremental commits as we progress, rather than one giant commit at the end.
- The user highly values comprehensive automated tests, run frequently. As of 2026-09-05 there is a real, passing test suite (`./gradlew :llkpattern:test`, JDK 17/21 required — see above) — keep it green and keep adding to it as each piece of the compiler gets implemented, rather than letting it lag behind.
- Default branch name for any new git repo: `main`, not `master`.
- When a task turns out to require unblocking something bigger than expected (e.g. "finish CodePointMap" led to fixing a toolchain crash and a generated-file compile error before any test could even run), pause and ask before doing large mechanical surgery (like patching a 13k-line generated file) rather than assuming — see the 2026-09-05 session's `AskUserQuestion` about the `UnicodePredicates.java` "code too large" error, where the user preferred fixing the generator over a quick patch or skipping it.

## Project history / state as of 2026-09-05

- The user hadn't touched this project in a couple of years before this session. Git repo did not exist yet — initialized fresh (`main` branch) on 2026-09-05, first commit imports everything as it was found on disk.
- Per the user: they were in the middle of two simultaneous refactors when they stopped, which they've noted (with hindsight) was probably too much to juggle at once and contributed to stalling out:
  1. Compiling the parsed AST (`PatternConstruct` tree) into a graph of executable matcher nodes (`MatcherConstruct`). This is partially done — `PatternConstruct.QuantifiedUnion.buildEntryMap` (the union/loop ambiguity-detection logic) is left mid-edit with syntax errors.
  2. Refactoring the character-class representation away from mirroring the pattern's own literal syntax, toward a `RangeSet`-based representation (`CharacterClass`/`CodePointMap`/`TreeCodePointMap`), with the stated eventual intent to replace that with something more specialized/optimized once the shape is proven out. This is also mid-flight — the old direct-`RangeSet`-on-`ComplexCharacter` approach and the new `CodePointMap` family currently coexist.
- Clarified direction (2026-09-05): the user confirmed the plan is/was to finish #2 (the `CodePointMap<V>` interface, with `TreeCodePointMap` as the initial `TreeRangeMap`-backed implementation, and a more specialized/optimized implementation later) *first*, on the theory that it'll make finishing #1 (AST → matcher-graph compilation) noticeably easier — in particular, `CodePointMap`'s merge/conflict-detection (`intersectionRejectingConflicts`) is meant to be the tool `QuantifiedUnion.buildEntryMap`'s ambiguity detection builds on, rather than hand-rolling Guava `RangeMap` overlap bookkeeping.
- Done as of 2026-09-05: `CodePointMap`/`TreeCodePointMap` rewritten and tested (23 tests), module compiles and its test suite runs green. See the [479dd84](../.) commit message for the full list of what that touched, including a few unrelated pre-existing bugs fixed along the way (a real `PatternParser` typo, `UnicodePredicates.java`'s "code too large" compile error, JDK/Checker-Framework toolchain issues) because they were blocking any compilation/testing at all.
- Next up per this plan: use `CodePointMap` to actually implement `QuantifiedUnion.buildEntryMap` (currently stubbed to throw `UnsupportedOperationException`), which is redactor #1's ambiguity-detection core.
- 2026-09-05 (later same day): the user recalled and dictated their original `compile()` algorithm design in detail — self-assigning `MatcherConstruct` constructors to break cycles, compiling tail-to-front, and a deferred `MethodHandle` optimization idea now believed moot given the `RangeMap`-based dispatch. Written up in full in [design.md](design.md)'s "The `compile()` algorithm and cycle handling" section — that's the canonical reference now, not this file.
- 2026-09-06: implemented that design for the non-repeating subset of the grammar (literals, character classes, sequences, plain alternation) — see the [2947d91](../.) commit. `./gradlew :llkpattern:test` passes 28/28. Quantifier/loop compilation (the case that actually needs the cycle-handling mechanism) is next; see remaining_work.md.
- **Debugging lesson from that session**: writing real assertions (not just "does it compile") for even the simplest patterns (`"a"`) immediately surfaced that a hand-recalled design's *scaffolding* (constructor signatures, field types) can compile cleanly while being functionally empty — I built `LiteralMatcherConstruct`'s constructor without ever populating its `dispatchMap`, and the compiler had no way to catch that. When a freshly-implemented compile step "works" but every dispatch lookup returns `null`/empty, suspect exactly this before suspecting the test. Once past that, three more real bugs surfaced in `PatternParser` (`advanceCodePoint` double-advancing, `parseComplexCharacter` never consuming `]`, wrong-variable literal accumulation) that had presumably never been exercised because nothing downstream ever ran the parser output through anything real before this session. Lesson: this parser had *no* prior end-to-end coverage — every "it parses without throwing" is not evidence it parses *correctly* until something inspects the resulting tree/values, which is exactly what the new `PatternConstruct.compile()` work finally does.
- **Debugging technique that worked well here**: when a test's actual behavior didn't match hand-traced expectations and the mismatch was confusing, adding a throwaway `System.out.println` trace line at the top of the suspect loop (temporarily, removed before committing) and running just that one test via `--tests` was much faster than repeated hand-tracing or nested unit tests — did this twice (once via a full scratch `DebugTest.java`, once via an inline trace in `PatternParser.parseUnion`) and both found the actual bug on the first try.
- 2026-09-06 (later, same day): implemented quantifier/loop compilation (`?`/`*`/`+`/`{n,m}`) and non-quantified capture groups — see the [2728911](../.) commit. Found **five more** real, previously-latent bugs this way (on top of the three from the CodePointMap session and one found earlier the same day): `{n,m}` overwrote `min` with the second number instead of setting `max`; a bare `?` never got a quantifiableIndex counter slot; the top-level pattern's `captureConstructIndex` defaulted to 0 (indistinguishable from "an actual group not yet assigned its index"), so the whole pattern was misread as capturing group 0; `Matcher#consume1CodePoint`/`consumeCodeUnits`/`peek` crashed at end-of-input instead of returning a sentinel (plus a wrong surrogate-width check in the first one); and `EndConstruct` never registered anything in its own `entryMap`, so a loop's "should I exit" dispatch had nowhere to route "there's no more input" to. Also caught one bug in code written *this same session*: `LiteralMatcherConstruct` dispatched post-consumption using a map keyed by its own *first* character (built for a different purpose entirely) instead of an unconditional forward — existing tests only checked dispatchMap's static structure, never actually called `match()`, so it went uncaught until real loop/capture tests exercised `match()` end-to-end. **Takeaway reinforced**: structural assertions (dispatchMap contents) are not a substitute for calling `match()`; prefer the latter once a construct is meant to actually run, not just compile.
- The user explicitly deferred `BackReferenceMatcherConstruct`/`BoundaryMatcherConstruct`'s actual matching behavior ("separate enough concepts to defer to a subsequent session") — still deferred, see remaining_work.md.
- 2026-09-06 (later still, same day): implemented the capturing-and-quantified case (`(a)*`) the user had asked to defer earlier — see the [35d4947](../.) commit. Turned out to compose cleanly on top of the existing loop/capture machinery once actually attempted: `LoopDispatchMatcherConstruct` gained a `captureConstructIndex` parameter, body parts compile against a `CaptureEndMarker` standing in for the loop's own owner (instead of the owner directly), and `LoopMatcherConstruct` dispatches unconditionally to a shared internal `BeginCaptureMatcherConstruct`. No new bugs found this pass — first implementation attempt passed all new tests. Also proposed (not yet implemented) splitting `MatcherConstruct` into `SingleDispatchingMatcherConstruct`/`MultiDispatchingMatcherConstruct` for performance — see design.md/remaining_work.md.
- 2026-09-06 (yet later, same day): user chose to prioritize `Matcher`'s public API over the performance split, specifically to enable "more thorough testing" — see the [edf515b](../.) commit. Implemented `matches`/`lookingAt`/`find`/group accessors/regions/reset. One more real bug found this pass: `EndConstruct` registering only the `-1` sentinel (rather than a catch-all `entryElse`) made an optional loop's exit unreachable at any position with real leftover input — invisible under `matches()`-only testing (where reaching the terminal always coincides with true end-of-string) but immediately caught once `lookingAt()`/`find()` tests existed to exercise "matched a prefix, string continues." **Pattern reinforced again**: each new *kind* of test (structural → `match()` → `matches()`/`lookingAt()`/`find()`) has now found at least one real bug the previous kind couldn't see. Treat that as a standing prior, not a fluke — when adding the next new capability, expect its first real tests to find something.
- The user's eventual plan (recorded, not yet started) is a scraped-corpus differential test harness comparing this engine against `java.util.regex` on real `(pattern, flags, input)` tuples mined from other projects' regex *test suites* (not bare regex literals scraped from code, which they already tried and found to have "limited value" without known inputs to run) — see remaining_work.md's Testing section for the full writeup. This is now unblocked since `Matcher`'s API exists.
- 2026-09-07: the scraped-corpus harness landed (`CorpusGenerator`, `ScrapedCorpusTestBase`, `OpenJdkBmpCorpusTest`/`OpenJdkSupplementaryCorpusTest` — see remaining_work.md's "Scraped-corpus differential test harness" section for the design). Snapshot as of that day: BMP 148/222 AGREES, supplementary 228/339 AGREES (most non-`AGREES` rows are still-unimplemented features like lookaround/backreferences/Unicode scripts, not yet human-triaged — that triage pass is tracked as an open item in remaining_work.md).
- 2026-09-07: while scoping the previously-deferred `SingleDispatchingMatcherConstruct`/`MultiDispatchingMatcherConstruct` split, the project owner recognized that `LoopDispatchMatcherConstruct` (introduced 2026-09-06) wasn't one of their originally-intended opcodes -- a prior session's invention that drifted from the small "opcode" model they'd designed. Corrected the loop/capture opcode set back to that design (dictated interactively, see design.md's "Opcode set: Single/Multi dispatch split" section for the result) and *then* implemented the dispatch split on top of the corrected shapes, rather than on top of the drift. Net result: `LoopDispatchMatcherConstruct` eliminated (a loop's entry/re-entry point is now literally a `DispatchMatcherConstruct`, the same class a union's `|` uses, via a second constructor); `EndLoopMatcherConstruct` turned out to already be single-successor (its old `dispatchMap` was dead weight -- only `elseDispatch` was ever populated) and moved to `SingleDispatchingMatcherConstruct`; `BeginCaptureMatcherConstruct` lost its second, branching role (previously needed for capturing loops) in favor of composing it with a plain internal `DispatchMatcherConstruct`; `SingleCharMatcherConstruct` now does a `RangeSet` membership check instead of a `RangeMap` lookup that always mapped to the same single target. Confirmed with the project owner that captures stay a flat array with a fixed `captureConstructIndex` on both Begin/End (not a stack), and that no separate loop-entry counter-reset node is needed given this engine never backtracks. Full test suite passed on the first real attempt after fixing up `PatternParserTest.java`'s structural assertions (moved from `MatcherConstruct` to `MultiDispatchingMatcherConstruct`/`SingleDispatchingMatcherConstruct`'s own `getDispatchMap()`/`getElse()`/`getNext()`) -- no new runtime bugs found this pass, unusual for this codebase's history (see the bug-finding pattern noted above) but plausible given nearly every changed node's *logic* (just its base class) was preserved verbatim.
- 2026-09-07: implemented `\b`/`\B` (word boundary) matching, resolving design.md's former "boundary matching" open question for those two boundary types (the rest -- `^`, `$`, `\A`, `\Z`, `\z`, ... -- are still open, see remaining_work.md). Added `Matcher#peekPrevious()` (mirrors `peek()`, looking backward, bounded at `regionStart`) and a `WordBoundaryMatcherConstruct`. Per the project owner, added a compile-time optimization on top of the basic runtime check: `BoundaryConstruct.buildMatcher()` classifies both the character just consumed (a new `PatternConstruct.lastCharSet()` recursion) and the one about to be consumed (the existing `entryMap`/`entryElse`) as always-word/always-non-word/unknown, folding the fully-statically-known case into a compile-time `PatternSyntaxException` or a zero-width no-op, and otherwise checking only whichever side is unresolved. See design.md's "Boundary matching" section for the resulting design.
- 2026-09-07: split `\b`/`\B` out of `BoundaryConstruct`/`BoundaryEnum.Word`/`BoundaryEnum.NonWord` into their own `WordBoundaryConstruct` (per the project owner) -- they're the only boundary types with a real implementation, and sharing one `PatternConstruct` class with the seven still-unimplemented `BoundaryEnum` values via `type == BoundaryEnum.Word` comparisons was adding indirection for no benefit now that they've diverged this much (their own field, `isWordBoundary`, instead of a shared enum; their own compile-time optimization logic). `BoundaryConstruct` reverts to a plain `BoundaryEnum`-keyed stub for the rest (`LineBegin`/`LineEnd`/`InputBegin`/`PreviousMatchEnd`/`InputEndExceptTerminator`/`InputEnd`/`Linebreak`).
- 2026-09-07: explored, then reverted, dispatching `SingleDispatchingMatcherConstruct.next.match(...)` via a bound `MethodHandle` instead of a plain virtual call -- see design.md's "Alternatives Considered" section for the final pros/cons writeup. Worth recording the attempt sequence here since it's a good example of "verify empirically before committing," including verifying a *rejection*:
  1. First form (a single `findSpecial`-based method in `MatcherConstruct` itself) failed with `IllegalAccessException` for the common case of a successor in the sibling `MultiDispatchingMatcherConstruct` hierarchy -- `findSpecial`'s `specialCaller` must be the calling `Lookup`'s own class or a subclass, which doesn't hold across that hierarchy split. Caught this with a minimal standalone repro before touching the real code, not by reasoning about the javadoc alone.
  2. Second form used `MethodHandles.Lookup.privateLookupIn` to sidestep that restriction, and worked (confirmed empirically) -- but was rejected by the project owner because `privateLookupIn` is Java 9+/Android API 33+, below this project's Android floor of API 26 (see the API-floor note above).
  3. Third form made each concrete leaf class supply its own `findSpecial` call (so `MethodHandles.lookup()`'s caller-sensitivity gives the right `lookupClass()` without `privateLookupIn`) -- this worked and shipped briefly, verified against the full suite (1381 tests) and a minimal `-source/-target 8` repro.
  4. Reverted after further discussion (with Gemini, per the project owner) established that a `MethodHandle` invocation on a non-static receiver isn't reliably inlined by any JVM, and is often *slower* than a plain virtual call even on newer Android runtimes -- invalidating the performance premise the whole exploration was based on. Back to a plain `next.match(matcher, peeked)`.
- 2026-09-07: implemented `\A`/`\z` (trivial: always the true start/end of input) and `\Z`/`^`/`$` (moderate: `\Z` needs a forward scan for "is there a line terminator that reaches exactly to the end", `^`/`$` need that plus a backward-scanning mirror for `MULTILINE`) in `BoundaryMatcherConstruct`. Two real bugs surfaced while doing this, both worth remembering:
  - **`BoundaryConstruct`/`WordBoundaryConstruct` were never getting `.flags` set by `PatternParser`** -- every other construct type gets `xxx.flags = flags;` right after construction, but the `^`/`$`/`\A`/`\G`/`\Z`/`\z`/`\b`/`\B` call sites never did, so `MULTILINE`/`UNIX_LINES`/`UNICODE_CHARACTER_CLASS` were silently ignored for every boundary -- including the earlier `\b`/`\B` work, latent until MULTILINE's test actually needed the flag to be read. Fixed by adding the assignment everywhere `BoundaryConstruct`/`WordBoundaryConstruct` is constructed.
  - **Misremembered `matches()`'s interaction with `\Z`/`$` before a trailing line terminator**: wrote tests assuming `Pattern.compile("abc$").matcher("abc\n").matches()` returns `true` (a "well known" factoid that turned out to be wrong) -- verified directly against real `java.util.regex` (compiled and ran a throwaway program against the actual JDK) before trusting either the tests or the implementation, and found both real `java.util.regex` and llk agree: `matches()` returns `false` there (it requires the *whole region*, terminator included; only `find()`/`lookingAt()` see the "except the final terminator" exemption). Fixed the tests, not the implementation, which was correct. Lesson: for cross-engine behavior, verify against the actual reference implementation rather than trusting recalled "well known" regex facts -- this is exactly what the scraped-corpus harness exists to do systematically, but a quick standalone `java.util.regex` program is the right tool when hand-verifying one specific case.
  - Also found (independently, while writing MULTILINE tests) that `lineTerminatorLengthBefore`'s first draft treated a lone `'\r'` as always a complete terminator by itself -- wrong when that `\r` is immediately followed by `\n` (i.e. it's the first half of a `"\r\n"` pair, which only completes, and only counts as ending there, one position later). Fixed before it shipped, caught by `lineBegin_multiline_doesNotMatchMidTerminator`.
  - Retagged both scraped-corpus golden files afterward (a temporary `RetagGolden` tool + gradle task, re-running `CorpusGenerator.generateRow` over each existing golden row instead of re-scraping, then deleted both -- not part of the normal regeneration workflow, just a one-off since nothing about the corpus's own *inputs* changed): BMP 148→150 AGREES, supplementary 228→235 AGREES.
  - `\G` (`PreviousMatchEnd`) is NOT trivial (per the project owner, likely wants its own `PreviousMatchEndConstruct` rather than reusing `BoundaryConstruct`, since it needs matcher history, not just position) -- left as an open question in remaining_work.md rather than attempted here.
  - `BoundaryEnum.Linebreak` turned out to be dead code (no `PatternParser` path ever produced it) -- removed outright (2026-09-07, per the project owner) once confirmed `\R` is already implemented elsewhere, as a character-consuming `ComplexCharacter` (`RegexCharacterClass.R`), not a zero-width boundary; `Linebreak` was never going to be `\R`'s home.
- 2026-09-07: split `^`/`$` out of `BoundaryConstruct`/`BoundaryEnum.LineBegin`/`BoundaryEnum.LineEnd` into their own `LineBoundaryConstruct`/`LineBoundaryMatcherConstruct` (per the project owner), for the same reason `\b`/`\B` got split earlier: a real, non-stub implementation with its own `MULTILINE`-aware logic, distinct enough from `BoundaryConstruct`'s remaining types (`\A`/`\Z`/`\z`/`\G`) that sharing one `BoundaryEnum`-keyed dispatch added indirection for no benefit. `matchesEndExceptTerminator`/`lineTerminatorLengthAt`/`lineTerminatorLengthBefore` moved from being private to `BoundaryMatcherConstruct` to package-private static helpers directly on `MatcherConstruct`, since `\Z` (still in `BoundaryConstruct`) and `$` (now in `LineBoundaryConstruct`) both need `matchesEndExceptTerminator`/`lineTerminatorLengthAt`. `BoundaryConstruct` is down to `InputBegin`/`PreviousMatchEnd`/`InputEndExceptTerminator`/`InputEnd`. Pure refactor -- full suite unchanged (1398 tests, 0 failing).
- 2026-09-07: the project owner realized `\G` doesn't actually match any specific position in the input at all -- unlike every other boundary, it's "effectively banned anywhere except the first character" and only meaningful as "anchor repeated `find()` calls to each other." Reworked accordingly: no `PatternConstruct`/`MatcherConstruct` for `\G` whatsoever (removed `BoundaryEnum.PreviousMatchEnd` and its stub case in `BoundaryMatcherConstruct`); `PatternParser` recognizes `\G` directly (bypassing the generic boundary-parsing path entirely), rejects it with a `PatternSyntaxException` unless it's at raw index 0 of the whole pattern, and otherwise just sets a `boolean anchorsToPreviousMatchEnd` flag carried onto `Ll1Pattern`. `Matcher#find(int)` consults that flag directly: when set, it tries `attemptMatch(start, false)` exactly once instead of scanning -- reusing `Matcher#matchEnd`, which was already tracked for `group(0)`/`end()`, needed no new state at all (the project owner's own observation once the design was described). This retroactively resolves what had been logged as an open design question (needing matcher "history") -- it turned out the history was already there. Per the project owner (2026-09-07): design.md holds only the *current* design and its rationale (ending in an "Alternatives Considered" section for rejected approaches, each with pros/cons) -- no dated history or narrative of how it got there; remaining_work.md holds only open items; this file (notes.md) is where that history/narrative actually lives.
- 2026-09-07: fixed bare `\p{Digit}` -- added `NamedCharClass.PosixDigit` (can't be named `Digit`; that identifier's already `\p{IsDigit}`, a genuinely different `NamedCharClass` despite the same underlying predicate: always-full-Unicode vs. ASCII-by-default) and had `PatternParser` translate the bare name "Digit" to "PosixDigit" before the `NamedCharClass.valueOf()` lookup. The project owner questioned whether this was really a naming conflict at all ("I only see one `Digit` defined") -- correct that it's not a *compile* conflict, but real: verified against actual `java.util.regex` (`\p{Digit}` vs. an Arabic-Indic digit: `false` by default, `true` under `UNICODE_CHARACTER_CLASS`) before trusting the fix, since sharing the existing `Digit` instance would have made bare `\p{Digit}` silently match non-ASCII digits by default.
- 2026-09-07: the project owner recalled Unicode scripts/blocks as already supported, which turned out to be half right -- checked directly rather than trusting either recollection: `UnicodePredicates.java` already has one generated range set per Unicode *script* (`LATIN`, `GREEK`, `CYRILLIC`, `HAN`, ~160 total; confirmed by grepping for known script names, not just the `Source.Script` enum value's existence, which proves nothing on its own), just never wired into `NamedCharClass`. *Blocks* have no generated data at all. See remaining_work.md's "HIGHEST PRIORITY" section for the resulting two separate items. The project owner clarified separately why this data got lost: an older version looked up character classes through separate `RegexCharacterClass`/`ScriptCharacterClass`/`BlockCharacterClassEnum`/`GeneralCategoryCharacterClassEnum` enums; when most classes turned out not to need a unique prefix and got merged into one `NamedCharClass`, the script/block enums were dropped in the same pass without their data being carried over -- confirmed a separate session (adjusting `unicodeanalyzer` to emit a name->`ImmutableRangeSet` map for both) is the right scope, not attempted here.
- 2026-09-07: fixed a real gap the `\p{Digit}` fix introduced: `NamedCharClass.valueOf("PosixDigit")` made the *internal* identifier `PosixDigit` itself reachable as literal pattern syntax -- `\p{PosixDigit}` silently compiled instead of throwing. Verified real `java.util.regex` rejects it (`Unknown character property name {PosixDigit}`) before adding an explicit guard in `PatternParser` plus a test. Same fix also corrected the unrelated bug where a failed bare-name lookup's exception echoed the internally-translated name (`"PosixDigit"`) instead of what the user actually typed (`"Digit"`) -- `originalCharClassName` is now kept separately for error messages.
- 2026-09-07: replaced the `Digit`/`PosixDigit` two-constant workaround (see the two entries above) with a cleaner fix the project owner proposed: `NamedCharClass.get(prefix, flags)` now checks `prefix` itself -- any Unicode-property-style prefix (`is`/`script`/`block`/`general_category`) always returns the full-Unicode set regardless of `UNICODE_CHARACTER_CLASS`, and only a bare (`none`) or `java`-prefixed lookup is flag-sensitive. `Digit` and `PosixDigit` are merged back into one `Digit` constant, given an explicit two-prefix override (`{none, is}`) since neither `Source.POSIX` (none only) nor `Source.UProperty` (is only) alone would permit both -- `NamedCharClass.allowedPrefixes` is now a per-instance field (defaulting to `source.allowedPrefixes`) rather than always deferring to `Source`. Verified this generalization is actually correct, not just convenient, by checking real `java.util.regex`: bare `\p{Alphabetic}`/`\p{White_Space}` both throw "Unknown character property name" (only their `Is`-prefixed forms are valid) -- confirming `Digit` really is the only POSIX name that also happens to be spelled identically to a Unicode binary property name, not a case needing to generalize further. `PatternParser.parseComplexEscape`'s if-chain also got the `Digit`-specific name-translation/guard removed (no longer needed) and the `script=`/`block=`/`general_category=` branches folded into the same flat if-chain as the `Is`/`In`/`java` branches (previously nested inside an `else` block only entered when none of those three matched), per the project owner's request. Bonus fix found via the new `\d`/`\D` UNICODE_CHARACTER_CLASS test added alongside this: `RegexCharacterClass.D` (`\D`) was defined via the single-`RangeSet` constructor (`ascii == unicode`), so it never actually honored `UNICODE_CHARACTER_CLASS` at all, unlike `\S`/`\W` which already complement `ascii`/`unicode` separately -- fixed to `D(Digit.ascii.complement(), Digit.unicode.complement())`. Full suite green afterward: 1419 tests, 0 failing (was 1403 before this session's `\p{Digit}`/backreference/cleanup work).
- 2026-09-07: the project owner asked for systematic ASCII-vs-Unicode test coverage across every POSIX/`java`-prefixed `NamedCharClass` (not just `Digit`). Adding it surfaced three more real, previously-uncaught bugs, each fixed and verified against real `java.util.regex` before shipping:
  - `Alnum` and `XDigit` were both built via the single-`RangeSet` constructor (`ascii == unicode`), so `UNICODE_CHARACTER_CLASS` was silently ignored for both -- `\p{Alnum}`/`\p{XDigit}` always matched their full-Unicode sets by default. Fixed both to use the `slicedAscii=true` constructor.
  - `XDigit`'s *unicode* set was independently incomplete even once slicing was fixed: real java.util.regex's widened `\p{XDigit}` is `Character.digit(cp, 16) != -1`, which accepts any Unicode decimal digit (numeric value 0-9, always below radix 16) from any script, not just the literal Unicode `Hex_Digit` property (verified with DEVANAGARI DIGIT ZERO, U+0966, which is a decimal digit but not itself `Hex_Digit`-property). Fixed to `unionOf(Hex_Digit.unicode, Digit.unicode)`.
  - `White_Space` (backing `\p{IsWhite_Space}`, and in turn `Space`/`Blank`'s Unicode-widened sets) delegated to `javaWhitespace` (`Character.isWhitespace`), which is NOT the same set as the real Unicode `White_Space` binary property -- `Character.isWhitespace()`'s own javadoc deliberately excludes NO-BREAK SPACE (U+00A0), NARROW NO-BREAK SPACE (U+202F), and MEDIUM MATHEMATICAL SPACE (U+205F) as "non-breaking", while the real Unicode property includes them (confirmed real java.util.regex matches U+00A0 under `\p{IsWhite_Space}`). Fixed by hand-building the real property's range set directly on `White_Space` (matching `RegexCharacterClass`'s already-correct `h`/`v` data, but not reusable directly -- see the existing `NamedCharClass`<->`RegexCharacterClass` circular-static-init note on `Space` for why); also confirmed U+180E (MONGOLIAN VOWEL SEPARATOR, sometimes mistaken for whitespace, removed from the property in Unicode 6.3) is correctly excluded, matching real java.util.regex. Full suite green afterward: 1419 tests, 0 failing (test count unchanged from the prior entry -- this round added assertions to existing test methods rather than new ones, plus one new assertion pair on `property_isWhiteSpace`).
- 2026-09-07: the project owner accepted backreference support in principle, then immediately spotted the real problem themselves: a conditional or loop-tail backreference's entry set isn't knowable from the AST alone the way `BackReference`'s current stub (`entryElse = this`, a catch-all) assumes, so it can conflict with sibling `RangeMap` branches in a way the compile-time disjointness check can't currently catch or resolve. Laid out three options (drop backreferences; document a priority favoring backreferences; check for conflicts at match time instead of compile time) without picking one. Before responding, verified with a real `java.util.regex` scratch program that the everyday case (`(\w+)\s+\1`) and the ambiguous case (`(a+)\1`) both behave the way the fourth (unlisted) option below assumes. Proposed and the project owner chose a fourth option instead: give backreferences a *precise* compile-time entry set (via a new `firstCharSet()` AST helper, mirroring the existing `lastCharSet()` used for `\b`/`\B`) computed from the referenced group, rather than a catch-all -- strictly more permissive than dropping them, avoids documenting (B)'s surprising priority rule (which, it turned out, is actually already `BackReference`'s accidental current behavior), and keeps ambiguity detection fully at compile time unlike (C). Recorded in design.md's new "Backreferences" section and "Alternatives Considered" entry; not yet implemented, see remaining_work.md.

- 2026-09-07: implemented backreferences (`\1`-`\9`, `\k<name>`) end to end -- parsing
  (`PatternParser.tryParseBackReference`, resolving a reference to its already-parsed
  `QuantifiedUnion` immediately, rejecting forward references and undefined groups at parse
  time), the compile-time precise entry set (`PatternConstruct.firstCharSet()`, mirroring the
  existing `lastCharSet()`), and match-time comparison (`BackReferenceMatcherConstruct.match`,
  comparing the referenced group's captured text against upcoming input, folding
  `CASE_INSENSITIVE`/`UNICODE_CASE` the same way `LiteralMatcherConstruct` does; an
  unparticipated group's backreference fails outright rather than matching empty, per
  `java.util.regex`). This was the decision recorded 2026-09-07 earlier the same day (see
  design.md's "Backreferences" section) -- implementing it surfaced two things worth recording:
  - The scraped-corpus golden files needed retagging after this landed, since several rows'
    previously-recorded `UNIMPLEMENTED: llk failed to compile` status was specifically because
    the *old* `BackReference` stub's `entryElse = this` catch-all made almost any backreference
    usage a guaranteed compile-time ambiguity error -- masking whatever the pattern would
    actually do once backreferences worked. Retagged via the same one-off `RetagGolden` tool
    pattern used on 2026-09-07 earlier (re-running `CorpusGenerator.generateRow` over each
    existing row without re-scraping, then deleting the tool): BMP and supplementary golden files
    each had 3 rows retagged. Two of those three now correctly `AGREES`; see the third bullet
    below for the third.
  - Found a real, pre-existing bug **unrelated to backreferences**, exposed only because
    backreferences no longer force those patterns to fail at compile time: a quantified/loop
    construct immediately followed by a composite (non-leaf) construct -- e.g. `(a)(b)*(z)`,
    where the thing right after the `(b)*` loop is another capturing group -- throws a
    `NullPointerException` at match time. Reproduces on plain, unmodified `main` with no
    backreference involved at all (verified via `git stash` before diagnosing further). Root
    cause: `Sequence`/`QuantifiedUnion`'s `buildEntryMap` never re-key their `entryMap` values
    onto `this` the way leaf constructs (and `CaptureEndMarker`, fixed for the identical reason
    on 2026-09-06) do -- so an identity check like `e.getValue() != next` (used by the loop
    machinery to detect "this range means exit the loop toward `next`") silently never matches
    when `next` is itself composite. Flagged as a background task rather than fixed in this
    session (out of scope for "implement backreferences", and a cross-cutting fix); see
    remaining_work.md.
  - Numbered backreferences only support a single digit (`\1`-`\9`), unlike `java.util.regex`'s
    greedy multi-digit parsing -- a deliberate scope-limiting decision for this session, not a
    bug; see remaining_work.md.
- 2026-09-07 (same day, later): fixed the quantified-loop-followed-by-composite-construct
  NullPointerException noted above, rather than leaving it for the flagged background task (short
  session, bug fresh in context). Root cause confirmed as diagnosed: `Sequence.buildEntryMap` and
  `QuantifiedUnion.buildEntryMap` were aliasing their `entryMap` to whatever nested leaf actually
  built each range, instead of re-keying onto `this` the way leaf constructs and `CaptureEndMarker`
  already did -- fixed both to re-key, plus the analogous `owner.entryMap`/`owner.entryElse`
  population at the end of `DispatchMatcherConstruct`'s loop-flavored constructor (same aliasing
  bug, for when a loop construct itself later serves as some ancestor's `next`).
  - This straightforward-sounding fix broke two more things on the first attempt, both found by
    just running the full suite immediately after -- worth recording since each was a real,
    non-obvious consequence of the same rekeying idea:
    1. Rekeying `QuantifiedUnion`'s entryMap onto `this` broke its OWN capturing-branch matcher
       construction: `buildMatcher()`'s capturing case builds an internal (non-self-registering)
       `DispatchMatcherConstruct(entryMap, entryElse, flags)` *before* `this.matcher` gets set
       (only the wrapping `BeginCaptureMatcherConstruct`, built afterward, sets it) -- so reading
       the now-rekeyed-to-`this` `entryMap` there resolved every `.matcher` lookup to `null`.
       Fixed by adding a second, non-rekeyed `rawEntryMap`/`rawEntryElse` pair on `QuantifiedUnion`,
       populated alongside the public (rekeyed) `entryMap`/`entryElse` in `buildEntryMap`, and used
       only by `buildMatcher()`'s own internal dispatch construction.
    2. The *non-capturing* case has the identical hazard, missed on the first pass: `new
       DispatchMatcherConstruct(this)` self-registers (`owner.matcher = this`) and THEN calls
       `populate(owner.entryMap, owner.entryElse)` -- since `owner.entryMap` was rekeyed onto
       `owner`, `populate()` resolved every entry to `owner.matcher`, which IS this very node --
       an infinite self-dispatch loop, caught immediately as a `StackOverflowError` in
       `GroupSyntaxTest`'s alternation tests. Fixed by adding a new self-registering
       `DispatchMatcherConstruct(PatternConstruct owner, RangeMap, PatternConstruct)` constructor
       overload that populates from explicit (`rawEntryMap`/`rawEntryElse`) arguments instead of
       `owner`'s own fields, and using it for the non-capturing case too.
  - Net rule that emerged: rekeying a construct's `entryMap` onto `this` is only safe when that
    construct's own `buildMatcher()` never itself needs to resolve `.matcher` through that same
    (now-self-referential) map -- true for `Sequence` (whose `matcher` is a direct alias to its
    first element's real matcher) and `CaptureEndMarker` (whose `matcher` is built from
    `realNext.matcher` directly), but NOT for `QuantifiedUnion` (whose own dispatch node is BUILT
    FROM `entryMap`), which needed the raw/public split instead.
  - Retagged the scraped-corpus golden files again after this fix (same `RetagGolden` one-off
    pattern, written and deleted again): BMP 2 rows retagged (the previously-`UNEXPECTED`
    `(あ+ぃ)+` row now `AGREES`, and the previously-`UNIMPLEMENTED: NullPointerException`
    backreference row now `AGREES`), supplementary 3 rows retagged (the equivalent
    non-BMP-codepoint versions of both). Full suite green afterward (including the new
    `QuantifierAndCaptureTest` regression coverage added for this fix): 1423 tests, 0 failing,
    561 skipped.
- 2026-09-07 (same day, yet later): found and fixed a second, unrelated real bug while triaging
  the scraped-corpus harness's un-triaged `UNEXPECTED` rows (48 of them, tracked as an open item in
  remaining_work.md's "Next steps") -- `.` (dot) was silently never actually quantifiable at all:
  `PatternParser`'s `'.'` case called `parseQuantifiable(dot)` *before* `advance(1)`, so the
  quantifier-suffix check ran while `peek` was still `'.'` itself and never saw the real following
  character -- every other quantifiable construct (bracket classes, plain literals) already
  advances past its own token first. Confirmed via a throwaway program:
  `Ll1Pattern.compile(".*z").matcher("a*z").find()` returned `true` (matched the literal text
  `"a*z"`), not "any characters then z". Found by testing the `.+ぃ`-family un-triaged rows by hand
  rather than assuming they were expected reluctant/possessive-quantifier divergences (the
  documented, actually-expected category) -- a quick standalone `.+z"`/`".*z"` scratch test caught
  the real cause on the first try. Fixed by swapping the order (advance, then check for a
  quantifier), matching every other call site's convention. This retroactively explains most of
  the `.{quantifier}` rows in that un-triaged set: they weren't behavior divergences at all, just
  this parser bug -- and, now fixed, several flip to a *new*, also-correct outcome: a quantified
  "." immediately followed by an ordinary literal (e.g. `.*ぃ`) is now correctly detected as
  LL(1)-ambiguous at compile time (since "." matches nearly everything, including whatever
  literal follows it) -- exactly the same category of rejection `[a-z]+z`/`a+a` already got, not a
  new bug. Retagged both golden files again (10 BMP rows, 13 supplementary rows); added dedicated
  regression tests in `QuantifierAndCaptureTest` (using "." as the pattern's own tail, matches()
  over the whole string, to isolate "does the quantifier apply to dot" from the separate,
  already-tested ambiguity-with-a-following-literal case). Full suite green: 1428 tests, 0
  failing, 561 skipped.
- 2026-09-07 (same day, yet later still): per the project owner, triaged all of the scraped-corpus
  harness's un-triaged `UNEXPECTED` rows (33 of them -- "both engines ran to completion but
  disagree," as opposed to `UNIMPLEMENTED`, where llk doesn't support a feature at all) rather than
  continuing to defer it, since the "." fix just above had already found a real bug hiding in that
  backlog. Found and fixed two more real bugs this way, both pre-existing since well before this
  session (confirmed via `git stash` against the pre-session commit before fixing either):
  1. **`Matcher#attemptMatch` never reset `quantifiableCounts`/`captureGroups` between separate
     match attempts** -- only `reset()`/`reset(String)` did. A loop's iteration counter is
     normally reset to 0 only when its own `EndLoopMatcherConstruct` exit fires; an attempt that
     instead hard-fails by exceeding `max` (`LoopMatcherConstruct`'s own check, with no
     backtracking to undo it) never reaches that reset, leaving a stale nonzero counter for the
     NEXT attempt to read -- routinely triggered by `find()`'s own internal scan over successive
     start positions. Confirmed via `a{2,3}` against `"aaaa"`: every real start position hard-fails
     (see the second bullet below for why), and without this fix, find() went on to spuriously
     "match" an empty string at the very end of input once a leftover count from an earlier failed
     attempt happened to already satisfy `min`. Also affected `a?b` (matched only `"b"` instead of
     `"ab"`) and `(ab)+` against input with no `"ab"` substring at all (spuriously matched empty).
     Fixed by resetting both arrays at the top of every `attemptMatch` call (factored into a shared
     `resetPerAttemptState()`, also used by `resetMatchState()`).
  2. **Capturing groups were numbered in closing-paren order, not opening-paren order.** A
     recursive-descent parser's nested `parseGroup()` calls always finish (and, before this fix,
     always finished claiming their `captureConstructIndex`) before the enclosing group's own call
     returns -- so `"(a(b)(c))"` assigned group 1="b", group 2="c", group 3="a(b)(c)" instead of
     the expected (and what every other regex engine, and this engine's own numbering-consuming
     code, assumes) group 1="a(b)(c)", group 2="b", group 3="c". Backwards for any pattern with
     nested capturing groups. Fixed by assigning `captureConstructIndex` (and registering a named
     group) right after parsing the `(`/`(?...)` prefix, before recursing into the group's own
     content, instead of after `parseUnion` returns -- `closedGroupsByIndex` (used by
     backreferences' forward-reference check) still only gets populated once the group is fully
     closed, unaffected by this change.
  - After both fixes, 33 `UNEXPECTED` rows dropped to 15 (BMP 12→6, supplementary 21→11 --
    numbers from an earlier snapshot mid-triage, not the exact before/after of each individual
    fix). Retagged both golden files twice, once per fix (same `RetagGolden` one-off pattern,
    written and deleted each time).
  - The remaining 15 rows split into three categories, recorded in remaining_work.md rather than
    repeated here: a third real bug (a quantified/loop construct whose body contains another
    quantified/loop construct fails to match at all -- diagnosed in detail, not yet fixed, flagged
    as its own remaining_work.md item since the fix needs real design thought), the already-tracked
    unimplemented `COMMENTS`/`(?x)` flag, and bounded/reluctant-quantifier edge cases that are a
    fundamental consequence of this engine's no-backtracking design (not bugs) and should be
    retagged `EXPECTED_DIVERGENCE` rather than left as "needs investigation."
  - Full suite green throughout: 1433 tests, 0 failing, 561 skipped, after adding regression
    coverage in `MatcherApiTest` for both fixes.
- 2026-09-07 (same day, yet later still): implemented `COMMENTS` (`(?x)`) per the project owner
  (deemed "probably straightforward" once the other two bugs were fixed and out of the way, rather
  than deferred to a separate session like the deeper nested-quantifier bug). Added
  `PatternParser.skipComments()` -- strips a run of whitespace and `#`-to-end-of-line comments,
  a no-op when the flag isn't set -- called at three points: the top of `parseUnion`'s main loop
  (between any two top-level tokens), right after a plain character's own inline "is a quantifier
  next?" check (which peeks at the very next character without going back through the main loop,
  so needed its own call), and at the top of `parseQuantifiable` (covering every other atom type --
  bracket classes, groups, "."). Never called from inside `[...]` (whitespace stays significant in
  a character class, matching real regex) or from the flag-list/group-name scanning loops (those
  have their own tighter grammars). Existing inline-flag-scoping machinery (used by `(?i:...)`/
  `(?s:...)`) handled `(?x:...)` correctly for free, confirmed by a dedicated scoping test. Fixed
  the three exact `(?x)`-with-whitespace corpus rows in each golden file (retagged, all now
  `AGREES`) plus additional cases (whitespace between an atom and its quantifier, e.g. `"a * b"`)
  found while writing `CommentsFlagTest`. 33 `UNEXPECTED` corpus rows (see the entry above) down to
  12. Full suite green: 1439 tests, 0 failing, 561 skipped.
- 2026-09-08: fixed the nested-quantifier-in-loop-body bug flagged 2026-09-07 (`(a(b)?)+` failing
  to match `"a"`) -- the project owner designed the fix (a lazy, memoized, cycle-guarded
  `getEntryPointMap()`/`getEntryElse()` pair replacing direct `entryMap`/`entryElse` field reads,
  mirroring the existing `matcher` self-registration trick but for entry-point computation instead
  of matcher construction), refined through several rounds with the advisor and the owner
  correcting the assistant's mis-traces along the way (see design.md's "Entry-point computation vs.
  matcher compilation" section for the resulting design and its rationale). Two real, distinct bugs
  had to be found and fixed, both empirically (implementing the design, then debugging why the
  target patterns still failed rather than assuming the design was complete):
  1. `Sequence`'s and `QuantifiedUnion`'s (plain, non-loop) entry-point computation had to be split
     from their matcher-wiring compile order -- a sequence's own entry set is always just its first
     element's, computable without touching the rest of the sequence at all, but the OLD code
     computed it only after fully compiling (matcher-building) every element tail-to-front. Left
     coupled that way, asking an enclosing construct for its entry point early (exactly what a
     nested loop's own construction needs to do) triggered a premature full compile of everything
     after it in the sequence, before what comes AFTER *that* was itself compiled yet -- an NPE
     (`LiteralMatcherConstruct`'s `next` field null) on patterns with no nesting bug at all (e.g.
     `(?:a)b`), caught immediately by the full suite.
  2. Even with entry-point computation correctly decoupled, the nested-loop case still failed to
     match (not a crash -- just returned `false`) until debugged with throwaway trace prints. Root
     cause: a loop's own *advertised* entry point (used for ambiguity-checking against siblings) is
     deliberately narrow -- only its continuation characters, e.g. an outer `+` loop only advertises
     the character that continues it, not "anything else" -- but its REAL matcher graph always has
     a way to handle "anything else" (by trying to exit). The nested loop's real dispatch
     construction was requiring `next` (the outer loop) to have registered an explicit catchall in
     that narrow advertised set before routing "exit" there at all, which an outer loop legitimately
     never does. Fixed by deriving the loop's own unconditional "else, try to exit" fallback from
     whether ITS OWN BODY has a catchall (via a separate `mergeEntryPoints(body-only)` call), not
     from whether `next` happens to have one -- `next`'s own dispatch handles acceptance/rejection
     on its own terms either way.
  - Verified against the specific regression list the advisor named up front (not just the new
    tests): `(a)(b)*(z)`, `(ab)+`, `a?b`, `(あ+ぃ)+`, `[a-z]+z` (still correctly rejected as
    ambiguous) all still pass, alongside the new nested-loop cases. Full suite: 1443 tests, 0
    failing, 561 skipped (up from 1439/0/561 -- four new tests in `QuantifierAndCaptureTest`).
  - Also added, per the advisor's point about the fix being user-reachable: a loop whose entire
    body can match zero characters (e.g. `(a?)+`) is a genuine, unbreakable self-reference under
    this scheme (computing its own entry point requires that same entry point already be known) --
    now a `PatternSyntaxException` naming the loop's start index, rather than a stack overflow or a
    silent wrong answer; also a real infinite-loop hazard in its own right, not just an artifact of
    the implementation technique.
  - Retagged both scraped-corpus golden files (same one-off `RetagGolden` tool pattern used in
    earlier sessions, written and deleted again): BMP 2 rows retagged, supplementary 5 rows
    retagged -- all 7 were exactly the `UNEXPECTED: ... needs investigation` rows this bug had been
    causing, now correctly `AGREES`.

## Tooling gotchas (this dev machine, Windows + git-bash)

- The Bash tool here is git-bash; running Windows Java tools (`java -cp ...`) directly through it can silently mis-handle mixed forward-slash paths and `;`-separated classpaths. When invoking `java`/`javac` directly (outside Gradle) with an explicit classpath, prefer the PowerShell tool with native `C:\...` paths — that's what actually worked when regenerating `UnicodePredicates.java` from `unicodeanalyzer`.
- PowerShell's `Out-File -Encoding utf8` writes a UTF-8 **BOM**. If the output is Java source (or anything else that cares), strip the BOM (`\xEF\xBB\xBF`) before compiling — it silently broke the first token in the first generated file until caught by a stray parse error location.
- `./gradlew` daemons can get stuck on a stale/wrong JDK after `JAVA_HOME` changes mid-session or after a host JDK auto-updates; `./gradlew --stop` before retrying is a cheap first move when a build fails in a way that looks environmental (e.g. `Unsupported class file major version NN`) rather than a real compile error in the diff you just made — confirm by re-running the *unmodified* file/command to see if the failure predates your change.
- Git on this machine warns `LF will be replaced by CRLF` on nearly every commit — that's this repo's line-ending normalization doing its job, not an error; ignore it.

## Scraped-corpus microbenchmark (2026-09-08)

- Built with JMH (via the `me.champeau.jmh` Gradle plugin), per the project owner's choice over a
  hand-rolled JUnit timer loop -- real warmup/fork/dead-code-elimination rigor was worth the new
  build dependency. Results go to both console and a checked-in JSON baseline (also the owner's
  choice over console-only or a separate decision later).
- First real run (JDK 17, this dev machine, 3 warmup + 5 measurement iterations @ 10s each, one
  fork): `llkCompile` ~20 ms/op vs `regexCompile` ~0.10 ms/op (llk ~200x slower to compile --
  expected, per the original design sketch, since it builds a full dispatch graph upfront), and
  `llkMatch` ~0.11 ms/op vs `regexMatch` ~0.055 ms/op (llk ~2x slower per match call here, not
  actually faster as the original sketch speculated might happen -- real data point, not yet
  investigated further). Take the specific ratios with a grain of salt: single-fork, single-machine,
  no `-prof`/`-lprof` isolation yet, and JMH's own compiler-blackhole-mode warning applies (see the
  run's own printed caveats).
- The results JSON is only written once the *entire* `jmh` task finishes; killing/timing out a
  partial run leaves the file empty (`[]`) rather than partially populated -- don't mistake that for
  "the benchmark found nothing."
- Added JMH's built-in `GCProfiler` (`profilers = ['gc']` in the `jmh {}` block) per the project
  owner's request for GC count and a memory delta, after clarifying "native memory" isn't
  applicable here: neither `java.util.regex` nor `Ll1Pattern` does any off-heap/JNI allocation, so
  real Native Memory Tracking (`-XX:NativeMemoryTracking` + `jcmd VM.native_memory diff`) would show
  nothing for either engine -- heap allocation-rate (`gc.alloc.rate.norm`, bytes/op) is the
  meaningful equivalent for a pure-Java comparison like this, and needed no extra JVM flags or
  jcmd scripting, just the one profiler flag. Adds `gc.alloc.rate`/`gc.alloc.rate.norm`/`gc.count`/
  `gc.time` as secondary metrics on every existing benchmark.
- Second full run's numbers (same machine/JDK, now with GC profiling on): times matched the first
  run closely (`llkCompile` ~21.5 ms/op, `regexCompile` ~0.10 ms/op, `llkMatch` ~0.09 ms/op,
  `regexMatch` ~0.055 ms/op). The allocation numbers tell a more nuanced story than the raw times
  alone: llk allocates ~26x more per compile (~10.5 MB/op vs ~405 KB/op -- consistent with building
  a full dispatch graph upfront) but only ~1.4x more per match (~121 KB/op vs ~84 KB/op) -- match-time
  allocation is much closer between the two engines than the ~1.7x match-time ratio might suggest,
  worth keeping in mind when profiling tomorrow (the time gap may be more about work-per-allocation
  than allocation volume).

## ArrayCodePointMap (2026-09-08)

- Replaced `TreeCodePointMap`'s Guava `TreeRangeMap` delegation as `PatternConstruct`'s actual
  ambiguity-detection/entry-map backing with `ArrayCodePointMap` (two flat arrays; see design.md's
  "Code point range representation" section for the shape). `TreeCodePointMap` was kept, not
  deleted, specifically to serve as the differential-test oracle (`CodePointMapDifferentialTest`)
  -- Guava's implementation is well-exercised and a useful independent check on the new one.
- `PatternConstruct` referenced the concrete `TreeCodePointMap` type directly in several places
  (not just through the `CodePointMap` interface, contrary to the "swap doesn't require touching
  callers" claim design.md/remaining_work.md made at the time) -- retyped those to
  `MutableCodePointMap` first, as its own verified-green step, before introducing the new
  implementation. That included dropping an unchecked `(TreeCodePointMap<PatternConstruct>)` cast
  on `merged.union(branchMap)`'s result in `mergeEntryMapRejectingAmbiguity`, which only worked
  because `CodePointMap.union`'s default hard-codes `new TreeCodePointMap<>(this)` -- replaced with
  a direct `merged.putAll(branchMap)`.
- Found and fixed two latent bugs while building this out, both in already-existing code, not new:
  - `CodePointMap.ImmutableEntry` didn't override `equals`/`hashCode`, so `entrySet().equals(...)`
    (which both `TreeCodePointMap` and `ArrayCodePointMap`'s `equals()` now rely on) silently
    compared entries by object identity. Was previously masked because `TreeCodePointMap.equals`
    compared the underlying `rangeMap` directly rather than going through `entrySet()`.
  - Confirmed empirically (not just assumed) that Guava's `TreeRangeMap.put` does *not*
    auto-coalesce adjacent equal-value ranges into one entry -- two separate `put` calls for
    touching ranges produce two entries, not one. `ArrayCodePointMap` deliberately does coalesce
    (capacity permitting), so `TreeCodePointMap.equals`/`hashCode` were changed from raw
    `rangeMap.equals` to `entrySet()`-based comparison, so the two implementations agree on
    equality for logically-identical maps regardless of how each was built up.
- `CodePointMapTestBase` now holds the ~24 shared behavioral cases, run against both
  implementations via `TreeCodePointMapTest`/`ArrayCodePointMapTest` (each just supplies a
  factory). `ArrayCodePointMapTest` adds its own coalescing-specific cases.
  `CodePointMapDifferentialTest` runs randomized `put`/`remove` sequences (200 trials of 50 ops
  each, biased toward code points near `0x100000` -- the plane-16 boundary where a signed-int
  packing bug would show up first) through both implementations and asserts they agree, both at
  sampled code points and on a coalescing-normalized `entrySet()`.
- Full suite after the swap: 1476 tests, 0 failing, 561 skipped (up from the prior 1443/561
  baseline — the new coverage above accounts for the difference).

### JMH regression found post-swap, and three follow-up fixes (2026-09-08)

- Running `CorpusBenchmark` after the swap surfaced a real regression the test suite couldn't
  catch (it checks correctness, not speed): `llkCompile` went from 21.5ms/op to 94.3ms/op (+339%),
  while `regexCompile`/`regexMatch` (which never touch this code) only moved ~+30% -- that's this
  machine's noise floor for a same-session before/after comparison, so the `llkCompile` number was
  real, not noise. GC got *cheaper* (fewer/shorter pauses) but allocation/op more than doubled
  (10.5MB -> 24.2MB), pointing at CPU-bound array-shuffling work, not GC pressure.
- Root cause (confirmed by the project owner's read, not just guessed): `remove()` and
  `coalesceAround()` -- both run on *every* `put()` call -- unconditionally rescanned the entire
  array regardless of where `[min, max)` actually fell. Building an n-entry map via n `put()` calls
  (exactly what `PatternConstruct.toCodePointMap`'s per-entry loop, and `putAll`'s default
  one-`put()`-per-source-entry forwarding, both do) was therefore O(n^2), not O(n). Fixed in three
  steps, each independently verified against the full suite and a re-run of `CorpusBenchmark`:
  1. Rewrote `put()`/`remove()` to use the existing binary-search lookup to find the exact window
     of entries `[min, max)` overlaps, touching only that window (plus O(1) boundary-coalesce
     checks) instead of the whole array. Also added `MutableCodePointMap.appendSorted` (bulk-append
     assuming ascending, non-overlapping input) and `ArrayCodePointMap.ensureCapacity` (now public,
     also added to the interface as a hint), and switched `toCodePointMap` to use both instead of
     `put()`. Result: llkCompile 94.3ms -> 41.1ms (but a very noisy run, +-22ms error bar).
  2. Simplified `remove()` further per the project owner's suggested case breakdown (a single
     touched entry is a plain field edit -- shorten it, move its start, or split it in two with one
     `insertSingle` -- never the general multi-entry path; multiple touched entries trim the first/
     last in place and delete what's strictly between via one shift), eliminating the small scratch
     `int[]`/`V[]` arrays `put()`'s general path was still allocating per call.
  3. Formalized "entrySet() (and everything built on it) is always ascending by min" as a
     `CodePointMap` interface-level ordering contract (documented in its class doc) rather than an
     implementation detail `appendSorted`'s callers had to trust informally -- every implementation
     already satisfied it, so this didn't change behavior, but it's what makes the next point sound.
     Rewrote `ArrayCodePointMap.putAll` from "one `put()` per source entry" into a single sorted
     merge sweep (two-pointer walk over this map's own entries and `other`'s, `other` winning on
     overlap -- see its own doc comment for the algorithm), which also *removes* the separate
     from-empty fast path since the general sweep degenerates to it automatically when this map
     starts empty. Added `CodePointMapDifferentialTest.randomPutAll_agreesWithTreeCodePointMap`
     (200 trials merging two random maps) as dedicated stress coverage for this, beyond the small
     hand-written `union_*` cases in `CodePointMapTestBase`.
  Also fixed, while investigating: `CodePointMap.union()`'s default hard-codes `new
  TreeCodePointMap<>(this)` regardless of the receiver's actual type -- the same class of bug
  already found once in `PatternConstruct` (see above). `ArrayCodePointMap` now overrides `union`/
  `difference` to construct the correct concrete type. `ArrayCodePointMap.entrySet()` was also
  changed from an eager `LinkedHashSet` copy to a lazy view (cheap to call, pay only for what you
  iterate) after noticing `PatternConstruct.findFirstOverlap` called `merged.entrySet()` *inside*
  its outer loop -- rebuilding merged's full entry set once per branch entry -- which was hoisted
  out as its own fix alongside the view change.
  Final result after all three fixes: llkCompile 21.5ms -> 33.8ms/op (+57.5% against a same-run
  noise floor of ~+6%, so ~+50% real), allocation/op 10.5MB -> 17.2MB. Down from the initial +339%/
  2.3x regression, but not fully closed -- left for the project owner's own planned profiling pass
  to find what's left (candidates raised in discussion: `floorIndex`'s binary search over the very
  small maps that dominate this workload -- worth comparing against a linear scan below some size
  threshold -- or `entrySet()`'s remaining per-call allocation, e.g. `putAll`'s `other.entrySet()
  .size()` capacity-hint call).
- Also switched `ArrayCodePointMap`'s packed key from `long` to `int` (21+11 bits fits exactly;
  the `long` was unnecessary and doubled `keys[]`'s memory footprint for no reason) and its initial
  array capacity from 16 down to 1 (most instances here are small -- one per union/loop-dispatch
  node in `PatternConstruct`, and many have a single entry) -- both raised by the project owner
  during this same investigation, not separately discovered.
- Tried the project owner's linear-scan-under-65-entries idea for `floorIndex` (kept, harmless, but
  flat: -1.0% on `llkCompile`, within that run's own noise floor). A follow-up JMH run with `-prof
  stack` (JMH's built-in sampling profiler -- crude and safepoint-biased, ~50% of samples came back
  as `<stack is empty>`, but still useful as a first look) pointed somewhere unexpected: the named
  hot spots weren't `ArrayCodePointMap` at all -- they were `java.util.TreeMap.compare`/
  `getCeilingEntry`/`put`/`fixAfterInsertion`/`getLowerEndpoint`/`successor` and
  `com.google.common.collect.Range.compareOrThrow`, ~31% of attributed RUNNABLE samples.
- That led to the actual fix: `PatternConstruct.entryMap` (every AST node's own "what comes next"
  field) was **never migrated off Guava's `RangeMap`/`TreeRangeMap` at all** -- the original
  `ArrayCodePointMap` swap only touched the later ambiguity-check conversion step
  (`toCodePointMap`/`mergeEntryMapRejectingAmbiguity`), not `entryMap` itself, which every single
  `buildEntryMap()` override across every construct type populated directly via Guava calls. Since
  every one of `entryMap`'s ~9 `.put()` call sites inserted `this` as the value (verified by reading
  all of them, not assumed), and every caller reading another construct's `entryMap` already re-keys
  onto a construct it has in hand rather than trusting the stored value (confirmed by an existing
  comment: "re-key every range onto `this` instead of aliasing ... directly"), the project owner
  correctly guessed the value carried zero information and could become `CodePointMap<Boolean>`
  (values always `true`) instead of `RangeMap<Integer, PatternConstruct>` -- migrated 2026-09-08:
  - `entryMap`'s field type, `getEntryPointMap()`'s return type, and every `.put()` call site
    changed accordingly; using `CodePointMap`'s native `[min, max)` int convention directly also
    eliminated the Guava `Range.canonical(DiscreteDomain.integers())` dance every call site used to
    need. `toCodePointMap` (which converted a source `RangeMap`'s existing values) became
    `toValueMap` (which stamps a given `PatternConstruct` onto ranges from a `CodePointMap<Boolean>`
    that never had real values to begin with).
  - `QuantifiedUnion.rawEntryMap` is a *different*, genuinely multi-valued field (it retains real
    distinct branch identities, needed by `DispatchMatcherConstruct` to build the actual runtime
    dispatch graph) -- deliberately left as Guava `RangeMap` in this pass, not touched.
  - Found and removed one piece of dead code while updating call sites: `DispatchMatcherConstruct`
    had a self-registering constructor (`DispatchMatcherConstruct(PatternConstruct owner)`) that
    read `owner.getEntryPointMap()` directly -- it had zero callers anywhere in the codebase, and
    would have needed `entryMap`'s old (fictional) multi-valued semantics to make sense.
  - Also found and removed: the unused `PatternConstruct.EMPTY_MAP` constant (a `RangeMap`,
    referenced nowhere).
  - Result: `llkCompile` 33.8ms -> **16.4ms/op**, a -51.5% drop -- and, notably, -23.7% *below* the
    very first pre-`ArrayCodePointMap` baseline (21.5ms), not just a recovery. The full suite
    (1477 tests, 0 failing, 561 skipped) passed on the first try after this change, both at compile
    and at test-run time.
  - One loose end: the same run showed `llkMatch` (runtime, not compile time) up ~+12% against a
    ~+-3-5% same-run noise floor -- outside that floor, but nothing about this change should affect
    match-time behavior. Flagged in remaining_work.md as unexplained, pending a repeat run before
    spending real investigation time on it.

### `QuantifiedUnion.rawEntryMap` migration (2026-09-08, same day)

- Followed up on the `entryMap` migration above by also moving `QuantifiedUnion.rawEntryMap` (the
  one other entry-point-shaped field still on Guava `RangeMap`) onto `CodePointMap<PatternConstruct>`
  -- unlike `entryMap`, this one is genuinely multi-valued (real per-branch identities), so it kept
  its value type, just changed its backing. Since `rawEntryMap` was populated in one shot from
  `mergeEntryPoints`'s own result (already exactly the right `CodePointMap<PatternConstruct>`), the
  migration let the per-entry copy loop disappear entirely -- `rawEntryMap = result.ranges;`, a
  direct reference assignment, replaces what used to be a full re-`.put()` loop.
- This meant `DispatchMatcherConstruct`'s two `RangeMap`-taking constructors and its `populate()`
  helper (all in `MatcherConstruct.java`, a second file) also needed to switch to
  `CodePointMap<PatternConstruct>` -- `populate()` still builds the actual runtime `dispatchMap`
  (see below) as a Guava `RangeMap<Integer, MatcherConstruct>`, converting each `CodePointMap.Range`
  into a Guava `Range.closedOpen(...)` at that one boundary, since `dispatchMap` itself wasn't
  touched in this pass.
- Result: `llkCompile` 16.4ms -> **16.0ms/op**, a further small improvement (removing the copy loop)
  on top of the entryMap migration's much larger one -- combined, -52.7% from the pre-fix 33.8ms,
  and still comfortably below the original 21.5ms pre-`ArrayCodePointMap` baseline. Full suite
  (1477 tests, 0 failing, 561 skipped) passed on the first try.
- The `llkMatch` anomaly from the `entryMap` migration **persisted at a similar magnitude** (+13.9%
  this run vs. +12.3% before) even though this migration didn't touch anything on the actual
  match-time dispatch path (`MultiDispatchingMatcherConstruct.dispatchMap` -- see below -- is still
  the same Guava `RangeMap<Integer, MatcherConstruct>` both before and after). Two migrations in a
  row moving that same needle by roughly the same amount, with neither one plausibly touching
  match-time code, points more toward a *benchmark methodology* artifact than a real regression:
  `CorpusBenchmark`'s `jmh {}` config runs with `fork = 1`, so `llkCompile` (now much more
  compile-work-per-iteration than the original Guava-backed version) and `llkMatch` run sequentially
  in the *same* JVM process/fork -- JIT/code-cache state left over from the compile-heavy phase could
  plausibly leak into the match phase's numbers without any real `Matcher` regression existing.
  Not confirmed yet; see remaining_work.md's dispatchMap item for the actual next step (an isolated
  single-benchmark run) before assuming there's a real bug.
- `MultiDispatchingMatcherConstruct.dispatchMap` (`RangeMap<Integer, MatcherConstruct>`, the actual
  structure `Matcher` consults via `getNext()` on every `find()`/`matches()` call) is now the last
  Guava map left in this family -- deliberately not touched in either of today's two migrations, and
  tracked as its own remaining_work.md item, partly *because* it's on the real match-time hot path
  (unlike `entryMap`/`rawEntryMap`, which are compile-time-only), so migrating it needs more care.

### `dispatchMap` migration -- the last Guava RangeMap, and the `llkMatch` mystery resolved (2026-09-08, same day)

- Migrated `MultiDispatchingMatcherConstruct.dispatchMap` from Guava `RangeMap<Integer,
  MatcherConstruct>` to `ArrayCodePointMap<MatcherConstruct>` -- the last Guava `RangeMap` anywhere
  in the compiled-graph/entry-point/runtime-dispatch family (confirmed by grep: the only remaining
  `RangeMap`/`TreeRangeMap` reference in `llkpattern/src/main/java` afterward is `TreeCodePointMap`
  itself, the deliberate differential-test oracle). `CodePointMap`'s existing `get(int)` method
  matched `RangeMap.get(int)`'s call shape exactly, so `getNext()` and the one test reading
  `getDispatchMap().get(...)` (`PatternParserTest`) needed no changes at all -- only the `.put()`
  call sites (all `Range.closedOpen(min, max)` -> plain `min, max` ints) and `getDispatchMap()`'s
  return type.
- This was the one migration of the day that touches the *actual match-time hot path*, not just
  compile time -- and it resolved the `llkMatch` mystery from the `entryMap`/`rawEntryMap`
  migrations decisively: that "benchmark methodology artifact" theory was wrong. `dispatchMap`'s
  Guava backing was a real, standing cost on every single `find()`/`matches()` call, present since
  before this whole investigation started (not something the earlier `ArrayCodePointMap` swap or
  its regression introduced) -- migrating it didn't just undo the earlier bump, it took `llkMatch`
  well *below* where it started the whole day.
- Final numbers, this session's whole `ArrayCodePointMap`/`CodePointMap` migration arc, measured
  against the very first pre-`ArrayCodePointMap` baseline: `llkCompile` 21.5ms -> **11.2ms/op**
  (-48.1%), `llkMatch` 0.092ms -> **0.066ms/op** (-27.7%). Full suite (1477 tests, 0 failing, 561
  skipped) passed on the first try after this change, as it had after every other step in this arc.

### `CodePointMap.complement`/else-value implementation (2026-09-08, same day)

- After eliminating every Guava `RangeMap`, the project owner asked to eliminate `RangeSet` too,
  flagging the `unicodeanalyzer`-generated `UnicodePredicates.java` as the hard part. Scoping (via
  grep across `PatternParser`/`NamedCharClass`/`PatternConstruct`/`MatcherConstruct`) found the
  real blocker was elsewhere: `PatternParser` builds `ComplexCharacter.ranges` using `RangeSet`'s
  `complement()` for DOT-under-`DOTALL`, `[^...]` negation, and `\P{...}`, plus `&&` intersection
  implemented *as* `a.removeAll(b.complement())` -- none of which `CodePointMap` supported yet
  (`complement()`'s old default, `ComplementCodePointMap`, had `entrySet()`/`intersection()`
  throwing `UnsupportedOperationException` -- it was never finished because nothing needed it
  while only `RangeMap` was being migrated).
- The project owner then proposed the fix directly: give every map an `elseValue` -- the value
  implicit for any code point without an explicit entry -- rather than a separate complement
  wrapper type. Implemented as described in design.md's "Code point range representation" section:
  since the code point domain is bounded, `complement()` is always finite (a normal map, else-value
  = the complement's value, one internal "punched hole" -- a `null`-valued entry, never exposed
  externally -- per entry of the source's own `entrySet()`). This is why it works where Guava's
  `RangeSet#complement()` (over all of `Integer`) can't be enumerated: bounding the domain turns an
  unbounded operation into a finite one for free.
- `intersection(min, max)`/`intersectionRejectingConflicts` needed a small adjustment: their
  existing raw-array/`RangeMap`-window scans only see real entries, not an else-value's implicit
  fill, so both implementations now fall back to iterating the (else-value-aware) `entrySet()`
  clipped to the window when the receiver actually has an else-value, keeping the existing fast
  array-window path for the common (no else-value) case. Every other operation (`union`, `putAll`,
  `difference`) needed *no* else-value-specific logic at all, since they're all built on `other
  .entrySet()`, which already resolves the fill into concrete (finite) entries.
- Added `CodePointMapDifferentialTest.complement_agreesWithTreeCodePointMap` covering complement,
  double-complement (should round-trip), and union/intersection mixing a complement with an
  ordinary map -- fewer trials (20, not 200) than the other differential tests, since the test's
  `normalize()` re-splits every entry into individual code points and an else-value's gap-fill can
  span nearly the whole domain. Full suite green afterward: 1478 tests, 0 failing.
- This lands the `CodePointMap` infrastructure `complement` needs; migrating the actual `RangeSet`
  call sites (`ComplexCharacter`, `containsFolded`, `NamedCharClass`/`UnicodePredicates`) onto it is
  still open -- see remaining_work.md.

### `getElseValue`/`setElseValue`/`getExplicit`, and simplifying `MultiDispatchingMatcherConstruct` (2026-09-08, same day)

- The project owner asked for public `getElseValue`/`setElseValue` accessors specifically to let
  `MultiDispatchingMatcherConstruct` store its "else" successor as `dispatchMap`'s own else-value
  instead of a separate `elseDispatch` field -- the field had been hand-rolling exactly what an
  else-value is. Adding the setter (the getter already existed, from the `complement` work above)
  made that replacement mechanical everywhere `elseDispatch` was *written*.
- The read side (`getNext()`) needed one more piece first: its case-insensitive fallback checks
  `dispatchMap.get(peeked)`, and if not found, the input character's other-case forms, only falling
  back to the "else" successor once none of those match. Folding the else-value straight into
  `dispatchMap.get()` breaks that ordering -- `get(peeked)` would return the else-value the moment
  `peeked` itself isn't an explicit key, short-circuiting before the upper/lower-case checks ever
  run (e.g. an explicit branch for lowercase `'b'` would never be tried for uppercase input `'B'`
  under `CASE_INSENSITIVE`, since `get('B')` returns the else-value first). Added
  `CodePointMap#getExplicit(codePoint)` -- like `get`, but ignoring the else-value fill entirely --
  for `getNext()`'s intermediate lookups, reserving `getElseValue()` for the final fallback only.
  Caught this by tracing through the interaction before writing the change, not by a test failure --
  worth flagging since it's the kind of thing a differential test wouldn't have caught either (both
  `ArrayCodePointMap`/`TreeCodePointMap` would have been *consistently* wrong the same way).
- Also found (not fixed; noted here in case a future editing pass reaches it) that
  `MultiDispatchingMatcherConstruct.getElse()` has zero callers anywhere in the codebase --
  apparently dead even before this change, unrelated to it.
- Full suite green: 1478 tests, 0 failing, no count change (pure refactor).

### Migrating `ComplexCharacter` off Guava `RangeSet` onto `CodePointMap` (2026-09-08, same day)

- The project owner asked to finish the `RangeSet` elimination now that `complement`/else-value
  existed. Scoped bottom-up (leaf to root, not starting at `PatternParser`) since each layer's type
  change forces the next: `CharacterClass.java` first (confirmed dead, deleted alone before
  anything else -- a clean recompile with it removed had zero errors), then `MatcherConstruct
  .containsFolded`/`SingleCharMatcherConstruct.validRanges` (the profiled hot path), then
  `PatternConstruct.ComplexCharacter.ranges`/`validRanges()`/`firstCharSet`/`lastCharSet`/
  `WordBoundaryConstruct`, then `PatternParser`'s 15-ish `RangeSet`-building call sites, compiling
  and testing after each layer.
- The one real correctness trap, caught before it became a bug (not via a test failure): `.
  validRanges()`'s whole reason for existing used to be clamping Guava's unbounded `complement()`
  to `[0, MAX_CODE_POINT]` so it couldn't swallow `-1` (Matcher's end-of-input sentinel).
  `CodePointMap`'s else-value fill is already bounded to that same domain by construction (see the
  `complement`/else-value entry above) -- so the clamp itself is unnecessary now, but `-1` isn't:
  `getOrDefault`/`get` don't domain-check their argument at all, so an else-valued `ranges.get(-1)`
  would still return the else-value, wrongly reporting `-1` a "member." Fixed by having
  `containsFolded` check `peeked == -1` first and unconditionally, *before* consulting the map --
  deliberately different from `MultiDispatchingMatcherConstruct.getNext()`'s use of `getExplicit()`
  from the previous entry above: `getNext()` needs to distinguish "no explicit entry" from "else-
  value fill" for its own case-fold fallback, but `containsFolded`'s fill genuinely IS membership
  (a negated class matches everything it doesn't exclude) -- using `getExplicit()` here would have
  made a negated class wrongly fail to match its own else-value-filled members. Added
  `RangeSetMigrationTest` to lock this in, plus DOTALL-at-end-of-input and a DOTALL branch inside a
  union (to confirm `findFirstOverlap`/ambiguity detection still see an else-valued "everything"
  branch as claiming everything, not as empty).
- `&&` intersection (`[a-z&&[^aeiou]]`) used to be `a.removeAll(b.complement())` (Guava `RangeSet`
  has no in-place intersect) -- replaced with a direct intersection (per `a`-entry, `b.intersection
  (min, max)`, which is already else-value-aware) rather than porting the complement-based formula,
  since materializing a complement just to subtract it would be wasted work now that a real
  intersection is just as easy to write.
- `NamedCharClass`/`UnicodePredicates` were deliberately left untouched, exactly per the plan from
  the `complement` session above: their `.complement()`/union-heavy static initializers already
  have documented circular-init fragility, so converting per-constant there would run that machinery
  through `CodePointMap` during class init instead of at consumption. Added
  `RangeSetCodePointMaps.toCodePointMap` as the one-time adapter at the actual consumption points
  (`PatternParser`'s `\d`/`\p{...}`/`\R` handling and `WordBoundaryConstruct`'s `\w` lookup) --
  eagerly clamped/materialized rather than else-valued, since these are one-time, already-fully-known
  conversions where that's simplest.
- Full suite green: 1484 tests, 0 failing (1478 + 6 new `RangeSetMigrationTest` cases). The only
  Guava `RangeSet`/`RangeMap` left anywhere in `llkpattern/src/main` is `NamedCharClass.java`/
  `UnicodePredicates.java` (deliberately, as above), `RangeSetCodePointMaps.java` (the adapter
  itself), the one boundary line in `PatternParser` that calls `NamedCharClass.get(...)`, and
  `TreeCodePointMap` (the deliberate `CodePointMap` test oracle) -- everything else in the compiled-
  graph/entry-point/runtime-dispatch/character-class family is `CodePointMap` now.
- JMH afterward confirmed this was a real win, not just cleanup -- `containsFolded` was genuinely
  on the match-time hot path, same as `dispatchMap` was for the `RangeMap` elimination: `llkCompile`
  11.15ms -> **7.05ms/op** (-36.8% further), `llkMatch` 0.0663ms -> **0.044ms/op** (-33.6% further).
  Against the very first pre-`ArrayCodePointMap` baseline (21.48ms/0.0917ms), that's **-67.2%
  compile / -52.0% match** for this whole multi-day `CodePointMap` migration arc, `RangeMap` and
  `RangeSet` combined.

### Migrating `NamedCharClass`/`UnicodePredicates` off Guava `RangeSet` onto `CodePointMap` (2026-09-08, next day)

- The project owner asked to finish the elimination that the two sessions above deliberately deferred
  (the "circular-init fragility" note on `NamedCharClass`'s union-heavy static initializers). Done
  bottom-up as before: `unicodeanalyzer`'s generator first, then `NamedCharClass`, then the two
  remaining consumption points.
- `UnicodeAnalyzer` now emits `UnicodePredicates` as `CodePointMap<Boolean>` fields built via
  `ArrayCodePointMap#appendSorted` (previously `ImmutableRangeSet.Builder`). `categories()`/
  `scripts()`/`printRanges()` pull their ranges out of a `HashMap`/`HashSet`, so (unlike the already-
  ascending-order `intPredicate()` scan) they needed an explicit sort by `min` first --
  `appendSorted` requires ascending order and silently corrupts the map otherwise, unlike the old
  `Builder`, which tolerated any insertion order. The generator now also emits the file's package/
  imports/class declaration/closing brace itself (previously hand-assembled once and pasted around),
  including a "DO NOT EDIT" javadoc and a `javax.annotation.processing.Generated` annotation (not
  `javax.annotation.Generated` -- that one was removed from the JDK in 9) carrying the generation
  date -- this doubles as the "documented/scripted way to regenerate" item from remaining_work.md,
  via the new `./gradlew :unicodeanalyzer:generateUnicodePredicates` task.
- Found and fixed a real Gradle bug while wiring that task up: `standardOutput = new
  FileOutputStream(path)` assigned directly in the task block runs at project*-configuration* time,
  not task-execution time -- since this is a multi-project build, that meant the file got truncated
  to zero bytes on *every* Gradle invocation that touches this project, including an unrelated
  `:llkpattern:compileJava`. Fixed by moving the `FileOutputStream` construction into `doFirst {}`.
  Cost a couple of confusing "cannot find symbol: UnicodePredicates" compile failures before the
  actual cause (an empty file, not a real missing class) was traced.
- `NamedCharClass`'s ~200 hand-written literal `Range.closed`/`singleton`/`closedOpen` calls all
  needed individual `[min, max)` conversion (Guava's `closed`/`singleton` are inclusive-max) --
  no mechanical find/replace covers this, since the right `+1` depends on each call's own bound
  type. Replaced the `ImmutableRangeSet.Builder` chains with a small `build(Consumer<
  MutableCodePointMap<Boolean>>)` helper backed by plain `put` (not `appendSorted`, since several of
  these literals -- `Hex_Digit`'s a-f/A-F/0-9/fullwidth-digit ordering, `h`/`v`/`R` similarly -- add
  entries out of ascending order, which `appendSorted` doesn't tolerate).
- The other real trap: `CodePointMap#complement(value)` is an else-value fill whose `entrySet()` is
  the *holes*, not the complement's members -- silently inverting anything that iterates entries
  (unions, `PatternParser`'s ambiguity check) rather than just calling `get()`/`containsKey()`. Every
  complement-derived constant (`Assigned`, `Graph`'s unicode side, and `RegexCharacterClass`'s `D`/
  `H`/`S`/`V`/`W`) now goes through a `materializedComplement` helper that sweeps `[0,
  MAX_CODE_POINT]` and builds real entries instead -- mirroring the choice the retired
  `RangeSetCodePointMaps.toCodePointMap` made for the same reason. `PatternParser`'s own `\P{...}`
  handling needed an identical `materializeComplement` (its `[^...]`/`DOTALL` negation, by contrast,
  correctly keeps using `CodePointMap#complement`'s else-value fill as-is, since that result becomes
  a `ComplexCharacter`'s entire `ranges` field rather than being merged via `putAll` into an
  already-populated one -- `putAll` only copies explicit entries, which is exactly what would drop
  an else-value fill on the floor).
- `RangeSetCodePointMaps.java` (the one-time Guava-to-`CodePointMap` adapter from the prior session)
  is now dead and deleted -- `PatternParser`/`WordBoundaryConstruct` call `NamedCharClass`/
  `RegexCharacterClass`'s `get(...)` directly, since it already returns a `CodePointMap<Boolean>`.
- No golden-membership dump test was added (the existing `PosixAndJavaClassTest`/
  `PredefinedClassTest`/`RangeSetMigrationTest`/`UnicodeClassTest` coverage of the ASCII-vs-Unicode
  split and every named class stayed green with no test-count drop, which is what would have caught
  a materialization/complement/off-by-one regression here).
- Full suite green: 1484 tests, 0 failing -- same count as the prior session (pure internal
  representation change, no behavior change intended or observed).
- JMH afterward, run on the same JDK 25 as the committed baseline (this dev machine's Gradle
  daemon needs JDK 17/21 -- see the toolchain note above -- but the `me.champeau.jmh` plugin's
  `jvm` option can fork the *benchmark* process on a different JDK independently of Gradle's own):
  `llkCompile` 7.05ms -> 9.65ms/op, `llkMatch` 0.044ms -> 0.067ms/op raw -- looks like a real
  regression at first glance, but the *unrelated* `regexCompile`/`regexMatch` benchmarks (pure
  `java.util.regex`, untouched by this change) moved by almost the identical percentage in the same
  run (0.096ms -> 0.140ms, 0.047ms -> 0.070ms) -- so this was the machine running ~45% slower
  across the board that particular time, not a code regression. Normalizing against that run's own
  regex numbers as an in-run control: `llkCompile/regexCompile` 73.3x -> 69.1x (slightly better) and
  `llkMatch/regexMatch` 0.938x -> 0.953x (flat, within noise) -- confirms the predicted outcome:
  since this migration only touches one-time static init and parse-time construction, not the
  match-time hot path the `ComplexCharacter`/`dispatchMap` migrations touched, it has no real effect
  on `llkMatch`, and if anything a slightly positive one on `llkCompile`. Committed
  `corpus_benchmark_results.json` reflects this run; a future `git diff` against it should likewise
  sanity-check `regexCompile`/`regexMatch`'s movement before reading `llkCompile`/`llkMatch`'s at
  face value, if the machine's load might have changed between runs.

## On-device corpus benchmark added (2026-09-08)

- Built `AndroidCorpusBenchmark` (`app/src/androidTest/java/.../corpus/`) to mirror
  `CorpusBenchmark` on real phones -- replaces the boilerplate `ExampleInstrumentedTest.java` that
  was `app/`'s only prior content, answering the "what is `app/` for" question below: it's now the
  on-device benchmark harness, not leftover `File > New Project` scaffolding.
- Written in a separate session running in the background while another session ("llkpattern
  eliminating last RangeSet") was actively modifying main code and running desktop JMH -- so this
  work touched only `app/` plus doc files, deliberately avoided touching anything under
  `llkpattern/src/main` or `llkpattern/build.gradle`'s `jmh {}` block, and was not built/run (no
  Android SDK/device available in that session) to avoid competing for machine resources during the
  other session's perf runs. See remaining_work.md's on-device-benchmark entry for the
  not-yet-verified checklist this leaves behind -- in particular, actually running
  `./gradlew :app:connectedAndroidTest` against hardware once both this and the main-code session
  have settled.
- JMH doesn't run on Android, so timing is hand-rolled (`System.nanoTime`, fixed warmup/measured
  iteration counts) rather than reusing JMH's harness -- cruder (no fork isolation, no statistical
  rigor) but sufficient for coarse cross-device comparison.
- Considered reusing `GoldenRow`/`GoldenTsv` directly from `llkpattern`'s `test` source set instead
  of writing `AndroidGoldenRow`/`AndroidGoldenTsv`: rejected because (a) Gradle doesn't expose one
  project's `test` source set output to another project without extra plumbing, and (b)
  `GoldenTsv.read(Path)` goes through `java.nio.file`, which needs API 26+ or core library
  desugaring that `app/` (`minSdk 19`) doesn't otherwise need. Instead the two golden TSVs are
  copied into `app`'s androidTest assets at build time (`copyGoldenAssetsForAndroidTest` in
  `app/build.gradle`, into a `build/` output dir, not checked in) so `llkpattern`'s copies stay the
  single source of truth, and read on-device with a small standalone parser.
- `minSdk 19` is stale per the API-floor note above (project floor is actually 26) but left alone
  here as out of scope for this task -- worth revisiting together with that note.
- Confirmed clean-tree via git status before building, and the other session confirmed idle
  (`isRunning: false`) before I ran any Gradle build, per its coordination request.
- First real build attempt (`./gradlew :app:assembleDebugAndroidTest`) failed on two issues, both
  pre-existing in `app/build.gradle` and unrelated to this test's own code:
  1. `compileSdk 33` was too old for `appcompat:1.6.1`/`material:1.11.0`'s transitive
     `androidx.activity:1.8.0`, which requires `compileSdk` 34+. Bumped to 36 rather than 34
     because platform 36 (and 37.1) were already installed locally and 34 wasn't -- avoided a
     network fetch inside this sandboxed session. AGP 8.5.1 warns it's only tested through
     compileSdk 34, but the build succeeds regardless.
  2. Adding `androidTestImplementation project(':llkpattern')` then failed dependency resolution
     with a Guava `listenablefuture` capability conflict: `llkpattern` depends on Guava's `-jre`
     flavor (a real `listenablefuture:1.0` jar), while `androidx.test:core`/
     `androidx.concurrent:concurrent-futures` depend on Guava's `-android` flavor's empty stub of
     the same coordinates -- Gradle can't pick one artifact for both capability claims. Fixed with
     Guava's own documented workaround, `configurations.all { exclude group: 'com.google.guava',
     module: 'listenablefuture' }` (https://github.com/google/guava/issues/2960).
  With both fixes, `:app:assembleDebugAndroidTest` and `:app:connectedDebugAndroidTest` (via
  Gradle, which auto-installs/uninstalls) both succeeded against the attached Pixel 3a (API 32,
  device id 93EAY0A967): all 5 real `@Test` methods passed, `testZZSamplingProfile` skipped as
  designed (no `-e profile true`). To actually inspect the results JSON rather than have it
  vanish with Gradle's post-test uninstall, ran a second pass manually (`adb install` both APKs,
  `adb shell am instrument -w ...`, `adb pull`, then `adb uninstall` both packages to leave the
  device clean) -- see remaining_work.md's on-device-benchmark entry for the resulting numbers.
  `benchmarks/Google_Pixel_3a_sargo_corpus_benchmark_results.json` is committed as a
  first real-device baseline, alongside the desktop JMH one.
- The first run used the defaults copied from the desktop `CorpusBenchmark` (0.25 fraction, 3
  warmup/5 measured iterations) and finished in ~4 seconds -- nowhere near using a device's spare
  compute budget. Reset to the full corpus (`FRACTION_OF_TEST_ROWS = 1.0f`, 406 rows) with
  `WARMUP_ITERATIONS`/`MEASURED_ITERATIONS` bumped to 50/1000; that run took ~124s wall-clock on
  the Pixel 3a (`am instrument`'s own "Time:" line), comfortably under a 5-minute target with
  margin for slower devices, while getting far more measured iterations than the crude
  hand-rolled timing loop would otherwise get.
- Tried `Debug.startMethodTracingSampling` first for the CPU-sampling test, but it has no
  parameter to cap stack depth (only buffer size and sample interval) -- when the project owner
  asked for ~8-frame-deep samples specifically, replaced it with a small hand-rolled sampler
  instead: a daemon thread wakes every `SAMPLE_INTERVAL_MILLIS` (2ms), snapshots the benchmark
  thread via `Thread.getAllStackTraces().get(targetThread)`, truncates to `STACK_SAMPLE_DEPTH`
  (8) frames, and tallies occurrences of that exact 8-frame chain in a `HashMap`. Output is a
  plain-text table (count, %, chain) sorted by frequency rather than a binary trace file -- no
  Android Studio Profiler import needed, and the depth cap keeps chains readable directly.
  Committed at `benchmarks/Google_Pixel_3a_sargo_llkMatch_sampling.txt` (275 samples,
  133 distinct 8-frame chains over `PROFILE_ITERATIONS = 200` full-corpus passes); replaces an
  earlier `.trace`-file version of this test that was captured, then deleted once the hand-rolled
  version replaced it (see remaining_work.md).
- Per the project owner's request, `testZZSamplingProfile` (and its supporting `formatChain`/
  `writeSamplingProfile` methods, its two constants, and its sampling-only imports) were then
  commented back out in `AndroidCorpusBenchmark.java` -- the checked-in file now only runs the four
  timing benchmarks by default, with the sampling code left in place as a ready-to-uncomment block
  (line-commented rather than wrapped in `/* */`, since the block itself contains javadoc `/** */`
  comments that would otherwise close a wrapping block comment early) so the how-to isn't lost, but
  it doesn't show up as a runnable `@Test` in the common case. Verified this still compiles and
  that a normal run now shows "OK (4 tests)" with no fifth skipped test.

### CPU-sampling `llkCompile`/`llkMatch`, and fixing the `put()`-loop `appendSorted` regression it found (2026-09-08, same day)

- The project owner asked for a local JMH run with CPU sampling, to see where compile-time cost
  actually goes on this dev machine. JMH's built-in `stack` profiler (no external agent needed --
  add `'stack'` to the `jmh { profilers = [...] }` list) at its default depth (leaf frame only)
  immediately named `ArrayCodePointMap.put` as ~32% of `llkCompile`'s RUNNABLE samples -- but a
  leaf-only sample can't say *why* it's hot. Re-ran with `'stack:lines=8;detailLine=true'` (8-frame
  stacks with line numbers) to get real call chains, which is what actually answered the project
  owner's follow-up question.
- The 8-frame stacks traced every hot `put()` call back to the same shape, all over
  `PatternConstruct`'s `buildEntryMap`/`buildMatcher` and `MatcherConstruct`'s `populate`/loop-
  dispatch construction: copy (or value-transform, e.g. `PatternConstruct` -> its `.matcher`) an
  already-sorted `entrySet()` (a merge result, a child construct's own entry map, etc.) one entry at
  a time via `put()` into a destination that starts completely empty. This is exactly the case
  `appendSorted` exists for (see design.md) -- but these ~9 call sites had never been switched over
  from `put()`, apparently missed when `appendSorted` was added and when `putAll`'s optimized linear
  merge was written (both from earlier sessions in this same `CodePointMap` arc, per the project
  owner's recollection) since none of them are a `putAll` (they're copies into a *fresh* map, or
  per-entry value transforms `putAll` can't express directly) -- easy to walk right past when
  auditing for `putAll` opportunities specifically.
- Fixed by switching all ~9 sites (`PatternConstruct`: `QuantifiableConstruct.buildLoopEntryMap`,
  `QuantifiedUnion.buildEntryMap`'s bare-flags-group and main branches, `CaptureEndMarker`,
  `Sequence.buildEntryMap`, `BackReference`, `ComplexCharacter`, `ComplexQuantifiedCharacter`;
  `MatcherConstruct`: `DispatchMatcherConstruct`'s loop-flavored constructor (both the capturing
  `bodyEntries` and non-capturing `loopNode.dispatchMap` branches, plus the main `dispatchMap` loop),
  and `populate()`) from `put(min, max, value)` to `appendSorted(min, max, value)`. Safe because
  every one of them starts from a fresh, empty destination map and only ever filters (never
  reorders) an already-ascending source -- a filtered subsequence of an ascending sequence is still
  ascending, so `appendSorted`'s ascending-order requirement holds even at the sites that skip some
  source entries (e.g. the loop-dispatch sites' `if (e.getValue() != next)` guard). `NamedCharClass`'s
  hand-written literal-building `build()` helper is deliberately NOT among these -- see its own doc
  for why those specific literals are NOT in ascending order.
- Full suite green: 1484 tests, 0 failing -- no count change (pure internal optimization, no
  behavior change).
- JMH (JDK 25, matching the committed baseline): `llkCompile` **7.05ms -> 2.85ms/op (-59.5%)**,
  `llkMatch` 0.044ms -> 0.048ms/op (flat -- this fix is entry-map/dispatch-map *construction*, not
  the match loop itself; `regexMatch` moved by a similar small amount in the same run, so this is
  noise, not a regression). This is by far the single biggest win in the whole multi-day
  `CodePointMap`/`RangeMap`/`RangeSet` elimination arc -- bigger than the `dispatchMap` or
  `ComplexCharacter` migrations that motivated switching off Guava in the first place, which makes
  sense in hindsight: those earlier migrations got the *representation* right but left the
  `CodePointMap`-level construction code still calling the general-purpose `put()` everywhere,
  never actually switched over to the bulk-append path it was designed to enable.

### Aliasing `entryMap` instead of copying it (2026-09-08, same day)

- The project owner's next optimization idea after the `appendSorted` fix: "if the crux is all the
  copies, then let's not make copies." Before committing to their fuller `CodePointMapBuilder`/
  push-visitor proposal, re-profiled with an 8-frame stack sample (checked in as
  `benchmarks/Intel-i7-9750H_llkCompile_sampling.txt`/`..._llkMatch_sampling.txt`) to
  confirm `CodePointMap` construction was still the dominant cost post-`appendSorted` -- it was
  (every top `llkCompile` entry traced to an `appendSorted`/`putAll` call, ~9 sites at 2-4.5% each,
  no longer one dominant offender) -- and to correct an overstated claim made mid-investigation
  ("almost entirely `appendSorted`" understated the real signal, which was `llkCompile`'s
  `gc.alloc.rate.norm` at 20x `regexCompile`'s, not the ms/op breakdown).
- Landed a smaller, lower-risk slice of the full push-visitor idea: `PatternConstruct.entryMap`'s
  field type changed from `MutableCodePointMap<Boolean>` to plain `CodePointMap<Boolean>`, and
  every `buildEntryMap()` override whose own entry point is defined to be exactly some other
  construct's (`Sequence`, `CaptureEndMarker`, a bare-flags `QuantifiedUnion`, `BackReference`,
  `ComplexCharacter`, `ComplexQuantifiedCharacter`) now aliases that other map directly instead of
  copying its entries -- 6 of the 9 profiled copy sites eliminated outright. Deliberately did NOT
  widen this to `QuantifiedUnion.rawEntryMap`/`QuantifiableConstruct.buildLoopEntryMap`'s merge
  result (genuinely `PatternConstruct`-valued) -- that's exactly the shape of a real 2026-09-06 bug
  (`CaptureEndMarker` aliasing a `PatternConstruct`-valued map, breaking a loop's continue-vs-exit
  `==` check) that `entryMap`'s Boolean-only value type now guards against; those two sites keep
  projecting to a genuinely new Boolean-valued map. See design.md's new "`entryMap` aliasing"
  section for the full per-construct breakdown. Changing the field's declared type first (rather
  than auditing call sites by hand) is what made every remaining illegal mutation site a compile
  error instead of a hoped-for invariant -- the compiler enumerated all of them.
- Also, while reviewing this: `ArrayCodePointMap`'s private complement constructor and
  `appendSorted` got three more fixes on top of the previous commit's fast path (project owner's
  own review): the else-valued-source branch allocates its arrays at `source.size` directly instead
  of `INITIAL_CAPACITY`-then-`ensureCapacity`; `appendSorted`'s per-chunk `ensureCapacity` calls
  (up to ~537 for one huge range) collapsed into a single upfront one; and confirmed (rather than
  defensively re-checked at runtime) that a null-valued entry can't coexist with `elseValue == null`
  in this codebase, since nothing calls `setElseValue(null)` on an already-else-valued map.
- `PatternParser#parseComplexCharacter`'s two `toMutable(...)` calls were also both unnecessary
  (per the project owner) -- `intersect()`'s and `complement()`'s return types are provably already
  mutable at each call site (a `MutableCodePointMap<Boolean>` ternary, and `ArrayCodePointMap`'s own
  `complement()` override always building another `ArrayCodePointMap`) -- replaced with a direct
  assignment and an explicit cast (with a comment explaining why it's always safe) respectively;
  `toMutable()` itself deleted as dead code.
- Full suite green throughout: 1484 tests, 0 failing -- no behavior change, confirmed in particular
  by `GroupSyntaxTest` (the test that caught the original `CaptureEndMarker` aliasing bug this
  change's safety argument rests on) and the two historical identity-check regression tests
  (`186d74a`'s nested-quantifier case, `b50a6b1`'s quantified-loop-then-composite case).
- JMH (JDK 25, matching the committed baseline): `llkCompile` 2.85ms -> **1.872ms/op (-34.3%
  further)**, `gc.alloc.rate.norm` 8.44MB/op -> **4.96MB/op (-41.3%)**; `llkMatch` 0.048ms ->
  0.049ms/op, flat as expected (this is a compile-time-only change). Running total for this
  session's `CodePointMap` construction work: `llkCompile` 7.05ms -> 1.872ms/op, **-73.4%**, on top
  of the RangeSet-elimination arc's own prior gains.
- One JMH run mid-session had unusually wide error bars (stdev ~18% of the mean, vs. the usual
  5-6%) -- traced to the project owner having a video playing in the background during capture, not
  a code issue. Worth remembering as a source of noise distinct from the earlier `compileJava`-
  contamination issue (see the `appendSorted` entry above): both look the same in the numbers (high
  variance, no crash), so when a run looks unusually noisy, ask what else was running rather than
  assuming the change itself is the cause.

### Confirming three specific ambiguity shapes have test coverage (2026-09-08, same day)

- The project owner asked directly whether `(a|ab)`, `a?a`, and `[ab]?a` -- the three cases they'd
  identified as needing genuine compile-time `CodePointMap` work for ambiguity detection -- were
  actually tested. Checked by grep before answering rather than assuming: none of the three exact
  patterns existed anywhere in the suite, though structurally similar shapes did (`"ab|ac"` for a
  shared-first-character union, `".*z"`/`".+z"` for a quantified class vs. a following literal) --
  none of those cover a strict-prefix union, a `min == 0` quantifier's own body-vs-next merge over
  a single code point, or that same merge over a real multi-entry class.
- Added the three as `QuantifierAndCaptureTest` cases, each just asserting
  `PatternSyntaxException`. Confirmed by running (not just written speculatively) before reporting
  back: all three throw as expected. Full suite: 1487 tests (1484 + 3), 0 failing.

### Re-running the Pixel 3a corpus benchmark after this session's compile-time fixes (2026-09-08, same day)

- The project owner unlocked their Pixel 3a and asked for the on-device benchmark to be re-run,
  since the checked-in numbers predated this session's `appendSorted`/`entryMap`-aliasing work.
  `./gradlew :app:connectedAndroidTest` ran clean (4/4 tests, 0 failed) -- but the results JSON
  (written to the app's external-storage files dir) was gone by the time it could be pulled:
  that Gradle task uninstalls both APKs after the run, and uninstalling an app on this device wipes
  its `/sdcard/Android/data/<package>/files/` directory along with it, per standard Android
  behavior. Confirmed via `adb shell pm list packages` (neither `com.tbohne.llkpattern` nor its
  `.test` package existed post-run) before concluding this rather than guessing.
- Worked around by installing both already-built APKs manually (`adb install -r` on
  `app/build/outputs/apk/debug/app-debug.apk` and `.../androidTest/debug/
  app-debug-androidTest.apk`) and running via `adb shell am instrument -w
  com.tbohne.llkpattern.test/androidx.test.runner.AndroidJUnitRunner` directly -- bypassing
  Gradle's own install/uninstall lifecycle entirely, same invocation style already documented for
  the (separate) sampling test. Pulled the JSON immediately afterward via `adb pull` (needed
  `MSYS_NO_PATHCONV=1` in this git-bash environment -- without it, bash mangles the leading
  `/sdcard/...` into a Windows path before it reaches `adb`).
- New committed baseline: `llkCompile` 101.7ms -> **41.47ms/pass** (~2.4x, consistent with the
  desktop-side `-73.4%` this session's fixes produced), `llkMatch` 1.54ms -> **1.26ms/pass**;
  `regexCompile`/`regexMatch` essentially unchanged (6.70->6.56ms, 3.77->3.77ms), as expected since
  nothing touched by this session's fixes affects `java.util.regex` at all -- a useful sanity check
  that the improvement is real, not measurement drift. Updated `README.md`'s benchmark table and
  remaining_work.md's "On-device (Android) corpus benchmark" section accordingly.

### `CodePointMapBuilder`/`addCodePointsTo`: two false starts before the real win (2026-09-08)

- The project owner's stated design: the few `PatternConstruct`s that actually need a real
  `CodePointMap` (`ComplexCharacter`, `QuantifiedUnion`/`ComplexQuantifiedCharacter` for ambiguity
  detection, `MultiDispatchingMatcherConstruct` for dispatch) would own a `CodePointMapBuilder<T>`
  and call `addCodePointsTo(builder, value)` on candidates to populate it; everything else would
  delegate. First landed the builder itself (`CodePointMapBuilder`, replacing
  `toValueMap`/`mergeEntryMapRejectingAmbiguity`/`findFirstOverlap` -- one allocation for the whole
  merge instead of one per candidate plus a nested-loop overlap scan) -- real, measured win on its
  own, no further work needed to realize it.
- Adding `addCodePointsTo` overrides on top (so a leaf candidate never even builds/caches its own
  `entryMap`) looked right by inspection but **did nothing** on the first attempt: `mergeEntryPoints`
  still called `candidate.getEntryElse()` *before* `candidate.addCodePointsTo(...)`, and
  `getEntryElse()` shares `ensureEntryPointBuilt()` with `getEntryPointMap()` -- so every candidate's
  full `buildEntryMap()` (and its allocation) ran anyway, just via a different call site than
  before. Fixed by adding `claimsEntryElse()`, a push-safe mirror of `addCodePointsTo` for exactly
  the "does this candidate claim the catch-all" question, so nothing forces the pull.
- Even with that fixed, a targeted canary (temporarily making `LiteralString.buildEntryMap` throw,
  then compiling `"a|b"`) still fired. Root cause: `PatternConstruct.compile()` calls
  `ensureEntryPointBuilt()` **unconditionally** before `buildMatcher()`, for every construct, always
  has (this is the same call the project owner asked to remove earlier this session, and was told
  not to -- correctly, for constructs whose `buildMatcher()` *does* read `buildEntryMap()`'s output).
  So even after the merge itself stopped pulling, `QuantifiedUnion.buildMatcher`'s `part.compile(...)`
  loop over each branch re-triggered the exact same pull independently. Fixed with a new
  `needsEntryPointBeforeMatcher()` hook (default `true`), overridden `false` only where `buildMatcher()`
  is verified to read nothing `buildEntryMap()` sets -- see design.md's section of the same name.
- The canary also revealed that `LiteralString`'s own opt-out wasn't sufficient by itself: every
  union/loop branch that's a bare literal or character class is parsed as a one-element `Sequence`
  wrapping it, not the leaf directly, and `Sequence.buildMatcher()` (already verified independent of
  `buildEntryMap()`'s output -- it does its own tail-to-front `next` wiring via each part's own
  `compile()` call) needed the same opt-out for the fix to reach real patterns. Confirmed via the
  same canary technique before trusting it.
- Verified correctness (not just "suite stays green," which was true even with the ordering bug
  still present, since the pull path is a superset of correct behavior): re-ran `(a?)+`, `(?:a?)+`,
  `[ab]?a`, `a?a`, `a|ab` and confirmed each still throws `PatternSyntaxException` specifically (not
  `StackOverflowError`) via a standalone harness outside the test suite, alongside the full 1487+9
  test suite.
- Measured (not assumed) the actual win via JMH: `CorpusBenchmark.llkCompile` went from 1.872 to
  **1.534 ms/op** (-18%), and its `gc.alloc.rate.norm` secondary metric from 4,958,552 to
  **3,566,024 B/op** (-28%) -- confirming the allocation reduction is real, not just structurally
  plausible. `regexCompile`/`regexMatch`/`llkMatch` moved by less than run-to-run noise, as expected
  since nothing here touches match-time code.
- Left for later (see remaining_work.md's "`CodePointMapBuilder`/`addCodePointsTo` follow-ups"
  section): extending `needsEntryPointBeforeMatcher()` to the quantified-loop case (plausible, not
  yet measured), and a proposed `BackReference` alias to its referenced group's own entry point
  (needs verifying against a nullable referenced group's cycle behavior first, not yet done).

### Investigating `DOT`'s `materializedComplement` cost, deferred (2026-09-08)

- The project owner noticed `llkCompile_sampling`'s ~4.5% time in `PatternParser`'s `new
  ArrayCodePointMap<>(RegexCharacterClass.DOT.unicode)` (the `.` handling) and suspected
  `materializedComplement`'s doc comment (claiming `set.complement(Boolean.TRUE)`'s `entrySet()`
  would incorrectly yield `set`'s holes, not its complement's members) pointed at a real bug worth
  fixing so `DOT` could just be `\n`'s complement directly.
- Checked the claim directly against the current `ArrayCodePointMap`: `{'\n'}.complement(true)
  .entrySet()` correctly returns 2 entries (`[0,10)->true`, `[11,1114112)->true`), not `\n`'s hole
  -- `materializeWithGaps()` already handles this correctly (skips null-valued punched-hole entries,
  fills every gap with the else-value). So the doc's stated justification doesn't reproduce; either
  it was accurate against an earlier version of this class and went stale, or it was describing a
  more general risk for a hypothetical consumer this doesn't happen to hit. Not chased further.
- Root cause of the actual measured cost is different from what the doc implies: `DOT.unicode` is
  built once at class-init via `materializedComplement`, which walks the full code point domain and
  produces ~541 real, physical entries (`ArrayCodePointMap`'s packed-key format caps each entry at
  2048 code points, so "everything except `\n`" can't be fewer chunks than that once actually
  appended). `PatternParser` then copies all ~541 of them, via `new ArrayCodePointMap<>(...)`, for
  *every* `.` in the pattern being compiled -- that per-`.` copy, not the one-time class-init walk,
  is what the profiler is actually seeing.
- Switching `DOT.unicode` to a real `.complement()` (the private complement constructor's O(1)
  array-copy fast path, since the source is just `{'\n'}`) would fix the one-time class-init cost,
  but NOT the per-`.` copy: `new ArrayCodePointMap<>(complementMap)` still routes through
  `putAll`/`entrySet()`, and an else-valued map's `entrySet()` (`materializeWithGaps()`) hands back
  the same ~2 giant logical ranges, which `appendSorted` then re-splits into ~541 physical chunks in
  the destination regardless. Fixing the real hot spot needs the copy itself addressed -- and
  `PatternParser` copies (rather than aliases) specifically because `ComplexCharacter.ranges` is
  mutable and further `&&`/negation parsing may write into it, so this isn't a one-line fix.
- Explicitly deferred at the project owner's direction ("we shouldn't tackle that in this session")
  -- see remaining_work.md's new item for where to pick this back up.

### Following through: `DOT`/`materializedComplement` fix and `ComplexCharacter.ranges` immutability (2026-09-08)

- Picked back up in a later session the same day. Implemented per the trace above:
  `NamedCharClass.materializedComplement` (and every `RegexCharacterClass`/`NamedCharClass` constant
  built from it -- `DOT`, `D`, `H`, `S`, `V`, `W`, `Assigned`, `Graph`) now just calls
  `.complement(Boolean.TRUE)` on the plain (non-complemented) source set, confirming the
  investigation's finding that this alone is O(source size), not O(domain) -- no measured class-init
  regression.
- The actual per-`.` cost needed the copy-avoidance the investigation called out as the real fix:
  `PatternParser`'s `.` handling no longer copies `RegexCharacterClass.DOT.unicode` at all -- it
  rebuilds `\n`'s complement fresh inline (`new ArrayCodePointMap<>(); put('\n',...); .complement()`),
  which is the same O(1) array-copy fast path, applied directly instead of routed through a shared
  static + defensive copy.
- Made `ComplexCharacter.ranges` genuinely immutable (`final CodePointMap<Boolean>`, not
  `MutableCodePointMap`) as the investigation's named prerequisite: `PatternParser#parseComplexCharacter`
  now builds into a local `ranges` variable throughout the loop (including the `&&` operand-run reset,
  which rebinds the local rather than reassigning a field) and only constructs the `ComplexCharacter`
  once, at each return point, from the finished map.
- That let `parseComplexEscape` become a pure function (`CodePointMap<Boolean> parseComplexEscape()`,
  no `ComplexCharacter`/`ranges` parameter) returning the escape's set directly. A standalone escape
  atom (bare `\D`, `\p{...}` in running pattern text -- not inside `[...]`) now assigns that result
  straight into the new `ComplexCharacter`'s `ranges` field with zero copying, for any named class
  including the else-valued ones (`\D`/`\H`/`\S`/`\V`/`\W`, and `\P{...}` -- the latter's own
  materializing walk (`PatternParser`'s local `materializeComplement` twin) was replaced with
  `.complement()` for the same reason, and removed once unused).
- Not fully generalized: a named class used *inside* a bracket expression (`[\d\s]`) still merges via
  `putAll` into the bracket's own accumulating local, which still forces materialization for an
  else-valued source -- see remaining_work.md's new item. Judged an acceptable residual (brackets
  containing these builtins are less common than standalone use, and the investigation's own
  measured hot spot was specifically the standalone `.` case) rather than something to chase now.
- Verified via `CodePointMapDifferentialTest` (which fuzzes `complement()` against `TreeCodePointMap`
  and was already in the suite) that `TreeCodePointMap.entrySet()`'s else-valued path also correctly
  yields the complement's members, not holes, matching `ArrayCodePointMap`'s behavior confirmed in the
  investigation above -- so aliasing a `.complement()` result is safe regardless of which concrete
  type produced it.

### `floorIndex` hybrid binary+linear search (2026-09-08)

- Follow-up to the linear-scan-under-65-entries experiment noted above (kept then, flat result).
  Changed `floorIndex` to always binary-search first, narrowing `[lo, hi]` down to at most
  `LINEAR_SEARCH_THRESHOLD` entries, then linear-scan that final window -- rather than choosing one
  strategy or the other based on the whole map's size. Bigger maps (already possible via `&&`/union
  chains) now get binary search's log-time narrowing before falling back to the branch-cheap linear
  scan for the final stretch, instead of a full linear scan regardless of size once under threshold,
  or a full binary search down to a single element once over it.
- Initial version of this hybrid excluded `mid` from the window on the "qualifies" branch (`lo =
  mid + 1`), which is right for a binary search that tracks a separate `result` variable but wrong
  once that tracking is dropped in favor of "the final window still contains the answer" -- it lost
  a legitimate rightmost-so-far candidate whenever nothing later in the window also qualified.
  Caught immediately by `CodePointMapDifferentialTest.complement_agreesWithTreeCodePointMap`
  (disagreement at U+10800). Fixed by keeping `mid` in the window on that branch (`lo = mid`, with
  `mid` rounded up via `(lo + hi + 1) >>> 1` so this still makes progress) -- see `floorIndex`'s own
  comment for the invariant.

### DOT should alias `RegexCharacterClass.DOT.unicode` directly, not rebuild it (2026-09-08, same session)

- The `.` (non-`DOTALL`) handling initially rebuilt `\n`'s complement fresh at every `.` instead of
  reusing the shared `RegexCharacterClass.DOT.unicode` constant, reasoning (accurately, at the time)
  that `ComplexCharacter.ranges` was mutable and aliasing the shared static risked later `&&`/
  negation parsing corrupting it. The project owner caught that this reasoning had gone stale:
  `ComplexCharacter.ranges` had *just* been made immutable in this same session (see above), so
  nothing past construction can mutate it any more -- aliasing `DOT.unicode` directly is safe again,
  and strictly cheaper than the rebuild (zero allocation vs. a fresh one-entry map plus a
  complement() call).
- Fixed: the non-`DOTALL` branch now does `new ComplexCharacter(index, RegexCharacterClass.DOT.unicode)`
  directly. Re-ran both benchmarks to confirm: desktop `llkCompile` allocation dropped a further
  small amount (2,114,320 -> 2,111,408 B/op); on-device `llkCompile` (Pixel 3a) went
  **27.72ms -> 18.99ms/pass** (a further ~31% on top of this session's earlier fixes), `llkMatch`
  1.32ms -> 1.18ms; `regexCompile`/`regexMatch` unchanged (6.68->6.71, 4.05->3.86, within noise) as
  the expected sanity check.
- General lesson: a "defensive copy because X is mutable" comment needs re-checking whenever X's
  mutability changes -- it doesn't automatically get revisited just because the copy site wasn't
  touched by the change that made X immutable.

### Sidestepping `putAll`/`entrySet()` in `parseComplexCharacter`'s merges (2026-09-08, same session)

- The project owner asked to avoid `putAll` (past performance issues) inside `parseComplexCharacter`,
  then separately asked to eliminate `entrySet()` too as "a performance problem waiting to happen."
- `putAll` avoidance: replaced both `ranges.putAll(...)` call sites (merging an escape class, merging
  a nested `[...]`) with a new `mergeInto(target, source)` helper that calls `target.put()` once per
  range of `source`, instead of `putAll`'s unconditional whole-array rebuild of `target` (`O(target's
  current size)` regardless of how small `source` is -- costly here since `ranges` keeps growing
  across a whole bracket expression while each merge source is typically tiny). Also restructured
  the nested-`[...]` case to recurse into a new `parseComplexCharacterRanges` core method (extracted
  out of `parseComplexCharacter`) rather than building, and immediately discarding everything but the
  `ranges` field of, a whole extra `ComplexCharacter` object.
- `entrySet()` avoidance: added `CodePointMap#forEachRange` (default method, `void accept(int min,
  int max, V value)` -- no boxed `Range`/`Entry` per visited range) with an `ArrayCodePointMap`
  override that reads `keys`/`values` directly for the common `elseValue == null` case, no
  `Iterator`/`Entry`/`Range` allocated at all (unlike `entrySet()`'s lazy view, which still allocates
  an `Entry`+`Range` pair per `next()`). `mergeInto` and `intersect` now use it.
- Measured, not just assumed: re-ran the JMH corpus benchmark after each step. Net result on this
  corpus is a wash, not a win -- `llkCompile` allocation actually *rose* slightly through the
  `putAll`-avoidance step alone (2,111,408 -> 2,167,488 B/op) before `entrySet` avoidance clawed most
  of it back (-> 2,127,208 B/op, still above the pre-`mergeInto` baseline); time stayed flat within
  noise throughout (~1.06ms/op regardless). Root cause: `put()`'s own window-search-and-splice does
  its own small array allocations per call (see `ArrayCodePointMap#put`'s `replKeys`/`replValues`),
  so N individual `put()` calls aren't strictly cheaper than one `putAll` bulk rebuild -- it depends
  on how many ranges are being merged and how large the destination already is. Reported honestly
  rather than assumed a win: this corpus's bracket expressions are mostly small (few ranges merged
  per bracket), which is close to the crossover point where the two approaches cost about the same.
  Kept anyway, per the project owner's direction (avoiding `putAll`/`entrySet()` as a matter of
  policy here, not contingent on this corpus showing a measured win) and because `forEachRange` is a
  generically useful building block regardless of this one caller's own numbers.
- Verified via the full test suite (`CodePointMapDifferentialTest` included) after each step.

### `ArrayCodePointMap` cleanup: dead code, direct `intersection`, optimized `putAll` (2026-09-08, same session)

- The project owner flagged four things after reviewing the `putAll`/`entrySet` work above:
  `entriesOverlapping` (returning a throwaway `List<Entry<Range, V>>`) was only used by
  `intersection`/`intersectionRejectingConflicts`; the latter looked unused; a `putAll(other)`
  should have an `ArrayCodePointMap`-specialized fast overload; and the previous `mergeInto`
  (individual `put()` calls) was based on a wrong premise -- the owner clarified `putAll` was slow
  historically because each individual `put()` did an unoptimized shift, and *multiple* `put()`
  calls (i.e. exactly what `mergeInto` did) is "definitely worse"; the real intent was minimizing
  how many map instances/copies get made in the first place, not avoiding `putAll` as such.
- Confirmed `intersectionRejectingConflicts` was genuinely dead in production: `grep` found it
  called only from its own two unit tests and `CodePointMapDifferentialTest`'s fuzzing -- real
  ambiguity detection moved to `CodePointMapBuilder` in an earlier session (see that session's own
  notes above) and never used this method. Removed it from the `CodePointMap` interface, both
  implementations, and its two dedicated `CodePointMapTestBase` tests; kept
  `CodePointMapDifferentialTest`'s `intersection`-only fuzz coverage (the same trial that caught
  this session's earlier `floorIndex` bug).
- Rewrote `ArrayCodePointMap#intersection(min, max)` directly against the raw arrays instead of
  through `entriesOverlapping`, which is now unused and was deleted too. The `elseValue != null`
  branch is a genuine improvement over the old `entriesOverlapping`, not just a rewrite: it walks
  from `windowStart(min)` (skipping straight to the first potentially-overlapping entry, same as
  the `elseValue == null` case) instead of `materializeWithGaps()`'s always-scan-from-index-0.
- Replaced `mergeInto` (the per-`put()` helper from the prior entry) with a real optimized
  `ArrayCodePointMap#putAll(ArrayCodePointMap<V>)` overload, with `putAll(CodePointMap<V>)`
  delegating to it via `instanceof`. Has its own empty-target fast path (a straight array copy,
  same trick the copy constructor/`complement()` already use) for the common "build a fresh
  accumulator from one source" shape; otherwise shares the existing sorted-sweep merge algorithm
  (`sweepMerge`) with the generic path, just fed via `forEachRange` instead of `entrySet()` --
  the sweep's cross-range state (`i`/`pending`/`pendingMin`/`pendingMax`/`pendingValue`) had to move
  into a small `SweepState` holder object, since `forEachRange`'s callback can't reassign locals of
  the enclosing method the way the old `entrySet()`-based `for` loop's body could.
  `PatternParser`'s two `mergeInto` call sites went back to plain `ranges.putAll(...)`.
- Also added an `ArrayCodePointMap`-vs-`ArrayCodePointMap` fast path to `equals()`: direct
  `keys`/`values`/`elseValue` field comparison (both are always kept in canonical coalesced form --
  see the class doc), no `entrySet()` at all. (`Arrays.equals(int[], from, to, int[], from, to)`
  isn't available -- this project targets Java 8, that overload is Java 9+ -- so it's a manual loop
  instead.)
- Measured: this round is an unambiguous win, not the wash the `mergeInto` experiment was --
  `llkCompile` allocation **2,127,208 -> 1,973,880 B/op**, the best figure of the whole session
  (better than the original `entrySet()`-based `putAll` baseline this session started from). Time
  ~1.01ms/op, within this run's own noise band of the prior numbers. Confirms the project owner's
  diagnosis: the fix for `putAll` being slow was never "stop using `putAll`", it was "optimize
  `putAll` itself" (plus avoid `entrySet()`'s per-range allocation while at it).
- Full test suite green throughout, including `CodePointMapDifferentialTest`.

### `appendSorted` single-block fast path + `parseComplexCharacterRanges` → `CodePointMapBuilder` (2026-09-09)

- The project owner spotted two things in a fresh `llkCompile` CPU sample: `ArrayCodePointMap
  .appendSorted` itself showing up as a bottleneck (even though it's only called from real-consumer
  sites), and `PatternParser.parseComplexCharacterRanges`'s `ranges.put(codePoint, codePoint + 1,
  true)` -- a genuinely new hot spot, since bracket-expression character members were still going
  through `ArrayCodePointMap.put()`'s general splice-and-shift path one at a time, never converted
  to `CodePointMapBuilder`.
- `appendSorted` fix: added a fast path for the common case (a single range, or the leftover after
  merging with the previous entry, that fits in one packed entry -- i.e. doesn't need the
  2048-code-point chunking loop) that skips the chunk-count division and loop setup entirely. Pure
  win, no tradeoff -- kept as-is.
- `parseComplexCharacterRanges`/`parseMaybeRangePredicate` converted from a mutable
  `ArrayCodePointMap` (built via `put()`/`putAll()`) to a `CodePointMapBuilder<Boolean>` (via the
  new `add()`/`addAll()`), since bracket-expression members arrive in arbitrary order (e.g. `[cba]`
  adds 'c', 'b', 'a') -- exactly what `CodePointMapBuilder` is for. Added `CodePointMapBuilder
  #addAll(CodePointMap)` (via `forEachRange`, no `Entry`/`Range` allocated) to support this.
- Measured a genuine tradeoff, not a clean win: `llkCompile` time improved (0.923 -> 0.853 ms/op,
  -7.6%) but `gc.alloc.rate.norm` got WORSE (1,956,720 -> 2,070,568 B/op, +5.8%). Root cause:
  `CodePointMapBuilder` itself allocates 3 raw arrays, and `build()` allocates 3 more scratch
  arrays plus two boxed `Integer[]` sort-order arrays -- for the common case (a handful of members
  in a small bracket expression), that fixed per-call overhead outweighs the `put()`-search
  avoidance on the allocation axis, even though it nets out faster on wall-clock.
- Presented the tradeoff to the project owner explicitly before committing (time better, allocation
  worse) rather than only reporting the flattering half. Decision: keep both changes as committed
  -- time is the metric that matters here, and the allocation regression is small relative to the
  time win. Cheaper alternatives (a primitive-index sort instead of boxed `Integer[]`, or a
  small-N-optimized `build()` path) were identified as a possible follow-up but not pursued this
  round -- worth revisiting if this specific spot regresses further or comes up again.
- Verified via a fresh stack-profiler sample (not just trusting the JMH number): the old
  single-line `ranges.put()` hot spot is gone, replaced by two small, diffuse entries
  (`parseComplexCharacterRanges` ~1.3%, `CodePointMapBuilder.build`/`appendSorted` ~1.2%) -- the
  cost moved to `build()` time as expected, not eliminated, consistent with the allocation number
  above. Full suite green throughout (same 1487+9 tests as before this round).

### Trimming `CodePointMapBuilder.build()`'s allocation overhead (2026-09-09, same day)

- Follow-up to the tradeoff above, addressing the identified headroom rather than leaving it open.
- `sortInPlaceByMin()`: replaced the boxed `Integer[]` index sort with a plain insertion sort over
  the builder's own `mins`/`maxs`/`values` arrays in lockstep -- no boxing at all. Appropriate
  since `size` is small at every real call site (a bracket expression's members, a union's
  branches, a loop's candidates) and inputs tend to already be close to sorted.
- The merge/conflict-check pass now compacts forward over those same three arrays in place,
  instead of writing into three separate `outMin`/`outMax`/`outValue` scratch arrays -- comparing
  each candidate only against the immediately-preceding *accepted* entry, not every prior one.
  Correct once sorted: an accepted entry's own range can never again overlap anything before the
  latest accepted one (disjoint by construction when accepted). This also fixes the old code's
  O(n^2) worst case as a side effect, not just its extra allocations.
- Added `ArrayCodePointMap`'s package-private `(int[], int[], Object[], int)` constructor, reached
  only from `build()`: builds directly from the now-sorted, disjoint, coalesced-where-possible
  arrays in one correctly-sized pass (computes the total packed-chunk count up front), instead of
  the old code's second sort pass plus a loop of `appendSorted` calls into a growing map.
  `CodePointMapBuilderTest` gained a case for a merged range spanning more than one 2048-code-point
  chunk, to exercise this constructor's own chunking logic specifically.
- Net result: `llkCompile` 0.853 -> 0.794 ms/op, a further real win on top of the previous round's
  tradeoff (not measured for its allocation-axis effect specifically, but the scratch-array/boxing
  removal should help there too by construction). Full suite green.

- 2026-09-09: `QuantifiableConstruct.buildLoopMatcher`'s `bodyOnlyResult` (used only for its
  `.elseCandidate` -- `.ranges` is never read) now skips `mergeEntryPoints`'s whole
  `CodePointMapBuilder`-sort-coalesce-conflict-check pipeline for a single-element `body` (any
  `x+`/`x*`/`x{n,m}` on one character/class -- the overwhelmingly common case in this corpus),
  computing the answer directly as `body.get(0).claimsEntryElse() ? body.get(0) : null` instead.
  Proposed by the project owner after profiling showed `buildLoopMatcher`/
  `parseComplexCharacterRanges` as (mildly, diffusely) hot; measured real: `llkCompile` 0.689 ->
  0.646 ms/op (-6.2%), allocation 1,886,560 -> 1,797,856 B/op (-4.7%). `llkMatch`/`regexCompile`/
  `regexMatch` unchanged, as expected for a compile-time-only change. A same-idea optimization for
  the (2-candidate, body+`next`) `result` merge a few lines below was considered but not yet
  attempted -- see remaining_work.md.
- 2026-09-10: ran a short (3s, single-iteration, `llkCompile`-only) JFR allocation-sampling profile
  (temporary `profilers = ['gc', 'jfr']` + `includes = ['llkCompile']` in `llkpattern/build.gradle`,
  reverted after; extracted per-class/per-site weight from the resulting `.jfr` with `jfr print
  --json` piped through a throwaway script) to get concrete numbers instead of continuing to guess
  from the flat 4-frame stack sampling. Headline finding: `java.util.Arrays.copyOf` alone accounted
  for ~60% of sampled allocation weight (312 of 916 samples) -- called from `ArrayCodePointMap`'s
  and `CodePointMapBuilder`'s own array-growth (`ensureCapacity`/`add`), not from object allocation
  or lambda closures as earlier suspected. `int[]` was the single largest allocated class (~66% of
  weight) for the same reason. Caveat: JFR's allocation sampling assigns each sample a statistical
  extrapolated weight, not a literal byte count, so low-sample-count entries can be noisy (e.g.
  `EndMatcherConstruct` showed 334 MB from only 3 samples) -- the `Arrays.copyOf` finding is solid
  (312 samples), individual small entries in the breakdown are not.
- 2026-09-10: tried pre-sizing `parseComplexCharacterRanges`'s `CodePointMapBuilder` from a cheap
  text-scan estimate (find the bracket expression's matching `]`, use the raw character span as the
  capacity) to act on the `Arrays.copyOf` finding above. Measured a REGRESSION, not a win:
  `llkCompile` allocation 1,797,856 -> 1,841,264 B/op (+2.4%), time flat within noise. Reverted
  entirely -- both the `PatternParser` call site and the `CodePointMapBuilder(int initialCapacity)`
  constructor it used; no leftover infrastructure kept, since nothing else calls it. Root cause: raw
  source-text length
  doesn't correlate with range count in either direction that matters -- it mildly over-provisions
  the common simple case (`[a-z]` spans 5 characters but needs exactly 1 range, so now allocates
  capacity 5 instead of the old flat default 4) while doing essentially nothing for the actually
  expensive case (`\p{L}`-style escapes expand to potentially hundreds of ranges from a handful of
  source characters, so `Arrays.copyOf` growth still happens just as before). Net: more waste on the
  many small classes, no help on the few big ones. Confirms the project owner's own skepticism going
  in -- a text-length heuristic isn't a substitute for actually knowing (or accurately estimating)
  the range count.
- 2026-09-10: tried making `CodePointMapBuilder`'s backing arrays lazily-null (allocate on the
  first real `add`, not eagerly at construction) plus sizing `addAll` directly from `source`'s
  `rangeCountUpperBound()` (re-added, same as the reverted union-estimate attempt) when the builder
  hadn't allocated yet. Measured a reproducible ~4.5% REGRESSION on `llkCompile` time (0.642 ->
  ~0.671-0.674 ms/op, confirmed across 2 runs after ruling out a noisy-environment false read) for
  NO allocation change at all (1,801,456 B/op both times, essentially flat vs. the 1,788,504
  baseline). Root cause: the `addAll`-presizing path apparently almost never actually fires in
  practice -- `PatternParser.parseComplexCharacterRanges`'s two `addAll` call sites are interleaved
  with ordinary `add()` calls parsing the same bracket expression (a literal, then maybe an escape,
  then maybe another literal...), so by the time `addAll` runs the builder usually already has
  entries from an earlier `add()`, and the "hasn't allocated yet" precondition rarely holds. Paying
  a `mins == null` branch on every single `add()` call (now needed unconditionally, not just once at
  construction) is a real, permanent cost with no offsetting win to show for it. Reverted entirely
  (`CodePointMap#rangeCountUpperBound`, `ArrayCodePointMap`'s override, and `CodePointMapBuilder`'s
  lazy-null fields/constructors/`add`/`addAll`) -- no leftover infrastructure kept this time, since
  the lazy-null structural change (unlike the standalone `CodePointMapBuilder(int)` constructor kept
  after the earlier reverted attempt) isn't separable from the regression itself.
- 2026-09-10: `Matcher.attemptMatch` now skips `resetPerAttemptState()` (the `Arrays.fill` over
  `quantifiableCounts`/`captureGroups` -- ~7.2% of sampled CPU time per
  `Intel-i7-9750H_llkMatch_sampling.txt`) on the very first attempt after construction/`reset()`/
  `reset(String)`/`usePattern()`: those arrays are already known zero/null then (either freshly
  `new`-allocated, or explicitly zeroed by `resetMatchState()` itself), so there's nothing yet for a
  prior attempt to have dirtied. A new `perAttemptStateIsFresh` field tracks this -- set `true` by
  `resetMatchState()`, consulted (then cleared) by `attemptMatch()`. Proposed by the project owner;
  full test suite green (including the scraped-corpus differential tests that specifically exist to
  catch stale per-attempt-state bugs, e.g. the 2026-09-07 leaked-loop-counter bug this same state
  was originally introduced to fix -- this change doesn't touch when a bug like that would be
  caught, only when a genuinely-already-fresh reset gets skipped). `CorpusBenchmark.llkMatch`
  itself didn't move (0.039 -> 0.039 ms/op): `runLlkMatch` builds a fresh `Matcher` per row and
  does exactly one top-level call, so `MATCHES`/`LOOKING_AT` rows are 100% "first attempt" and
  should see the full per-op saving, but `FIND` rows only save one skip out of however many
  internal `attemptMatch` calls `find()`'s scan makes -- diluted below this benchmark's noise floor
  in the aggregate. Kept anyway: correct, tested, and should measurably help the common
  single-`matches()`/`lookingAt()`-call case even though this particular mixed-mode aggregate
  benchmark can't see it.
- 2026-09-10: tried the project owner's follow-up idea instead -- estimate `mergeEntryPoints`'s
  `CodePointMapBuilder` capacity by walking the actual candidates, not the source text. Added a
  `PatternConstruct estimateEntryCount()` structurally mirroring `addCodePointsTo` exactly (same
  overrides: leaves answer directly from their own ranges, aliases delegate, the default pulls
  `getEntryPointMap().rangeCountUpperBound()` -- a new `CodePointMap` method, O(1) on
  `ArrayCodePointMap` via its own backing-array size), summed over `mergeEntryPoints`'s candidates
  before building the real `CodePointMapBuilder`. Expected to fare much better than the text-span
  idea above, since it estimates from other maps' real sizes instead of guessing from source-string
  length. Measured a clear, reproducible REGRESSION instead, on time this time (not allocation):
  `llkCompile` 0.646 -> ~0.677 ms/op (+4.8%, consistent across 3 repeated runs, each landing on
  essentially the same value) for only a ~1% allocation improvement (1,797,856 ->
  ~1,780,000-1,793,000 B/op, noisier). Root cause: walking every candidate's entry structure TWICE
  (once to estimate, once to actually push via `addCodePointsTo`) costs more than the
  `Arrays.copyOf` growth it avoids -- unlike the `buildLoopMatcher`/`bodyOnlyResult` win earlier
  this session (which eliminated a whole redundant merge for the single-element case, not just
  resized one), this doesn't remove any work, it duplicates a walk to skip a resize that's already
  fairly cheap in practice. Reverted entirely (`CodePointMap#rangeCountUpperBound`,
  `ArrayCodePointMap`'s override, `CodePointMapBuilder`'s capacity constructor,
  `PatternConstruct#estimateEntryCount` and its 6 mirrored overrides, and `mergeEntryPoints`'s
  two-pass wiring) -- no leftover infrastructure kept. This makes the planned follow-up (a
  same-idea two-pass for `parseComplexCharacterRanges`) worth reconsidering before attempting: that
  candidate set is usually even smaller (a bracket expression's own members) than a union's, so the
  same double-walk cost is likely to dominate there too, for less to gain.
- 2026-09-09: swept `ArrayCodePointMap.LINEAR_SEARCH_THRESHOLD` (1, 4, 8, 16, 32, the checked-in 65,
  128, 256) against `CorpusBenchmark.llkMatch` alone (temporary `includes = ['llkMatch']` +
  shortened warmup/iterations in `llkpattern/build.gradle`, reverted after) to see whether a
  different value -- specifically a power of 2 -- helps. A quick low-iteration pass suggested
  smaller values might be faster, but a longer, tighter-error-bar re-run of the top candidates
  showed 16/32/65 are statistically indistinguishable (0.037-0.038 ms/op, overlapping error bars);
  128 and 256 are measurably worse (0.041, 0.043 ms/op) -- a real effect, not noise, presumably from
  linear-scanning a needlessly large window on this corpus's few bigger maps (Unicode script/
  property ranges). Left `LINEAR_SEARCH_THRESHOLD` at 65: no measured win from changing it, and no
  reason to prefer an untested value over the one the 2026-09-08 sweep already picked.
- 2026-09-09: re-ran the desktop JMH corpus benchmark after the `CodePointMap#first`/`entrySet()`
  cleanup round below (`['gc']` only, no stack sampling this time -- the shape of the compiled
  matcher graph didn't change, only cold-path `equals`/`hashCode`/`toString`/dead-code removal).
  No regression: llkCompile 0.686 -> 0.689 ms/op, llkMatch 0.044 -> 0.039 ms/op -- both within this
  machine's normal run-to-run noise band established by the earlier re-runs this same day.
- 2026-09-09: cleaned up `CodePointMap`'s `entrySet()`/`forEach` surface once `forEachRange`/`first`
  had taken over every real hot-path caller (see the two entries above). Removed the now-dead
  `forEach(BiConsumer<Range, V>)` (its last two real callers -- `MutableCodePointMap.removeAll` and
  `TreeCodePointMap.putAll` -- converted to `forEachRange`) and the equally-dead `asMapOfRanges()`/
  `iterator()`/`stream()` default methods (confirmed zero callers anywhere in the codebase before
  deleting). Flipped `entrySet()` from an abstract method every implementation had to define to a
  `CodePointMap`-interface default built from `forEachRange` (documented that every implementation
  must still override at least one of the two, since their defaults reference each other and would
  recurse forever otherwise). That let `ArrayCodePointMap` drop its own `entrySet()` (the lazy
  `AbstractSet`-over-array view, now unused -- its "lazy view lets an early-return caller skip
  work" justification cited a `PatternConstruct.findFirstOverlap` method that no longer exists) and
  `materializeWithGaps()` (exact duplicate of `forEachRange`'s own `elseValue != null` branch)
  entirely, and rewrite `toString()`/`equals()`/`hashCode()` to build off `forEachRange` directly
  instead of calling `entrySet()` -- `hashCode()` in particular no longer materializes a `Range`/
  `Entry` per range at all, just sums `Range(min,max).hashCode() ^ value.hashCode()` (exactly what
  `Set<Entry>.hashCode()`'s own order-independent sum-of-entries definition already computes).
- 2026-09-09: consolidated the loop opcode set again (previous round: 2026-09-07, see above), this
  time proposed by the project owner directly (reading `DispatchMatcherConstruct`'s loop-flavored
  constructor cold and pushing back on it) rather than a correction of a prior session's drift.
  `EndLoopMatcherConstruct` is gone -- folded into
  `LoopMatcherConstruct`, which is now compiled as a loop body's own continuation (reached only
  after a body pass finishes, never as the loop's entry point) rather than self-registering as the
  loop's combined entry/re-entry node. `DispatchMatcherConstruct`'s loop-flavored constructor (the
  one that took a `QuantifiableConstruct owner, List<PatternConstruct> body, PatternConstruct next,
  int captureConstructIndex`) is gone too -- that logic moved into
  `QuantifiableConstruct.buildLoopMatcher` directly, which now builds the loop's real entry point as
  a plain, ordinary self-registering `DispatchMatcherConstruct` (the exact same constructor a plain
  union uses), separately from `LoopMatcherConstruct`. The two nodes need to be genuinely distinct
  now (entry needs no bound check; the re-check does), which reintroduced the same
  self-registration-first cycle-breaking requirement `MatcherConstruct`'s class doc describes -- just
  landing on a new throwaway `LoopBackMarker` PatternConstruct instead of `owner` itself, since
  `owner.matcher` needs to end up being the (separately-built, later) entry node instead.
  `QuantifiableConstruct` gained `rawEntryMap`/`rawEntryElse` fields (mirroring
  `QuantifiedUnion.rawEntryMap`) so the entry node can be built from `buildLoopEntryMap`'s
  already-computed merge instead of recomputing one -- which resolves (by making moot, not by
  implementing) the `needsEntryPointBeforeMatcher()` remaining_work.md item this touched: the loop
  case now genuinely needs the pull, so there's nothing left to extend there.
  Two real bugs found and fixed while implementing (both existing tests, no new ones needed):
  (1) the fresh `LoopBackMarker` had no working `buildEntryMap()` at first (just threw), which broke
  the moment a nullable construct nested in the loop body (e.g. the `(b)?` in `(a(b)?)+`) needed to
  look past itself at "what comes next" during its own entry-point computation -- fixed by having the
  marker delegate to `owner`'s own (by-then-already-cached) entry point, exactly mirroring what
  `body.compile(owner)` used to make available for free before the entry/re-entry split.
  (2) the new entry `DispatchMatcherConstruct`, unlike the old unified node, initially used a plain
  `rawEntryElse`-driven else-value with no fallback -- which broke a `min == 0` construct (e.g. `b?`)
  at end-of-input, since end-of-input is never actually present as an explicit `-1` entry in anyone's
  entry map and nothing else naturally claims it as a catch-all; needed the same "body claims no
  catchall of its own -> always default to trying exit" override the old unified node's `elseValue`
  logic already had (applied there via `bodyOnlyResult`), just re-derived for the entry-only case
  and gated on `min == 0` (only then is `next` even a legitimate entry candidate at all). Full test
  suite green after both fixes; see design.md's "Quantifier/loop compilation" and "Opcode set"
  sections for the resulting shape.
- 2026-09-09: replaced every hot-path `for (Entry<...> e : someCodePointMap.entrySet())` loop (in
  `PatternConstruct`/`MatcherConstruct`/`TreeCodePointMap`) with `forEachRange((min, max, value) ->
  ...)` -- avoids a `Range`/`Entry`/`Iterator` allocation per visited range (see
  `CodePointMap#forEachRange`'s own doc). Initially left `WordBoundaryConstruct`'s `isSubsetOf`/
  `isDisjointFrom` on `entrySet()`, since both short-circuit (`return false` on the first violation)
  and `forEachRange`'s `RangeConsumer` has no way to signal "stop early" -- see the next entry for
  the follow-up that added exactly that. One capturing-loop lambda (`buildLoopMatcher`'s
  continue-target construction) needed extracting into its own `private static` helper
  (`buildContinueTarget`) rather than being inlined, since its `continueTarget` local wasn't
  effectively final once wrapped in a lambda.
- 2026-09-09: added `CodePointMap#first(RangePredicate<V>)` -- `forEachRange`'s short-circuiting
  counterpart, same zero-allocation range visitation but stopping at (and returning `true` from)
  the first range the predicate accepts. `ArrayCodePointMap` overrides it the same way it overrides
  `forEachRange` (direct array reads, same `elseValue`-gap-fill handling); the default falls back
  to `entrySet()`. Used it to convert `isSubsetOf`/`isDisjointFrom` (see the entry above) off
  `entrySet()` too, closing out that conversion completely. Added `CodePointMapTestBase` coverage
  (shared by both `ArrayCodePointMap` and `TreeCodePointMap`): match found, no match, actually stops
  after the first match (counts visits), and sees `complement()`'s gap-filled else-value ranges too.
- 2026-09-09: default CPU-sampling depth for this project changed from 8 frames to 4 (see
  CLAUDE.md) -- an 8-frame capture taken this same session came out too flat/diffuse (no leaf much
  above 1%) to point at anything actionable; re-capturing the same code state at 4 frames surfaced a
  clear top leaf instead. Both `Intel-i7-9750H_*_sampling.txt` files now note the depth explicitly.
- 2026-09-09: re-ran the desktop JMH corpus benchmark (no code change since the previous round --
  requested as a standalone re-run with 4-frame stack traces, not tied to a specific commit) and
  captured fresh CPU sampling at `stack:lines=4;detailLine=true` (narrower than the usual 8-frame
  capture at the time). Numbers moved a bit from the last-committed baseline (llkCompile 0.794 ->
  0.741 ms/op, llkMatch 0.056 -> 0.051 ms/op, regexCompile 0.115 -> 0.123, regexMatch 0.057 ->
  0.059) -- normal run-to-run noise on this machine, not attributed to any change. The 4-frame
  sampling shows the same hot spots as the last 8-frame capture (`ComplexQuantifiedCharacter`'s
  quantified-loop path for llkCompile; `ArrayCodePointMap.floorIndex` for llkMatch), just with
  shorter call chains -- this is what prompted making 4 frames the project default shortly after
  (see the entry above), once the *next* round's 8-frame capture came out flat by comparison.

## Misc

- `oldllkpattern/` is the previous implementation attempt, kept around for reference — don't delete without checking with the user first.

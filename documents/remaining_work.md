# Remaining Work

Open items only: undone work, known bugs, open questions and optional experiments. History, measurements and
finished work live in [notes.md](notes.md); current design in [design.md](design.md). Run
`./gradlew :llkpattern:test` (JDK 27 -- see notes.md) to check the current state of the suite.

## Scraped-corpus differential test harness

- [ ] **More scrape sources**, each as its own `scrape_<source>.py` + golden file + `ScrapedCorpusTestBase`
      subclass. Oracle GraalVM's regex engine tests are a candidate -- not yet located/confirmed.
- [ ] **Investigate java-reggie's `FuzzTest`** (`https://github.com/DataDog/java-reggie` --
      look under its integration-tests module) to see how it picks fuzzed inputs and decides
      pass/fail. This project considered a fuzz test for `Ll1Pattern` before and shelved it because it wasn't
      clear which inputs to generate for an arbitrary pattern, or what the "correct" outcome even is without an
      oracle. java-reggie apparently found an answer worth copying -- read it before building anything, don't
      just copy the "fuzz" label.
- [ ] **Let `CorpusGenerator` refresh a golden file in place.** Today it only writes a whole file from an
      intermediate TSV, which discards the hand-triaged `status` tags, so refreshing a few rows means writing a
      throwaway class in package `com.tbohne.llkpattern.corpus` (needed for `CorpusGenerator.generateRow`) that
      reads the file with `GoldenTsv.read`, regenerates the wanted rows, and writes it back with `GoldenTsv.write`.
      Add a mode that reads an existing golden file, takes a selector for which rows to regenerate (e.g. a status
      prefix, a pattern regex, or row numbers), re-runs llk for them, and rewrites only those rows, leaving every
      other row untouched. A flag chooses whether to also re-run `java.util.regex` for the selected rows (when its
      recorded columns are suspect) or keep the recorded regex columns and refresh only the llk ones. `status` and
      `unicodeSensitive` are kept for rows not regenerated, and `status` is recomputed only for regenerated rows.

## Benchmarks

- [ ] `jmhAllocSampling`'s fixed 3000-iteration count (`llkpattern/build.gradle`) was sized for
      `llkCompile` (~3000 `jdk.ObjectAllocationSample` events); the same count only yielded 135 events for
      `llkMatch` (it allocates far less per pass) -- still enough to show a clear dominant leaf
      (`Ll1Pattern.matcher` at 97.9%), but a `llkMatch`-specific higher iteration count would give finer
      resolution if that ever matters.
- [ ] If a device's `java.util.regex` disagrees with the golden files' recorded `regexMatchResult`
      (scraped on desktop), decide whether that's rare enough to ignore (the on-device benchmark only times
      *speed*, not correctness) or common enough to need Android-specific golden columns or forked golden files --
      not yet checked against a real device.
- [ ] Re-run the timing and sampling benchmarks on the other phones once convenient.
- [ ] **Pixel 3a CPU-sampling leaders** (`Google_Pixel_3a_sargo_CompileLlk_sampling.txt`, captured 2026-09-24 --
      refresh before trusting exact percentages): none measured yet, just flagged from reading the profile.
  - [ ] `PatternParser`'s constructor does a full-pattern pre-scan (`Character.codePointAt` <-
        `PatternParser.codePointAt` <- `PatternParser.<init>`, ~7.8% combined) -- read what this scan computes and
        whether it can be folded into the same pass as parsing itself, or skipped when the pattern doesn't need it.
  - [ ] `PatternParser.advanceCodePoint` uses `String.offsetByCodePoints` (~1.4%) -- likely replaceable with
        `Character.charCount(codePointAt(...))`.
  - [ ] `PatternParser.removeQuoting`'s repeated `String.indexOf` calls (~2.7% combined) -- worth a single-pass
        rewrite if `removeQuoting` is called often enough to matter (check corpus frequency of `\Q...\E` first).
  - [ ] `NamedCharClass$RegexCharacterClass.valueOf` goes through `Enum.valueOf` (~1.3%) -- convert to a generated
        string switch, like `NamedCharClass#scriptByName`/`#blockByName`.
  - [ ] `PatternConstruct$Sequence.buildMatcher` calls `patterns.get(i)` repeatedly (~3.0% `ArrayList.get` + ~1.7%
        `Objects.checkIndex` on ART) -- hoist the element into a local once per iteration.
  - [ ] `PatternParser.skipComments` is its own leaf at ~1.9% -- confirm it early-returns when `COMMENTS` isn't set.
  - [ ] `PatternParser.tryParseSingleCharEscape` calls `String.indexOf` (~1.0%) -- a plain `switch` may be cheaper.

Before touching any desktop allocation-sampling leader, read the `CodePointSetBuilder` entries in notes.md
(2026-09-18 and 2026-09-25): small-N accumulation sites have repeatedly regressed when converted to a
`CodePointSetBuilder`, but plain `ArrayCodePointSet` pre-sizing (its `(int initialCapacity)` constructor, with a
correctly-computed hint) has measured as a real win at least once -- don't conflate the two.

## Shrink `UnicodePredicates` (idea from the project owner, 2026-09-19)

Not urgent: `UnicodePredicates` is 561 `CodePointSet` fields holding 13,640 ranges; a 316KB class file; ~14ms
first-touch (mostly one-time class-loading overhead); ~109KB heap afterward. Only worth doing to keep the jar/dex
small and startup "vaguely reasonable".

- [ ] **Step 1: pack all the ranges into one binary blob plus an index.** The generator concatenates
      every set's `int[]` internals (`ArrayCodePointSet`'s own packed `(min<<11)|count` format, ~55KB
      total before compression) into one big buffer, and writes a second buffer mapping each predicate
      to its slice (offset/length). Both live as jar resources (or, to avoid needing resource loading
      at all -- relevant on Android -- as `String` constants in a generated class, split into <64KB
      pieces since a class-file string constant is capped at 65,535 modified-UTF-8 bytes). Each
      predicate becomes a thin set built on demand from its slice. Should collapse the class file,
      verification, and static-init cost, since there'd be no per-set bytecode at all.
      Design questions to settle first: (a) lookup speed matters more than init speed, and it is UNMEASURED
      whether a slice view would be slower than `ArrayCodePointSet`'s plain `int[]` indexing (ART inlines less
      than HotSpot's C2, so the Pixel 3a is where a gap is likelier). Options, cheapest first: (i) one shared
      `int[]` blob with each thin set holding `(offset, length)` and indexing `blob[offset + i]` -- needs a small
      new `CodePointSet` implementation (or `ArrayCodePointSet`'s search working over array+offset) and keeps the
      whole ~55KB blob alive; (ii) an `IntBuffer` slice per set; (iii) copy the slice into a real
      `ArrayCodePointSet` on first use and cache it (zero match-time cost, small per-used-set init cost). Settle
      it with a throwaway JMH microbenchmark of `contains` on a large set such as `isDefined`, on the desktop AND
      the Pixel 3a, not the full corpus cycle; (b) resource loading via `getResourceAsStream` needs checking on
      the Pixel 3a / APK packaging, and its failure mode should be a loud, detailed exception.
- [ ] **Step 2 (after step 1): replace the 561 members with an enum** (or an ordinal-indexed table) and
      one method that materializes the set for a given value on the fly. Fits the existing name lookups
      (`NamedCharClass#scriptByName`/`#blockByName`, currently generated string switches) and lets
      nothing be built until asked for.
- [ ] Both steps change the generator (`UnicodeAnalyzer`) output format, so re-run the regeneration
      (see notes.md's 2026-09-19 entry) and the full suite afterward, and re-measure class size, init
      time and heap.

## Toolchain and testing

- [ ] **Paired benchmark follow-ups** (runner built: `:llkpattern:jmhPaired`, `:llkpattern:pairedCompare`, Android
      `testPaired`; see CLAUDE.md and notes.md 2026-10-01/02):
  - **Android multi-run harness:** run-to-run noise (~2-4%) exceeds the within-run CI, so a 2% change is not detectable from one
    run. Build the fork analogue: N separate processes (e.g. `adb shell am instrument` repeated without a Gradle rebuild, or N
    Gradle invocations), each short (100 warmup rounds is the floor, ~3.5 min), merged as blocks by `PairedStats`; then redo
    the injected-slowdown check (`pairedInjectPercent=2`) with that. The harness side (`pairedInjectPercent`) already exists.
  - **Pixel device-state episodes:** one 1200-round standalone run had a slow phase (rounds ~180-400, ratio +15%) that never
    recurred in later runs with the device monitor on; if it shows up again, the monitor log will show whether it is thermal.
  - **README tables/CLAUDE.md A/B procedure** still quote pre-paired numbers; rewrite around the two paired ratios.
  - **CPU sampling:** replace JMH's safepoint-biased `stack` profiler with JFR `jdk.ExecutionSample` at a short
    period (reuse `AllocationSamplingRunner`'s JFR-to-reversed-tree code) and add a sampling-diff tool with per-leaf
    binomial SE sqrt(p(1-p)/n), so "leaf moved 3%->5%" can be judged against counting noise.
  - **Version-interleaved A/B:** load a baseline jar and the working tree in one JVM via separate classloaders.
  - **Environment:** pause Dropbox; consider a High power plan; optionally raise child JVM priority/affinity.
- [ ] Decide on a CI setup (or at least a documented local command; the daemon must be JDK 17-25, tests want JDK 27 -- see notes.md) to run the suite "frequently" per the owner's stated preference.

## `ArrayCodePointSet` / `CodePointSetBuilder` API cleanup (project owner, 2026-10-01)

The refactor that split "sorted-insert" (`ArrayCodePointSet`) from "unsorted-append" (`CodePointSetBuilder`)
left the naming and layering muddled. Do as its own session (touches ~30 test call sites; re-run the full
benchmark checklist, since `addAll`'s hot paths are sensitive -- see notes.md, 2026-09-30).

- [ ] Keep the `ArrayCodePointSet(CodePointSet)` copy constructor (owner's decision). Its only non-test callers
      are `union`/`difference`, which could instead use the existing `sweepUnion`/`sweepDifference` for two
      non-inverted sets (one pass, one allocation), keeping the generic path only for inverted/lazy operands.
- [ ] Remaining `MutableCodePointSet` sites (see notes.md, "MutableCodePointSet -> CodePointSetBuilder migration"): `union(a,b)` and the three `insertAll` loops in `PatternConstruct` (`skipZeroWidthEntrySet`/`firstCharSet`/`resolveSingleCodePointBody`) are untried; `build()` now merges two sorted runs linearly, so they are viable. Measure each batch (A/B plus the full benchmark cycle). `mergeRun` and `mergeEntryPoints`/`unionLastCharSet` regressed as builder users; `gate` needs `removeAll`.

## Decompose `PatternParser#parseUnion` without regressing speed or allocation

Phases 1-2 are done (`PatternLexer` <- `CharClassParser` <- `PatternParser`, in package `parser`). Measured
2026-10-03: largest method bytecode is `parseUnion` 2001 B, `parseGroup` 1062 B, everything else under 810 B, so
splitting can't change JIT compile/inline decisions; the goal is navigability only.

- [ ] **Phase 3, decompose `parseUnion` (~400 lines; do regardless of the language question)**: split into private
      methods on the same object (`endAlternative` for `|`, `finishUnion` for `)`/EOF, the two escape
      paths, the plain-character path) and replace the three duplicated literal-flush blocks with one `flushLiteral`.
      Idea to verify: every branch that recurses into `parseUnion` (`(`, `[`, ...) flushes the pending literal first,
      so the literal-run state (`rawTextStartIndex`, `rawTextPureEnd`, `rawTextIsPure`, `rawText`) is empty at
      recursion time and can live in parser fields (no per-level state object, one shared `StringBuilder`); only
      `accumulator`/`altStartIndex` stay per-level locals. Check the quantifier path, which flushes only when a literal
      is actually pending. Measure B/op. Kotlin (`value class`/`inline`) was considered and rejected for this: a value
      class holds one field, `inline` can't carry state, and adopting Kotlin is a whole-project build decision.
- Verification per move commit: `:llkpattern:jmh` `gc.alloc.rate.norm` must be byte-identical (pure move), then one
  same-session interleaved `jmhPaired` A/B at the end (expected effect under 2%); Pixel run is a sanity check only.

## Open questions

## Optional experiments (nothing here is required work)

- [ ] **Consider a parse-time check rejecting a quantified construct whose entire body is nullable** (e.g. `(a?)+`).
      Today only the entry-point-computation guard (design.md's "Entry-point computation vs. matcher compilation")
      catches it, as a compile-time `PatternSyntaxException`; `PatternParser` has no `nullable(construct)` recursion
      (a third sibling to `firstCharSet()`/`lastCharSet()`). A parse-time version would only improve the
      diagnostic (an earlier, more specific message), not correctness (`NestedQuantifierCombinatorialTest`).
- [ ] **`CodePointMap#forEachRange` isn't used everywhere `entrySet()` still is.** It visits ranges
      as primitive `int`/`value` triples with no `Range`/`Entry`/`Iterator` allocated per range (for
      `ArrayCodePointMap`'s common `elseValue == null` case). `PatternParser`'s `intersect` helper and
      `ArrayCodePointMap#putAll`'s `sweepMerge` already use it. Still unconverted: `TreeCodePointMap`'s own methods
      (low priority -- differential-test oracle only), and every `PatternConstruct`/`MatcherConstruct` loop that
      walks an entry map while building the matcher/dispatch graph (`grep -n '\.entrySet()'
      llkpattern/src/main/java` finds them all). Most are on the `llkCompile` hot path, so likely worth a dedicated
      pass (its own session) rather than opportunistic conversion.
- [ ] **`ArrayCodePointMap`/`TreeCodePointMap` immutable+builder split**: neither has it today (both are
      mutable-only) -- worth doing for both together if immutability is ever wanted.
- [ ] **Followup experiment** for `ArrayCodePointMap`: shrink the range field to 10 bits and use the
      freed 11th bit as a mask-vs-range flag. When set, the 10 "range" bits are instead a bitmask of
      which of the 10 code points *after* `min` also map to this value (not required to be
      contiguous) -- lookup then has to branch on the flag and, on a mask hit, may need to check up
      to 11 candidate keys in a row, so it trades lookup speed for density. Good fit for
      alternating-but-not-contiguous data (e.g. `isLowerCase` over `0x100`-`0x137`). Also
      worth trying a `long[]` variant with 42 range/mask bits instead of `int[]`'s 11.
- [ ] **`PatternParser` codepoint-array indexing** (see the reverted attempt in notes.md, 2026-09-14): only worth
      revisiting for a corpus of much longer patterns, or a single-array encoding (char offset packed into unused
      high bits of each codepoint slot). Measure before keeping.
- [ ] Rename `singletonCodePointMap` (used by `firstCharSet`/`lastCharSet`) to `singletonCodePointSet` -- it is a
      `CodePointSet` now. It and the `QuantifiedUnion`-branch-union temporary sets inside those two methods are
      still small un-eliminated allocations.

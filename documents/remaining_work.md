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

- [ ] **Make a single benchmark run statistically self-sufficient** (so sessions stop repeating whole runs to
      estimate noise; see CLAUDE.md's A/B procedure). Own session -- touches the JMH config, the Android test and
      the output formats:
  - **Intel/JMH:** raise `forks` (between-fork variance -- JIT, layout, heap -- dominates, and iterations inside one
    fork don't shrink it) and trim warmup iterations per fork; keep total wall time near today's ~2m50s.
  - **Ratio helper:** a small script/Gradle task that reads the JMH JSON and prints llk/regex per table with an error
    interval propagated from the two `scoreError`s, replacing the two-run eyeballing of overlapping bands.
  - **Android:** record per-pass times (already measured, currently only averaged) and output stddev, a 99%
    confidence interval and p50/p90/p99 next to the mean in `Google_Pixel_3a_sargo_corpus_benchmark_results.json`;
    also emit the llk/regex ratio with its interval there. Update README/CLAUDE.md for the new fields.
  - **Interleave llk and regex (both platforms):** investigate alternating llk and regex iterations/forks (JMH: one
    fork running both benchmarks in alternation, or a combined benchmark method; Android: alternate llk/regex passes
    in the test loop) so time-correlated noise (background load, thermal throttling, sync clients) hits numerator and
    denominator equally and largely cancels in the ratio. Check it doesn't distort either side (shared JIT/ART state,
    cache effects) before adopting.
  - **Environment:** a pre-run checklist (pause Dropbox, close other sessions) belongs in CLAUDE.md; this session's Intel
    A/B was noisy with Dropbox syncing.
- [ ] Decide on a CI setup (or at least a documented local command; the daemon must be JDK 17-25, tests want JDK 27 -- see notes.md) to run the suite "frequently" per the owner's stated preference.

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

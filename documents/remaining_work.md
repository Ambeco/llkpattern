# Remaining Work

Run `./gradlew :llkpattern:test` (with `JAVA_HOME` pointed at a JDK 17/21 — see [notes.md](notes.md)) to check the current state of the suite; see notes.md for dated pass/fail history rather than this file.

## HIGHEST PRIORITY

- [ ] **Implement Unicode scripts** (`\p{IsScript}`/`\p{script=Script}`) — no `NamedCharClass` entry
      uses `Source.Script` at all, so every such reference throws (covered, as throwing, by
      `UnicodeClassTest`). The underlying data already exists: `unicodeanalyzer` already generated
      one range set per script in `UnicodePredicates.java` (`LATIN`, `GREEK`, `CYRILLIC`, `HAN`,
      `KHITAN_SMALL_SCRIPT`, ~160 total) -- confirmed 2026-09-07, this was previously assumed
      unimplemented from scratch. What's missing is purely the `NamedCharClass` wiring, but at
      ~160 entries, adding one enum constant per script (the pattern every other named class here
      uses) is a lot of boilerplate for one feature -- worth restructuring `NamedCharClass` away
      from one-Java-constant-per-name to a runtime `Map<String, NamedCharClass>`-style lookup
      while doing this, rather than 160 more enum constants. Large enough to be its own session.
- [ ] **Implement Unicode blocks** (`\p{InBlock}`/`\p{block=Block}`) -- unlike scripts, no
      generator support exists for this at all (confirmed 2026-09-07: no block-named constant like
      `BASIC_LATIN` anywhere in `UnicodePredicates.java`). Needs `unicodeanalyzer` work first, not
      just `NamedCharClass` wiring.

## Flattened matcher dispatch (merged to `main`)

`MatcherConstruct.entrySet`/`failedEntry` fold the old `ForkingMatcherConstruct` into every node
(checked *before* a node's own matching logic runs, falling through to `failedEntry` on a miss and
failing the match outright when `failedEntry` is null) instead of `SingleDispatchingMatcherConstruct`
always advancing to `next` and a separate `ForkingMatcherConstruct` doing the membership check
after. See design.md's "Quantifier/loop compilation"/"Opcode set" sections for the current design.

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

- [ ] Once implemented, add the same depth of test coverage for: quotation (`\Q...\E`),
      positive/negative lookahead (`(?=...)`/`(?!...)`), positive/negative lookbehind
      (`(?<=...)`/`(?<!...)`) -- note lookahead/lookbehind are currently rejected outright at
      parse time per design.md, and independent/atomic non-capturing groups (`(?>X)`).
- [ ] Of `java.util.regex.Pattern`'s remaining compile flags -- `CASE_INSENSITIVE`, `UNICODE_CASE`,
      `DOTALL`, and now `COMMENTS` (`(?x)`, implemented 2026-09-07 -- see `CommentsFlagTest`/
      `PatternParser.skipComments()`) are implemented (both globally and correctly scoped through
      an inline `(?i:...)`/`(?s:...)`/`(?x:...)`), and `MULTILINE`/`UNIX_LINES` are now implemented
      too, but *only* as far as `^`/`$`/`\Z` (`BoundaryMatcherConstruct`) consult them --
      `.`/`\s`/etc. under `UNIX_LINES` (which characters count as line terminators for those) is
      still unaffected by the flag, so `UNIX_LINES` is only partially honored. `LITERAL` (treat the
      whole pattern string as literal text, no metacharacters) and `CANON_EQ` (Unicode
      canonical-equivalence matching) are not started at all. Neither has any test coverage or even
      a stub `flags` branch, unlike the boundary/backreference stubs above -- they're simply
      unimplemented from scratch.

## Scraped-corpus differential test harness

Design: `./tools/scrape_<source>.py` fetches a source project's own regex test data and
emits an "intermediate" TSV of `(pattern, flags, input[, mode])` tuples. `CorpusGenerator`
(`llkpattern/src/test/java/.../corpus/CorpusGenerator.java`, run via
`./gradlew :llkpattern:generateCorpus -Pinput=... -Poutput=... -Pmode=... -Punescape=...`) reads
that, runs each tuple through both `java.util.regex` and `Ll1Pattern`, and writes a golden TSV
(`GoldenRow`/`GoldenTsv`) with an auto-tagged `status` column (`AGREES`/`UNIMPLEMENTED: ...`/
`UNEXPECTED: ...` -- see `CorpusGenerator`'s javadoc; a first-pass heuristic, not human-verified).
`ScrapedCorpusTestBase` is a JUnit4 `@Parameterized` base class; one concrete subclass per golden
file (`OpenJdkBmpCorpusTest`, `OpenJdkSupplementaryCorpusTest`) — regenerate via the
`generateCorpus` command above after any scraping/unescaping/engine change. See notes.md for a
dated AGREES-count snapshot rather than tracking that number here.

**Next steps**:

- [ ] Human triage pass over every non-`AGREES` row in both golden files -- retag
      `EXPECTED_DIVERGENCE` vs leave as a real bug to fix, per `CorpusGenerator`'s status scheme.
      Progress (2026-09-07): triaged all `UNEXPECTED` rows (both engines completed but disagreed --
      the `UNIMPLEMENTED` rows, where llk simply doesn't support a feature yet, weren't in scope
      for this pass). Found and fixed two real bugs this way (see the "Core implementation"
      section's history in notes.md): `Matcher#attemptMatch` never reset `quantifiableCounts`/
      `captureGroups` between separate match attempts (so a loop's iteration counter leaked across
      `find()`'s internal scan positions and repeated `matches()`/`lookingAt()`/`find()` calls),
      and capturing groups were numbered in closing-paren order instead of opening-paren order for
      any nested group. Then implemented `COMMENTS` (`(?x)`, see the flags item above), which
      resolved the corpus's `(?x)`-with-whitespace rows too. 33 `UNEXPECTED` rows dropped to 12,
      then a nested-loop entry-point bug fix (see notes.md) resolved the remaining nested-loop
      rows too. What's left falls into one category, not fixed (by design, not a bug):
      - Bounded/reluctant quantifier edge cases where the engine's no-backtracking design cannot
        produce the same match `java.util.regex` does even though llk's own greedy result is
        internally consistent (e.g. `a{2,3}` against `"aaaa"`: llk cannot tell "stop at 3" from
        "keep going" without a distinguishing next character, since nothing follows the loop, so
        it hard-fails at every position where a 4th `a` follows the first three and instead matches
        the *tail* 3 `a`s rather than `java.util.regex`'s backtracked leading 3; reluctant
        (`?`) quantifiers being a documented no-op compounds this for `{n,m}?` cases). These are a
        fundamental consequence of the engine's design (see README's own "tradeoff: not every
        pattern a traditional regex engine accepts can be expressed this way"), not bugs -- should
        be retagged `EXPECTED_DIVERGENCE` with that rationale, not left as "needs investigation."
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

## Scraped-corpus microbenchmark

Done (2026-09-07): `CorpusBenchmark` (`llkpattern/src/jmh/java/.../corpus/CorpusBenchmark.java`),
via the `me.champeau.jmh` Gradle plugin (see `llkpattern/build.gradle`'s `jmh {}` block). Loads
every golden row from `openjdk_bmp.tsv`/`openjdk_supplementary.tsv`, keeps only rows where both
engines compiled successfully (a stricter filter than `status == "AGREES"`, which also covers rows
where both engines agree by both throwing the same compile exception -- not a speed sample), and
times `java.util.regex` vs `Ll1Pattern` compile and match separately (`regexCompile`/`llkCompile`,
`regexMatch`/`llkMatch` -- matches are pre-compiled once in `@Setup` so match timing never includes
compile cost). Run via `./gradlew :llkpattern:jmh`; results print to console and are also written
as JSON to `benchmarks/Intel-i7-9750H_corpus_benchmark_results.json` (named after the
desktop it was run on, mirroring the Pixel 3a's own results file below -- rename if run on a
different machine) (only once the *entire* run completes -- an interrupted run leaves that file
empty), meant to be committed as a baseline and diffed against on later runs to catch regressions.
CPU-sampling (`SamplingRunner`, JMH's `stack` profiler, flat chain format) and allocation-sampling
(`AllocationSamplingRunner`, JFR's `jdk.ObjectAllocationSample` event, reversed call-tree format --
see `AndroidCorpusBenchmark`'s CPU sampler for the same tree format) are separate, opt-in Gradle
tasks (`jmhSampling`, auto-chained after `jmh`; `jmhAllocSampling`, standalone) writing
`benchmarks/<machine>_<name>_sampling.txt`/`_alloc_sampling.txt` respectively.
- [ ] `jmhAllocSampling`'s fixed 3000-iteration count (`llkpattern/build.gradle`) was sized for
      `llkCompile` (yielded ~3000 `jdk.ObjectAllocationSample` events, a good sample size); the same
      count only yielded 135 events for `llkMatch` (it allocates far less per pass) -- still enough
      to show a clear dominant leaf (`Ll1Pattern.matcher` at 97.9%), but a `regexMatch`/`llkMatch`-
      specific higher iteration count would give finer resolution if that ever matters.

## On-device (Android) corpus benchmark

Done (2026-09-08, verified on a real device -- a Pixel 3a, API 32): `AndroidCorpusBenchmark`
(`app/src/androidTest/java/.../corpus/AndroidCorpusBenchmark.java`) mirrors `CorpusBenchmark` above
but runs as an `androidx.test` instrumented test on a phone (JMH itself doesn't run on Android), so
it can be compared against the desktop numbers under a slower CPU, a much smaller heap, and
Android's own `java.util.regex`/ART. It reuses the same golden TSVs (copied into androidTest assets
at build time by `app/build.gradle`'s `copyGoldenAssetsForAndroidTest` task, so the checked-in
copies under `llkpattern/src/test/resources/golden/` stay the only source of truth) via a
standalone `AndroidGoldenRow`/`AndroidGoldenTsv` reader (kept separate from `GoldenRow`/`GoldenTsv`
since those live in `llkpattern`'s `test` source set and use `java.nio.file`, which needs API 26+ /
desugaring this app module doesn't otherwise pull in). `FRACTION_OF_TEST_ROWS` subsamples the
corpus for slower devices; results are written as JSON named after the actual device
(`Build.MANUFACTURER`/`MODEL`/`DEVICE`) to the app's external files dir, since the point is
comparing several phones with different hardware. GC counts during each measured benchmark are
recorded via `Debug.getGlobalGcInvocationCount()`. `sampleMatchLlk`/`sampleCompileLlk` (a
hand-rolled stack sampler, since `Debug.startMethodTracingSampling` has no way to cap stack depth)
run unconditionally alongside the four timing benchmarks, aggregating into a reversed call-tree
report (leaves ranked by frequency, then each leaf's callers recursively) at
`benchmarks/<device>_<name>_sampling.txt`.
- [x] ~~Run `./gradlew :app:connectedAndroidTest` against real hardware~~ -- done 2026-09-08 against
      a Pixel 3a (API 32): all 5 tests pass. Required two unrelated fixes to `app/build.gradle`,
      both pre-existing issues not caused by this test itself: `compileSdk` bumped 33 -> 36
      (`appcompat`/`material`'s transitive `androidx.activity:1.8.0` requires 34+; 36 chosen over
      34 since it was already installed locally, avoiding an SDK download) and a
      `configurations.all { exclude group: 'com.google.guava', module: 'listenablefuture' }` added
      (Guava's `-jre` flavor, pulled in transitively via the new `androidTestImplementation
      project(':llkpattern')` dependency, conflicts with the empty `listenablefuture` stub artifact
      several androidx libraries depend on -- Guava's own documented workaround, see
      https://github.com/google/guava/issues/2960).
      A first small-fraction run (0.25, 103 rows, default 3 warmup/5 measured iterations) showed
      the whole run finishing in ~4 seconds -- wildly underusing a 5-minute test budget -- so
      `FRACTION_OF_TEST_ROWS` was set to `1.0f` (full 406-row corpus) and `WARMUP_ITERATIONS`/
      `MEASURED_ITERATIONS` bumped to 50/1000, landing at ~124s wall-clock (`am instrument`'s own
      "Time:" figure), comfortably under 5 minutes with margin for slower devices. Current
      committed baseline (`benchmarks/Google_Pixel_3a_sargo_corpus_benchmark_results.json`,
      full corpus, 1000 measured iterations, re-run 2026-09-08 after this session's `appendSorted`/
      `entryMap`-aliasing compile-time fixes): `llkCompile` ~6.3x slower than `regexCompile`
      (41.47ms vs 6.56ms/pass -- down from an earlier 101.7ms/6.7ms baseline before those fixes,
      consistent with the desktop-side improvement); `llkMatch` is actually *faster* than
      `regexMatch` at this row count and iteration depth (1.26ms vs 3.77ms/pass) -- notably
      different from the small-fraction run's `llkMatch` being slower, and from the desktop JMH
      ratio (see `Intel-i7-9750H_corpus_benchmark_results.json`) where `llkMatch` is slower than `regexMatch` --
      not yet investigated further (different row mix at full fraction, ART vs HotSpot JIT
      behavior, and/or genuine device-specific dispatch performance are all plausible; worth
      another look if it matters for a real decision, but out of scope for just standing up this
      harness).
      **Gotcha found re-running this** (2026-09-08): `./gradlew :app:connectedAndroidTest` installs
      both APKs, runs the tests, and then uninstalls them afterward -- and uninstalling an app on
      this device wipes its `/sdcard/Android/data/<package>/files/` directory (standard Android
      behavior), taking the just-written results JSON with it before it can be pulled. Worked
      around by installing both APKs manually (`adb install -r
      app/build/outputs/apk/debug/app-debug.apk` and the matching `.../androidTest/debug/
      app-debug-androidTest.apk`) and running via `adb shell am instrument -w
      com.tbohne.llkpattern.test/androidx.test.runner.AndroidJUnitRunner` directly instead --
      exactly the invocation already documented below for the sampling test, which is presumably
      why that one never hit this. Pull promptly after that command returns, before doing anything
      else that might trigger a reinstall/uninstall cycle.
      Also captured CPU sampling profiles of `llkMatch`/`llkCompile` (`sampleMatchLlk`/
      `sampleCompileLlk`): a hand-rolled sampler (a background thread periodically snapshotting the
      benchmark thread via `Thread.getStackTrace()`) rather than `Debug.startMethodTracingSampling`
      (tried first, but its sampling API has no way to cap stack depth -- see notes.md), aggregated
      into a reversed call-tree report at
      `benchmarks/Google_Pixel_3a_sargo_{MatchLlk,CompileLlk}_sampling.txt`. These two run
      unconditionally alongside the four timing benchmarks -- no separate invocation needed.
      Worth re-running both (timing and sampling) on the other phones once convenient.
- [ ] If a device's `java.util.regex` disagrees with the golden files' recorded `regexMatchResult`
      (scraped on desktop), decide whether that's rare enough to ignore (the benchmark only times
      *speed*, not correctness, on-device) or common enough to need Android-specific golden columns
      or forked golden files -- not yet checked against a real device.
## Core implementation

- [ ] **Consider a parse-time check rejecting a quantified construct whose entire body is nullable**
      (e.g. `(a?)+`), instead of relying solely on the entry-point-computation guard added
      2026-09-08 (see design.md's "Entry-point computation vs. matcher compilation" section) to
      catch it as a compile-time `PatternSyntaxException`. Checked (2026-09-08): no such parse-time
      check exists today -- `PatternParser` has nothing recognizing a nullable quantifier body, so
      the guard is currently the *only* thing catching these patterns, not a backstop for
      parser-level rejection as originally hoped. A `NestedQuantifierCombinatorialTest` was added
      the same day to prove the guard itself is reachable and never escapes as anything other than
      `PatternSyntaxException` across ~600 nested-quantifier pattern shapes. A parse-time version
      would need a `nullable(construct)` AST recursion (a third sibling to `firstCharSet()`/
      `lastCharSet()`) and would only be a diagnostic-quality improvement (an earlier, more
      specific error message) -- not a correctness fix, since the guard already catches every case.
- [ ] Numbered backreferences only support a single digit (`\1`-`\9`) -- unlike `java.util.regex`,
      which greedily consumes further digits when enough groups exist to make them part of the
      group number (`\12` can mean group 12, not group 1 followed by literal "2"). A pattern
      needing a 10th+ backreference isn't supported yet; see `PatternParser.tryParseBackReference`.
- [ ] `MatcherConstruct.BoundaryMatcherConstruct.match(...)`: `InputBegin`/`InputEndExceptTerminator`/`InputEnd` (`\A`/`\Z`/`\z`) are implemented (position-and-surrounding-characters checks only, honoring `MULTILINE`/`UNIX_LINES` -- see design.md's "Boundary matching" section). `^`/`$` and `\b`/`\B` are separate, already-implemented pairs (`LineBoundaryConstruct`/`LineBoundaryMatcherConstruct`, `WordBoundaryConstruct`/`WordBoundaryMatcherConstruct`). `\G` isn't a position-based boundary at all and has no `MatcherConstruct` of its own -- see design.md's "Boundary matching" section.
- [ ] Remaining `Matcher`/`Ll1Pattern` API gaps: `replaceAll`/`replaceFirst`/`appendReplacement`/`appendTail`/`quoteReplacement`, `split`/`splitAsStream`, `toMatchResult`, `hitEnd`/`requireEnd`, `useAnchoringBounds`/`hasAnchoringBounds`, `useTransparentBounds`/`hasTransparentBounds` — all still `UnsupportedOperationException` stubs. None of these are needed for the scraped-corpus differential test harness above (that only needs `matches`/`find`/`group`/`start`/`end`), so lower priority than that.
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

## `PatternParser` codepoint-array indexing (proposed 2026-09-15, tried 2026-09-14, reverted -- measured regression)

- [ ] **Convert `PatternParser`'s internal `index` from a char index into `pattern` to a codepoint
      index into a decoded `int[]`**, to skip `Character.charCount`/surrogate-pair math throughout
      the per-character scan loop (proposed by the project owner, following the same reasoning as
      `Matcher.peeked`/`PatternParser.patternChars`'s own char\[\]-vs-String win -- see notes.md's
      2026-09-15 entries). Explicitly deferred to its own session rather than attempted
      opportunistically: this isn't a small tweak, it's a full re-indexing of the file --
      `index`/`startIndex`/`endIndex` are used well over 100 times, including every
      `PatternSyntaxException`'s char-accurate error position (must keep matching
      `java.util.regex`'s own char-offset contract), every `pattern.substring(...)` call (group/
      char-class names), and the three `CharBuffer.wrap(pattern, ...)` zero-copy literal-text
      sites added 2026-09-14.
- [ ] To land this **without** losing the zero-copy literal optimization or breaking char-accurate
      error positions: decode `pattern` into a codepoint `int[]` AND a parallel `int[] charOffsets`
      (`charOffsets[codepointIndex]` = the char index that codepoint starts at, built in one pass
      alongside the codepoint decode) at construction. `index` becomes a codepoint index used for
      the hot scan loop (`advance`/`advanceCodePoint`/`peek` all collapse to simple `index++`/array
      reads, no charCount distinction needed at all, since every codepoint occupies exactly one
      array slot regardless of BMP/supplementary); every place that currently captures a
      `startIndex`/`endIndex` for a `PatternConstruct`, a `pattern.substring(...)` call, or a
      `CharBuffer.wrap(pattern, ...)` call translates through `charOffsets[index]` first, so
      external-facing behavior (spans, substrings, exception positions, the zero-copy literal
      views) is unchanged.
- **Tried exactly as specced above (2026-09-14), reverted -- see notes.md's entry.** Correctness
  held (full test suite green, including char-accurate exception positions), but desktop
  `llkCompile` regressed ~5-8% (0.229 -> 0.241-0.244 ms/op, reproduced across two runs, tight error
  bars) with ~10% more allocation (667,200 -> ~735,000 B/op) -- the two per-compile `int[]`
  allocations (`codePoints` + `charOffsets`) cost more than the `charCount`/surrogate-math this
  corpus's short patterns ever needed skipped. Left as an open item in case a different corpus
  (much longer patterns, where the per-character savings would actually accumulate) or a
  single-array encoding (packing the char-offset into unused high bits of each codepoint slot,
  avoiding the second array) changes the tradeoff -- not attempted this session.
- [ ] Measure before keeping, same as everything else touched this session -- this project has
      twice already measured a plausible-sounding indexing/pre-sizing heuristic as a net
      regression (see notes.md's 2026-09-10 `CodePointMapBuilder` entries), and parsing happens
      once per compile on typically-short pattern strings, so the absolute win here may be small
      even if real.

## `firstCharSet`/`lastCharSet` follow-ups (2026-09-08)

- [ ] **`BackReference` aliasing its referenced group's own entry point directly**, instead of going through the separate `firstCharSet`/`lastCharSet` static-walk helpers -- proposed this session, NOT done: `firstCharSet(referencedGroup)` and `referencedGroup.getEntryPointMap()` diverge for a nullable referenced group (the latter folds in `next`'s entries via `buildLoopEntryMap`'s `min == 0` case, and can throw `EntryPointCycleException` on a pattern that compiles fine today), so this needs verifying against `(a?)\1` and `(a|b)?\1` before landing, not just assumed safe.
- [ ] **`lastCharSet`/`WordBoundaryConstruct.priorCharSet` should NOT be removed** -- raised and rejected this session. `lastCharSet` isn't dead now that sequences link `next` pointers directly; it's the compile-time `\b`/`\B` static-wordness optimization (`WordBoundaryConstruct.classify`'s subset/disjoint checks need an actual queryable `CodePointSet`, which a push-only API can't give it). Removing it wouldn't fail any test, just silently push every `\b` onto the runtime-check path -- noted here so it isn't attempted again without realizing that.
- [ ] `singletonCodePointMap` (used by `firstCharSet`/`lastCharSet`, and stale-named -- it's a `CodePointSet` now, not a `CodePointMap`) and the `QuantifiedUnion`-branch-union temporary sets inside those two methods are still real, un-eliminated small allocations -- left alone this session per the item above (converting `lastCharSet`'s callers to a push model isn't viable; `firstCharSet`'s one call site might be, see above, but wasn't converted). Worth renaming `singletonCodePointMap` to `singletonCodePointSet` while touching this.

## Remaining desktop-allocation-sampling leaders (2026-09-18)

Found via `:llkpattern:jmhAllocSampling` after `CodePointSetBuilder`'s own eager-array fix (see
notes.md's entry) knocked it out of the top 10. Neither item below was attempted this session --
both have a materially bigger blast radius than `CodePointSetBuilder` (a throwaway per-bracket-
expression builder) for a similar-sized win, so they need their own dedicated look rather than
being folded in opportunistically:

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

**Both items above now have one more caution attached, learned 2026-09-18** (see notes.md's three
entries that session): `CodePointSetBuilder` went through THREE further rewrites that session --
one array (`long[]`, packing `(min,max)` per entry), then a fully packed single `int[]` matching
`ArrayCodePointSet`'s own `(min<<11)|count` format exactly, then finally restructured as an
interface implemented by a class that literally IS-A `ArrayCodePointSet` (so `#build` returns
`this`, no separate final object at all) -- each measurably improving its one real caller
(`parseComplexCharacterRanges`'s literal members) a little further, down to 632,792 B/op from the
original 667,200. But converting three OTHER allocation-sampling leaders that looked like ideal
`CodePointSetBuilder` candidates (`PatternParser#intersect`, `#mergeRun`'s combine branch,
`PatternConstruct#mergeEntryPoints`'s main loop) to use it regressed allocation EVERY time this was
tried -- FOUR times, against four successively-more-optimized versions of the builder (including
the final IS-A-`ArrayCodePointSet` version, which should have eliminated the "two objects" cost
entirely), each attempt on the theory that the specific inefficiency just fixed was what had sunk
the previous attempt. Even after two more targeted fixes on top of the IS-A version (bypassing
`ArrayCodePointSet#addAll`'s own fast path; giving the builder a bigger starting capacity than
`ArrayCodePointSet`'s own), it STILL regressed -- both of those "fixes" made it measurably WORSE,
not better. The likely real explanation, this time genuinely load-bearing across all four attempts:
these three call sites' typical accumulation is small enough (often just 1-2 ranges) that ANY
builder-shaped approach -- extra object, extra array-growth bookkeeping, whatever the exact design
-- costs more than `ArrayCodePointSet`'s own direct sorted-insert-with-shift mutation, which has
no fixed overhead to amortize in the first place. This also rules out `CodePointSetBuilder` for the
`Sequence`/`Union` `ArrayList` item below it (also typically few elements) for the same reason.

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
Don't reach for `CodePointSetBuilder` as a general "any small accumulation" replacement without
measuring first, and don't re-attempt converting these three specific call sites a FIFTH time
without a fundamentally different idea, not just another tuning knob on the same "builder" concept
-- four independent variants of that concept have now all failed the same way.

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

## Fork-chain dispatch (2026-09-11/12) -- the performance plan

**Superseded 2026-09-18** (branch `flatten-matcher-dispatch`): step 1's `ForkingMatcherConstruct`
node and `LoopMatcherConstruct`'s own `memberSet`/`exitSet` fields (referenced below) no longer
exist -- the fork is now folded into every node via an `entrySet`/`failedEntry` pair instead of a
separate wrapping node. See design.md's "Opcode set" section for the current design and its
"Alternatives Considered" section for `ForkingMatcherConstruct`'s own pros/cons relative to it.
Steps 2 and 3 below (the `CodePointMap<Boolean>` -> `CodePointSet` migration, and
`UnionCodePointSet`) are unaffected by this and remain accurate.

Step 1 (2026-09-11): `DispatchMatcherConstruct`/`MultiDispatchingMatcherConstruct` (the
`CodePointMap<MatcherConstruct>`-table-backed N-way dispatch node) is gone, replaced by chains of a
new `ForkingMatcherConstruct` (a plain 2-way fork on set membership) for unions and a quantified
construct's own entry point, plus a related but separate `LoopMatcherConstruct` for a loop's own
continue-vs-exit choice -- see notes.md's 2026-09-11 entry for the case-insensitive priority bug
this surfaced and fixed along the way.

Step 2 (2026-09-12): every `CodePointMap<Boolean>` production use (`PatternConstruct.entryMap`,
`ComplexCharacter.ranges`, a dispatch node's own membership set(s), `WordBoundaryConstruct`'s
word-set classification, every `NamedCharClass`/`UnicodePredicates` constant) is now a plain
`CodePointSet`/`ArrayCodePointSet` -- no `V[] values`
array, and `complement()` is a flag flip (`invert`) instead of a real rebuild. `UnicodeAnalyzer`
(the `unicodeanalyzer` module's generator) updated to emit `CodePointSet` fields directly;
`UnicodePredicates.java` regenerated. `ArrayCodePointMap<V>`/`CodePointMap<V>` remain in use only
where a real multi-valued map is still needed: `mergeEntryPointsRaw`'s transient
`PatternConstruct`-valued ambiguity-check merge, and the general-purpose `String`-valued test
oracle (`TreeCodePointMap`/`CodePointMapDifferentialTest`). See notes.md's 2026-09-12 entry for a
real bug found along the way (a private "complement" constructor overload silently shadowing the
public copy constructor for any same-class argument -- fixed via a static factory instead of a
constructor overload).

Step 3 (2026-09-12): added `UnionCodePointSet`, a read-only lazy union of two delegate `CodePointSet`s
(see its own class doc). Wired into `PatternParser#parseComplexCharacterRanges`: a bracket run's
escape/nested-class contributions (`\d`, `\p{...}`, `[...]`) are unioned in by reference while the
run is being parsed, instead of each one being copied into the run's `CodePointSetBuilder`
immediately. The lazy union is **always materialized back into a concrete `ArrayCodePointSet`**
before the run's result leaves `parseComplexCharacterRanges` (`mergeRun`'s job) -- a `Union
CodePointSet` is measurably slower than `ArrayCodePointSet` at `contains`/`containsAll`/
`forEachRange` (extra virtual calls, or a full materialize), so letting one reach `ComplexCharacter
.ranges`/a compiled matcher would trade a one-time parse-time copy for a permanent match-time cost --
see notes.md's 2026-09-12 entry. Net effect: a bracket combining multiple large sets (e.g.
`[\d\w\s]`) now does its unioning in one pass at the end instead of copying each one in turn, but a
bracket with only one such contribution and no other members (e.g. `[\d]`) skips the copy entirely.
JMH before/after (`0.353`/`0.355` ms/op compile, `0.035`/`0.035` ms/op match) showed no measurable
match-time change and no measurable compile-time change either -- the corpus has few brackets with
multiple large named-class members, so this is a real but narrow win; not surprising given the
guard above intentionally limits its own scope. The "bracket-embedded named classes still merge"
item elsewhere in this file describes the same remaining copy-at-the-end behavior from a different
angle -- not fully superseded by this, since `mergeRun` still materializes eagerly today.

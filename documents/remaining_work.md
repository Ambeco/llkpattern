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

Design: `documents/tools/scrape_<source>.py` fetches a source project's own regex test data and
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
as JSON to `documents/benchmarks/corpus_benchmark_results.json` (only once the *entire* run
completes -- an interrupted run leaves that file empty), meant to be committed as a baseline and
diffed against on later runs to catch regressions.

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
recorded via `Debug.getGlobalGcInvocationCount()`. A CPU sampling profiler for `llkMatch`
(`testZZSamplingProfile`, an 8-frame-deep hand-rolled stack sampler) exists but is kept commented
out in the checked-in file -- see the entry below -- so the file only runs the four timing
benchmarks by default; uncomment it (and its imports, marked the same way) when profiling is
actually needed again.
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
      committed baseline (`documents/benchmarks/Google_Pixel_3a_sargo_corpus_benchmark_results.json`,
      full corpus, 1000 measured iterations, re-run 2026-09-08 after this session's `appendSorted`/
      `entryMap`-aliasing compile-time fixes): `llkCompile` ~6.3x slower than `regexCompile`
      (41.47ms vs 6.56ms/pass -- down from an earlier 101.7ms/6.7ms baseline before those fixes,
      consistent with the desktop-side improvement); `llkMatch` is actually *faster* than
      `regexMatch` at this row count and iteration depth (1.26ms vs 3.77ms/pass) -- notably
      different from the small-fraction run's `llkMatch` being slower, and from the desktop JMH
      ratio (see `corpus_benchmark_results.json`) where `llkMatch` is slower than `regexMatch` --
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
      Also captured a CPU sampling profile of `llkMatch` (`testZZSamplingProfile`, `-e profile
      true`, `PROFILE_ITERATIONS = 200` full-corpus passes): a hand-rolled sampler (a background
      thread periodically snapshotting the benchmark thread via `Thread.getAllStackTraces()`,
      truncated to `STACK_SAMPLE_DEPTH = 8` frames) rather than `Debug.startMethodTracingSampling`
      (tried first, but its sampling API has no way to cap stack depth -- see notes.md), aggregated
      into a plain-text table of hottest 8-frame call chains at
      `documents/benchmarks/Google_Pixel_3a_sargo_llkMatch_sampling.txt`. That test's own code is
      now commented out in `AndroidCorpusBenchmark.java` (both the method and its imports, each
      marked with instructions on where its counterpart is) so a routine run only does the four
      timing benchmarks -- uncomment both blocks, rebuild, and run just that method with:
      ```
      adb shell am instrument -w -e profile true \
          -e class com.tbohne.llkpattern.corpus.AndroidCorpusBenchmark#testZZSamplingProfile \
          com.tbohne.llkpattern.test/androidx.test.runner.AndroidJUnitRunner
      ```
      Worth re-running both (timing and, less often, sampling) on the other phones once convenient.
- [ ] If a device's `java.util.regex` disagrees with the golden files' recorded `regexMatchResult`
      (scraped on desktop), decide whether that's rare enough to ignore (the benchmark only times
      *speed*, not correctness, on-device) or common enough to need Android-specific golden columns
      or forked golden files -- not yet checked against a real device.
- [ ] **Investigate why the desktop `./gradlew :llkpattern:jmh` run (~5-6 minutes wall-clock) takes
      so much longer than `AndroidCorpusBenchmark` on a Pixel 3a (~45s-2min depending on config) --
      the project owner flagged this as surprising given the phone is much older/lower-end hardware.
      Likely at least partly explained by the two harnesses timing fundamentally different amounts
      of work rather than the same workload on different hardware: JMH's default here is a fixed
      **wall-clock** budget per iteration (3 warmup + 5 measured, 10s each, x4 benchmarks = ~320s
      just for the timing loop, before JVM/fork/Gradle-daemon startup), so it always runs as many
      corpus passes as fit in 10s regardless of how fast that is; `AndroidCorpusBenchmark` instead
      runs a fixed **iteration count** (currently 50 warmup / 1000 measured passes) and reports
      however long that happens to take. Worth confirming this actually accounts for the gap (e.g.
      by computing passes-per-second from each run's own numbers and comparing) before assuming
      it's a real hardware/JIT difference worth chasing.

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

- [ ] **Ambiguity-detection error quality**: `PatternConstruct.findFirstOverlap` (used by `QuantifiedUnion.buildEntryMap`'s ambiguity check) does an O(candidates × ranges) manual scan to find and report the first conflicting range, rather than using `CodePointMap.intersectionRejectingConflicts` directly — the latter throws immediately on any conflict but only carries stringified values, not the conflicting range/candidate needed for a useful `PatternSyntaxException`. Fine for realistic pattern sizes; revisit if this becomes a real cost, or if `intersectionRejectingConflicts`'s exception is ever extended to carry structured conflict info.
- [ ] **Reluctant/possessive quantifiers' permanent semantics**: the parser currently accepts and no-ops `?`/`+` quantifier modifiers (per its own comment, "reluctant and possessive quantifiers are no-ops in this Pattern"). Confirm this is the intended permanent semantic (i.e., this engine has one matching behavior, and the reluctant/possessive distinction from `java.util.regex` doesn't apply here) and document it prominently for users migrating from `java.util.regex`, rather than leaving it as an implicit consequence of "no backtracking."
